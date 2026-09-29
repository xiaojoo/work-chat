package com.chat.group.service;

import com.chat.group.config.InternalTokenIssuer;
import com.chat.group.dto.CreateGroupRequest;
import com.chat.group.dto.GroupDTO;
import com.chat.group.dto.GroupMemberDTO;
import com.chat.group.model.Group;
import com.chat.group.model.GroupMember;
import com.chat.group.repository.GroupMemberRepository;
import com.chat.group.repository.GroupRepository;
import com.chat.group.util.IdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class GroupService {

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final RestTemplate restTemplate;
    private final InternalTokenIssuer internalTokens;
    private final IdGenerator ids;

    @Value("${user-service.url:http://localhost:8081}")
    private String userServiceUrl;

    public GroupService(GroupRepository groupRepository,
                        GroupMemberRepository groupMemberRepository,
                        InternalTokenIssuer internalTokens,
                        IdGenerator ids) {
        this.groupRepository = groupRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.internalTokens = internalTokens;
        this.ids = ids;
        this.restTemplate = new RestTemplate();
    }

    /**
     * 创建群组
     */
    @Transactional
    public GroupDTO createGroup(Long ownerId, CreateGroupRequest request) {
        Group group = new Group();
        group.setId(ids.nextId());
        group.setName(request.getName());
        group.setAvatar(request.getAvatar());
        group.setOwnerId(ownerId);
        group.setAnnouncement(request.getAnnouncement());
        // 类型只认 1/2/3，其它值（包括前端漏传）一律落回普通群
        if (request.getGroupType() != null && request.getGroupType() >= 1 && request.getGroupType() <= 3) {
            group.setGroupType(request.getGroupType());
        }
        group.setStatus((short) 1);
        groupRepository.save(group);

        // 创建者自动加入为群主
        GroupMember ownerMember = new GroupMember();
        ownerMember.setId(ids.nextId());
        ownerMember.setGroupId(group.getId());
        ownerMember.setUserId(ownerId);
        ownerMember.setRole((short) 2); // 群主
        groupMemberRepository.save(ownerMember);

        // 创建群聊会话
        try {
            createGroupConversation(group.getId(), ownerId);
            bindUserToGroupConversation(ownerId, group.getId(), ownerId);
        } catch (Exception e) {
            // 会话创建失败不影响群创建
            e.printStackTrace();
        }

        return toDTO(group, 1);
    }

    /**
     * 创建群组并邀请成员（事务性）
     */
    @Transactional
    public GroupDTO createGroupWithMembers(Long ownerId, CreateGroupRequest request, List<Long> memberIds) {
        // 创建群组
        GroupDTO group = createGroup(ownerId, request);

        // 邀请成员
        if (memberIds != null && !memberIds.isEmpty()) {
            inviteMember(group.getId(), ownerId, memberIds);
        }

        // 成员是创建之后才补的，DTO 里的 memberCount 得按补完再查一次，否则永远回 1
        Group saved = groupRepository.findById(group.getId())
                .orElseThrow(() -> new RuntimeException("群组创建失败"));
        return toDTO(saved, (int) groupMemberRepository.countByGroupId(saved.getId()));
    }

    /**
     * 获取群信息
     */
    public GroupDTO getGroup(Long groupId) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("群组不存在"));
        long memberCount = groupMemberRepository.countByGroupId(groupId);
        return toDTO(group, (int) memberCount);
    }

    /**
     * 更新群信息
     */
    @Transactional
    public GroupDTO updateGroup(Long groupId, Long userId, String name, String avatar, String announcement) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("群组不存在"));

        GroupMember member = groupMemberRepository.findByGroupIdAndUserId(groupId, userId)
                .orElseThrow(() -> new RuntimeException("你不是群成员"));

        if (member.getRole() < 1) {
            throw new RuntimeException("没有权限修改群信息");
        }

        if (name != null) group.setName(name);
        if (avatar != null) group.setAvatar(avatar);
        if (announcement != null) group.setAnnouncement(announcement);
        groupRepository.save(group);

        long memberCount = groupMemberRepository.countByGroupId(groupId);
        return toDTO(group, (int) memberCount);
    }

    /**
     * 解散群
     */
    @Transactional
    public void dissolveGroup(Long groupId, Long userId) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("群组不存在"));

        if (userId == null) {
            throw new RuntimeException("用户ID不能为空");
        }

        if (!group.getOwnerId().equals(userId)) {
            throw new RuntimeException("只有群主可以解散群");
        }

        group.setStatus((short) 0);
        groupRepository.save(group);
    }

    /**
     * 邀请成员
     */
    @Transactional
    public void inviteMember(Long groupId, Long operatorId, List<Long> userIds) {
        GroupMember operator = groupMemberRepository.findByGroupIdAndUserId(groupId, operatorId)
                .orElseThrow(() -> new RuntimeException("你不是群成员"));

        if (operator.getRole() < 1) {
            throw new RuntimeException("没有权限邀请成员");
        }

        for (Long userId : userIds) {
            if (!groupMemberRepository.existsByGroupIdAndUserId(groupId, userId)) {
                GroupMember member = new GroupMember();
                member.setId(ids.nextId());
                member.setGroupId(groupId);
                member.setUserId(userId);
                member.setRole((short) 0);
                groupMemberRepository.save(member);

                // 绑定用户到群聊会话
                try {
                    bindUserToGroupConversation(userId, groupId, operatorId);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 移除成员
     */
    @Transactional
    public void removeMember(Long groupId, Long operatorId, Long targetUserId) {
        GroupMember operator = groupMemberRepository.findByGroupIdAndUserId(groupId, operatorId)
                .orElseThrow(() -> new RuntimeException("你不是群成员"));

        GroupMember target = groupMemberRepository.findByGroupIdAndUserId(groupId, targetUserId)
                .orElseThrow(() -> new RuntimeException("对方不是群成员"));

        if (operator.getRole() == 1 && target.getRole() >= 1) {
            throw new RuntimeException("没有权限移除该成员");
        }
        if (operator.getRole() == 0) {
            throw new RuntimeException("没有权限移除成员");
        }

        groupMemberRepository.delete(target);
    }

    /**
     * 退出群
     */
    @Transactional
    public void leaveGroup(Long groupId, Long userId) {
        GroupMember member = groupMemberRepository.findByGroupIdAndUserId(groupId, userId)
                .orElseThrow(() -> new RuntimeException("你不是群成员"));

        if (member.getRole() == 2) {
            throw new RuntimeException("群主不能退出，请先转让群主或解散群");
        }

        groupMemberRepository.delete(member);
    }

    /**
     * 设置成员角色
     */
    @Transactional
    public void setMemberRole(Long groupId, Long operatorId, Long targetUserId, Short role) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("群组不存在"));

        if (!group.getOwnerId().equals(operatorId)) {
            throw new RuntimeException("只有群主可以设置角色");
        }

        // 群主由 chat_group.owner_id 单独决定，把某人 role 改成 2 只会造出两个 2，
        // owner_id 却还指着原群主 —— 换群主必须走 transferOwner，两个地方一起改。
        if (role == null || role < 0 || role > 1) {
            throw new RuntimeException("只能设置普通成员或管理员，换群主请用转让群主");
        }

        GroupMember target = groupMemberRepository.findByGroupIdAndUserId(groupId, targetUserId)
                .orElseThrow(() -> new RuntimeException("对方不是群成员"));

        if (target.getRole() == 2) {
            throw new RuntimeException("群主不用设角色，请用转让群主");
        }

        target.setRole(role);
        groupMemberRepository.save(target);
    }

    /**
     * 转让群主。leaveGroup 的文案一直写着"请先转让群主"，但此前没有任何入口，
     * 群主只剩解散一条路可走。
     */
    @Transactional
    public void transferOwner(Long groupId, Long operatorId, Long targetUserId) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("群组不存在"));

        if (!group.getOwnerId().equals(operatorId)) {
            throw new RuntimeException("只有群主可以转让群主");
        }
        if (operatorId.equals(targetUserId)) {
            throw new RuntimeException("已经是群主了");
        }

        GroupMember target = groupMemberRepository.findByGroupIdAndUserId(groupId, targetUserId)
                .orElseThrow(() -> new RuntimeException("对方不是群成员"));

        GroupMember oldOwner = groupMemberRepository.findByGroupIdAndUserId(groupId, operatorId)
                .orElseThrow(() -> new RuntimeException("你不是群成员"));

        oldOwner.setRole((short) 0);
        groupMemberRepository.save(oldOwner);

        target.setRole((short) 2);
        // 转让后原群主被降权，若还挂着禁言会立刻变成"管理员被禁言"这种自相矛盾的状态
        target.setMuteUntil(null);
        groupMemberRepository.save(target);

        group.setOwnerId(targetUserId);
        groupRepository.save(group);
    }

    /**
     * 禁言成员
     */
    @Transactional
    public void muteMember(Long groupId, Long operatorId, Long targetUserId, LocalDateTime muteUntil) {
        GroupMember operator = groupMemberRepository.findByGroupIdAndUserId(groupId, operatorId)
                .orElseThrow(() -> new RuntimeException("你不是群成员"));

        if (operator.getRole() < 1) {
            throw new RuntimeException("没有权限禁言成员");
        }

        GroupMember target = groupMemberRepository.findByGroupIdAndUserId(groupId, targetUserId)
                .orElseThrow(() -> new RuntimeException("对方不是群成员"));

        if (target.getRole() >= operator.getRole()) {
            throw new RuntimeException("没有权限禁言该成员");
        }

        // null 或过去的时间就是解禁：界面上一颗"取消禁言"要能落到这里，
        // 否则被禁言的人只能等到点自动恢复，谁都没有手动放行入口。
        target.setMuteUntil(muteUntil != null && muteUntil.isAfter(LocalDateTime.now()) ? muteUntil : null);
        groupMemberRepository.save(target);
    }

    /**
     * 获取群成员列表
     */
    public List<GroupMemberDTO> getMembers(Long groupId) {
        return groupMemberRepository.findByGroupId(groupId).stream()
                .map(this::toMemberDTO)
                .collect(Collectors.toList());
    }

    /**
     * 获取用户所在的群列表
     */
    public List<GroupDTO> getUserGroups(Long userId) {
        List<GroupMember> memberships = groupMemberRepository.findByUserId(userId);
        List<Long> groupIds = memberships.stream()
                .map(GroupMember::getGroupId)
                .collect(Collectors.toList());

        if (groupIds.isEmpty()) return new ArrayList<>();

        return groupRepository.findByIdIn(groupIds).stream()
                .filter(g -> g.getStatus() == 1)
                .map(g -> {
                    long count = groupMemberRepository.countByGroupId(g.getId());
                    return toDTO(g, (int) count);
                })
                .collect(Collectors.toList());
    }

    /**
     * 检查用户是否是群成员
     */
    public boolean isMember(Long groupId, Long userId) {
        return groupMemberRepository.existsByGroupIdAndUserId(groupId, userId);
    }

    // ========== 内部方法 ==========

    private void createGroupConversation(Long groupId, Long actingUserId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", internalTokens.forUser(actingUserId));

        Map<String, Long> body = new HashMap<>();
        body.put("groupId", groupId);

        HttpEntity<Map<String, Long>> request = new HttpEntity<>(body, headers);
        restTemplate.postForObject(userServiceUrl + "/api/conversation/create-group", request, Map.class);
    }

    private void bindUserToGroupConversation(Long userId, Long groupId, Long actingUserId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", internalTokens.forUser(actingUserId));

        Map<String, Long> body = new HashMap<>();
        body.put("userId", userId);
        body.put("groupId", groupId);

        HttpEntity<Map<String, Long>> request = new HttpEntity<>(body, headers);
        restTemplate.postForObject(userServiceUrl + "/api/conversation/bind-group", request, Map.class);
    }

    private GroupDTO toDTO(Group group, int memberCount) {
        GroupDTO dto = new GroupDTO();
        dto.setId(group.getId());
        dto.setName(group.getName());
        dto.setAvatar(group.getAvatar());
        dto.setOwnerId(group.getOwnerId());
        dto.setAnnouncement(group.getAnnouncement());
        dto.setGroupType(group.getGroupType());
        dto.setMemberCount(memberCount);
        return dto;
    }

    private GroupMemberDTO toMemberDTO(GroupMember member) {
        GroupMemberDTO dto = new GroupMemberDTO();
        dto.setId(member.getId());
        dto.setGroupId(member.getGroupId());
        dto.setUserId(member.getUserId());
        dto.setRole(member.getRole());
        dto.setMuteUntil(member.getMuteUntil());
        dto.setMuted(member.getMuteUntil() != null && member.getMuteUntil().isAfter(LocalDateTime.now()));
        dto.setJoinTime(member.getJoinTime());
        return dto;
    }
}
