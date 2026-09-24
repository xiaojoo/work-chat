package com.chat.user.controller;

import com.chat.user.dto.FriendDTO;
import com.chat.user.dto.FriendRequest;
import com.chat.user.service.FriendService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/friend")
@CrossOrigin(origins = "*")
public class FriendController {

    private final FriendService friendService;

    public FriendController(FriendService friendService) {
        this.friendService = friendService;
    }

    private Long getUserId(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof Long) {
            return (Long) auth.getPrincipal();
        }
        return null;
    }

    @PostMapping("/add")
    public ResponseEntity<Map<String, String>> addFriend(
            Authentication auth,
            @Valid @RequestBody FriendRequest reqBody) {
        Long userId = getUserId(auth);
        friendService.addFriend(userId, reqBody.getFriendId());
        Map<String, String> result = new HashMap<>();
        result.put("message", "已添加好友");
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/remove/{friendId}")
    public ResponseEntity<Map<String, String>> removeFriend(
            Authentication auth,
            @PathVariable Long friendId) {
        Long userId = getUserId(auth);
        friendService.removeFriend(userId, friendId);
        Map<String, String> result = new HashMap<>();
        result.put("message", "已删除好友");
        return ResponseEntity.ok(result);
    }

    @GetMapping("/list")
    public ResponseEntity<List<FriendDTO>> getFriendList(Authentication auth) {
        return ResponseEntity.ok(friendService.getFriendList(getUserId(auth)));
    }

    @GetMapping("/check/{friendId}")
    public ResponseEntity<Map<String, Boolean>> checkFriend(
            Authentication auth,
            @PathVariable Long friendId) {
        Map<String, Boolean> result = new HashMap<>();
        result.put("isFriend", friendService.areFriends(getUserId(auth), friendId));
        return ResponseEntity.ok(result);
    }
}
