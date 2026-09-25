package com.chat.user.service;

import com.chat.user.dto.ConversationDTO;
import com.chat.user.model.Conversation;
import com.chat.user.model.User;
import com.chat.user.model.UserConversation;
import com.chat.user.repository.ConversationRepository;
import com.chat.user.repository.UserConversationRepository;
import com.chat.user.repository.UserRepository;
import com.chat.user.util.IdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class ConversationService {

    /** last_message_id 只用来做侧栏预览，留 100 字足够看清是哪条 */
    private static final int PREVIEW_MAX = 100;
    private static final tools.jackson.databind.ObjectMapper FILE_JSON =
            new tools.jackson.databind.ObjectMapper();

    private final ConversationRepository conversationRepository;
    private final UserConversationRepository userConversationRepository;
    private final UserRepository userRepository;
    private final RestTemplate restTemplate;
    private final com.chat.user.config.InternalTokenIssuer internalTokens;
    private final IdGenerator ids;

    @Value("${group-service.url:http://localhost:8082}")
    private String groupServiceUrl;

    public ConversationService(ConversationRepository conversationRepository,
                               UserConversationRepository userConversationRepository,
                               UserRepository userRepository,
                               com.chat.user.config.InternalTokenIssuer internalTokens,
                               IdGenerator ids) {
        this.conversationRepository = conversationRepository;
        this.userConversationRepository = userConversationRepository;
        this.userRepository = userRepository;
        this.internalTokens = internalTokens;
        this.ids = ids;
        this.restTemplate = new RestTemplate();
    }

    /**
     * 获取或创建一对一会话。
     * 会话键是 (user_a_id, user_b_id) 这一对，不再用 min*10^9+max 之类的算术编码 ——
     * userId 是 16 位雪花量级，乘 10^9 直接溢出 long（实测得到负数并会回绕碰撞），
     * 500 人规模下等于把别人的会话串到自己列表里。
     */
    @Transactional
    public Conversation getOrCreatePrivateConversation(Long userId1, Long userId2) {
        Long a = Math.min(userId1, userId2);
        Long b = Math.max(userId1, userId2);

        Conversation conv = conversationRepository.findByUserAIdAndUserBId(a, b).orElse(null);
        if (conv == null) {
            conv = new Conversation();
            conv.setId(ids.nextId());
            conv.setType((short) 1);
            conv.setUserAId(a);
            conv.setUserBId(b);
            conversationRepository.save(conv);
        }

        // 确保双方都有绑定
        bindUserConversation(userId1, conv.getId());
        bindUserConversation(userId2, conv.getId());

        return conv;
    }

    /**
     * 创建群聊会话（供群组服务调用）
     */
    @Transactional
    public Conversation createGroupConversation(Long groupId) {
        Optional<Conversation> existing = conversationRepository.findByTypeAndTargetId((short) 2, groupId);
        if (existing.isPresent()) {
            return existing.get();
        }

        Conversation conv = new Conversation();
        conv.setId(ids.nextId());
        conv.setType((short) 2);
        conv.setTargetId(groupId);
        conversationRepository.save(conv);

        return conv;
    }

    /**
     * 绑定用户与会话
     */
    @Transactional
    public void bindUserConversation(Long userId, Long conversationId) {
        Optional<UserConversation> existing = userConversationRepository
                .findByUserIdAndConversationId(userId, conversationId);
        if (existing.isPresent()) return;

        UserConversation uc = new UserConversation();
        uc.setId(ids.nextId());
        uc.setUserId(userId);
        uc.setConversationId(conversationId);
        uc.setUnreadCount(0);
        userConversationRepository.save(uc);
    }

    /**
     * 获取会话列表
     */
    public List<ConversationDTO> getConversationList(Long userId) {
        List<UserConversation> userConvs = userConversationRepository
                .findByUserIdOrderByUpdateTimeDesc(userId);

        List<ConversationDTO> result = new ArrayList<>();
        for (UserConversation uc : userConvs) {
            // 跳过已删除的会话
            if (Boolean.TRUE.equals(uc.getDeleted())) continue;
            conversationRepository.findById(uc.getConversationId()).ifPresent(conv -> {
                ConversationDTO dto = new ConversationDTO();
                dto.setType(conv.getType());
                dto.setTargetId(conv.getTargetId());
                // 群聊使用 g{targetId} 格式，与 gateway 和消息服务保持一致
                if (conv.getType() == 2) {
                    dto.setId("g" + conv.getTargetId());
                } else {
                    dto.setId(conv.getId());
                }
                dto.setLastMessage(uc.getLastMessageId());
                dto.setLastMessageTime(uc.getLastMessageTime());
                dto.setUnreadCount(uc.getUnreadCount());

                if (conv.getType() == 1) {
                    fillPrivateCounterpart(userId, conv, dto);
                } else if (conv.getType() == 2) {
                    resolveGroupConversationName(userId, conv, dto);
                }

                result.add(dto);
            });
        }
        return result;
    }

    private void fillPrivateCounterpart(Long userId, Conversation conv, ConversationDTO dto) {
        // 通过 user_conversation 表找到会话中的另一个用户
        List<UserConversation> bindings = userConversationRepository.findByConversationId(conv.getId());
        
        for (UserConversation uc : bindings) {
            if (!uc.getUserId().equals(userId)) {
                Long otherUserId = uc.getUserId();
                // 私聊在 conversation 表里只有 userA/userB 两列，targetId 一直是 null，
                // 前端拿不到"对面是谁"（成员宫格要点对方头像、单聊转群聊要带对方 id），这里补上
                dto.setTargetId(otherUserId);
                userRepository.findById(otherUserId).ifPresent(user -> {
                    dto.setName(user.getNickname() != null ? user.getNickname() : user.getUsername());
                    dto.setAvatar(user.getAvatar());
                });
                return;
            }
        }
        
        dto.setName("私聊");
    }

    private void resolveGroupConversationName(Long userId, Conversation conv, ConversationDTO dto) {
        try {
            // 通过群组服务获取真实群名
            String url = groupServiceUrl + "/api/group/" + conv.getTargetId();
            org.springframework.http.HttpEntity<Void> request = new org.springframework.http.HttpEntity<>(createInternalHeaders(userId));
            org.springframework.http.ResponseEntity<Map> resp = restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, request, Map.class);
            if (resp.getBody() != null && resp.getBody().get("name") != null) {
                dto.setName((String) resp.getBody().get("name"));
                if (resp.getBody().get("avatar") != null) {
                    dto.setAvatar((String) resp.getBody().get("avatar"));
                }
                return;
            }
        } catch (Exception e) {
            // 群组服务不可用时降级
        }
        dto.setName("群 " + conv.getTargetId());
    }

    private org.springframework.http.HttpHeaders createInternalHeaders(Long actingUserId) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("X-Internal-Token", internalTokens.forUser(actingUserId));
        return headers;
    }

    /**
     * 更新最后消息（更新会话中所有用户）
     * 网关对群聊传的是削掉 g 前缀后的群 ID，所以这里两种 ID 都要认。
     */
    @Transactional
    public void updateLastMessage(Long conversationId, Long senderId, String messageId, String content) {
        resolveConversation(conversationId).ifPresent(conv -> {
            conv.setUpdateTime(LocalDateTime.now());
            conversationRepository.save(conv);

            // 更新所有绑定到这个会话的用户：必须用解析后的真实会话 id，入参可能是群 ID
            List<UserConversation> bindings = userConversationRepository.findByConversationId(conv.getId());
            String preview = previewOf(content);
            for (UserConversation uc : bindings) {
                uc.setLastMessageId(preview);
                uc.setLastMessageTime(LocalDateTime.now());
                if (!uc.getUserId().equals(senderId)) {
                    uc.setUnreadCount(uc.getUnreadCount() + 1);
                }
                // 收到新消息时取消删除状态
                uc.setDeleted(false);
                userConversationRepository.save(uc);
            }
        });
    }

    /**
     * 增加未读消息数
     */
    @Transactional
    public void incrementUnreadCount(Long conversationId, Long userId) {
        userConversationRepository.findByUserIdAndConversationId(userId, conversationId)
                .ifPresent(uc -> {
                    uc.setUnreadCount(uc.getUnreadCount() + 1);
                    userConversationRepository.save(uc);
                });
    }

    /**
     * 清除未读消息数
     */
    @Transactional
    public void clearUnreadCount(Long conversationId, Long userId) {
        userConversationRepository.findByUserIdAndConversationId(userId, conversationId)
                .ifPresent(uc -> {
                    uc.setUnreadCount(0);
                    userConversationRepository.save(uc);
                });
    }

    public Optional<Long> findConversationId(Long userId, Long friendId) {
        Long a = Math.min(userId, friendId);
        Long b = Math.max(userId, friendId);
        return conversationRepository.findByUserAIdAndUserBId(a, b).map(Conversation::getId);
    }

    /**
     * 获取会话中的所有用户ID
     */
    public List<Long> getConversationUserIds(Long conversationId) {
        return userConversationRepository.findByConversationId(conversationId)
                .stream()
                .map(UserConversation::getUserId)
                .collect(Collectors.toList());
    }

    /**
     * 删除用户与会话的绑定（软删除，标记 deleted=true）
     * 支持两种 ID：
     * 1. 会话真实 id（私聊场景）
     * 2. 群 ID（前端列表 id 为 "g{groupId}"，提取后传的是 groupId，需先转为会话真实 id）
     */
    @Transactional
    public void deleteConversationForUser(Long userId, Long conversationId) {
        // 情况1：直接用 conversationId 查绑定
        Optional<UserConversation> opt = userConversationRepository
                .findByUserIdAndConversationId(userId, conversationId);
        if (opt.isPresent()) {
            markDeleted(opt.get());
            return;
        }

        // 情况2：conversationId 可能是群 ID（type=2, target_id=群ID），先转为会话真实 id
        resolveConversation(conversationId).ifPresent(conv ->
                userConversationRepository.findByUserIdAndConversationId(userId, conv.getId())
                        .ifPresent(this::markDeleted));
    }

    /**
     * 入参可能是会话真实 id，也可能是群 ID（网关对群聊只带得出手里的群 ID），两种都要认。
     */
    private Optional<Conversation> resolveConversation(Long conversationIdOrGroupId) {
        Conversation byId = conversationRepository.findById(conversationIdOrGroupId).orElse(null);
        if (byId != null) {
            return Optional.of(byId);
        }
        return conversationRepository.findByTypeAndTargetId((short) 2, conversationIdOrGroupId);
    }

    /**
     * 侧栏预览串：文件/图片消息存 "[图片] 名字"，长文本截断。
     * last_message_id 是 VARCHAR(255)，直接塞原文的话，超长消息会让整次更新失败。
     */
    private String previewOf(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String s = content.trim();
        if (s.startsWith("{") && s.contains("\"fileId\"")) {
            try {
                var n = FILE_JSON.readTree(s);
                String type = n.path("contentType").asText("");
                String name = n.path("name").asText("");
                return (type.startsWith("image/") ? "[图片] " : "[文件] ")
                        + (name.isEmpty() ? "文件" : name);
            } catch (Exception e) {
                return s.substring(0, Math.min(s.length(), PREVIEW_MAX));
            }
        }
        return s.length() <= PREVIEW_MAX ? s : s.substring(0, PREVIEW_MAX);
    }

    private void markDeleted(UserConversation uc) {
        uc.setDeleted(true);
        userConversationRepository.save(uc);
    }
}
