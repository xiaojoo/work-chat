package com.chat.group.controller;

import com.chat.group.config.AuthFilter;
import com.chat.group.dto.CreateGroupRequest;
import com.chat.group.dto.GroupDTO;
import com.chat.group.dto.GroupMemberDTO;
import com.chat.group.service.GroupService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/group")
@CrossOrigin(origins = "*")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    /** 身份只来自 AuthFilter 验过签名的令牌，不再读 X-User-Id */
    private Long getUserId(HttpServletRequest request) {
        Object userId = request.getAttribute(AuthFilter.ATTR_USER_ID);
        return userId == null ? null : (Long) userId;
    }

    /** 要写操作时用的那一个：拿不到签名身份就是没登录，宁可报错也不许退化成"调用方自报" */
    private Long needUser(HttpServletRequest request) {
        Long uid = getUserId(request);
        if (uid == null) {
            throw new RuntimeException("用户ID不能为空");
        }
        return uid;
    }

    /**
     * 创建群
     */
    @PostMapping("/create")
    public ResponseEntity<GroupDTO> createGroup(
            HttpServletRequest request,
            @Valid @RequestBody CreateGroupRequest body) {
        Long userId = getUserId(request);
        if (userId == null) {
            throw new RuntimeException("用户ID不能为空");
        }

        // 如果有成员列表，使用创建并邀请方法
        if (body.getMemberIds() != null && !body.getMemberIds().isEmpty()) {
            return ResponseEntity.ok(groupService.createGroupWithMembers(userId, body, body.getMemberIds()));
        }

        return ResponseEntity.ok(groupService.createGroup(userId, body));
    }

    /**
     * 获取群信息
     */
    @GetMapping("/{groupId}")
    public ResponseEntity<GroupDTO> getGroup(@PathVariable Long groupId) {
        return ResponseEntity.ok(groupService.getGroup(groupId));
    }

    /**
     * 更新群信息
     */
    @PutMapping("/{groupId}")
    public ResponseEntity<GroupDTO> updateGroup(
            HttpServletRequest request,
            @PathVariable Long groupId,
            @RequestBody Map<String, String> body) {
        // 操作者只认令牌里的身份。原来这里收 ?userId=，谁都能报一个别人的 id 来改别人的群
        Long userId = needUser(request);
        return ResponseEntity.ok(groupService.updateGroup(groupId, userId,
                body.get("name"), body.get("avatar"), body.get("announcement")));
    }

    /**
     * 解散群
     */
    @DeleteMapping("/{groupId}")
    public ResponseEntity<Map<String, String>> dissolveGroup(
            HttpServletRequest request,
            @PathVariable Long groupId) {
        // 同上：解散谁都能报一个别人的群 id，但"你是不是群主"得按令牌里这个人来判
        groupService.dissolveGroup(groupId, needUser(request));
        return ResponseEntity.ok(Map.of("message", "群已解散"));
    }

    /**
     * 邀请成员
     */
    @PostMapping("/{groupId}/invite")
    public ResponseEntity<Map<String, String>> inviteMember(
            HttpServletRequest request,
            @PathVariable Long groupId,
            @RequestBody Map<String, Object> body) {
        Long userId = getUserId(request);
        if (userId == null) {
            throw new RuntimeException("操作者ID不能为空");
        }

        @SuppressWarnings("unchecked")
        List<Long> userIds = (List<Long>) body.get("userIds");
        groupService.inviteMember(groupId, userId, userIds);
        return ResponseEntity.ok(Map.of("message", "已邀请"));
    }

    /**
     * 移除成员
     */
    @DeleteMapping("/{groupId}/member/{targetUserId}")
    public ResponseEntity<Map<String, String>> removeMember(
            HttpServletRequest request,
            @PathVariable Long groupId,
            @PathVariable Long targetUserId) {
        groupService.removeMember(groupId, needUser(request), targetUserId);
        return ResponseEntity.ok(Map.of("message", "已移除"));
    }

    /**
     * 退出群
     */
    @PostMapping("/{groupId}/leave")
    public ResponseEntity<Map<String, String>> leaveGroup(
            HttpServletRequest request,
            @PathVariable Long groupId) {
        groupService.leaveGroup(groupId, needUser(request));
        return ResponseEntity.ok(Map.of("message", "已退出"));
    }

    /**
     * 设置成员角色
     */
    @PutMapping("/{groupId}/member/{targetUserId}/role")
    public ResponseEntity<Map<String, String>> setMemberRole(
            HttpServletRequest request,
            @PathVariable Long groupId,
            @PathVariable Long targetUserId,
            @RequestBody Map<String, Short> body) {
        Long userId = getUserId(request);
        groupService.setMemberRole(groupId, userId, targetUserId, body.get("role"));
        return ResponseEntity.ok(Map.of("message", "已设置"));
    }

    /**
     * 禁言成员；muteUntil 缺省或为过去时间是解禁
     */
    @PostMapping("/{groupId}/member/{targetUserId}/mute")
    public ResponseEntity<Map<String, String>> muteMember(
            HttpServletRequest request,
            @PathVariable Long groupId,
            @PathVariable Long targetUserId,
            @RequestBody Map<String, String> body) {
        Long userId = getUserId(request);
        String raw = body == null ? null : body.get("muteUntil");
        LocalDateTime muteUntil = (raw == null || raw.isBlank()) ? null : LocalDateTime.parse(raw);
        groupService.muteMember(groupId, userId, targetUserId, muteUntil);
        return ResponseEntity.ok(Map.of("message", muteUntil == null ? "已解除禁言" : "已禁言"));
    }

    /**
     * 转让群主
     */
    @PostMapping("/{groupId}/transfer")
    public ResponseEntity<Map<String, String>> transferOwner(
            HttpServletRequest request,
            @PathVariable Long groupId,
            @RequestBody Map<String, Object> body) {
        Long userId = getUserId(request);
        Object raw = body == null ? null : body.get("targetUserId");
        if (raw == null) {
            throw new RuntimeException("targetUserId 不能为空");
        }
        groupService.transferOwner(groupId, userId, ((Number) raw).longValue());
        return ResponseEntity.ok(Map.of("message", "已转让群主"));
    }

    /**
     * 获取群成员列表
     */
    @GetMapping("/{groupId}/members")
    public ResponseEntity<List<GroupMemberDTO>> getMembers(@PathVariable Long groupId) {
        return ResponseEntity.ok(groupService.getMembers(groupId));
    }

    /**
     * 获取用户所在的群列表
     */
    @GetMapping("/my")
    public ResponseEntity<List<GroupDTO>> getMyGroups(HttpServletRequest request) {
        return ResponseEntity.ok(groupService.getUserGroups(needUser(request)));
    }
}
