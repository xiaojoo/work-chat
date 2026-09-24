package com.chat.user.controller;

import com.chat.user.model.User;
import com.chat.user.repository.UserRepository;
import com.chat.user.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/user")
@CrossOrigin(origins = "*")
public class UserController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    public UserController(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          org.springframework.data.redis.core.StringRedisTemplate redisTemplate) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.redisTemplate = redisTemplate;
    }

    private Long getUserId(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof Long) {
            return (Long) auth.getPrincipal();
        }
        return null;
    }

    @GetMapping("/{userId}")
    public ResponseEntity<Map<String, Object>> getUser(@PathVariable Long userId) {
        return userRepository.findById(userId)
                .map(user -> ResponseEntity.ok(toPublicMap(user)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> searchUser(@RequestParam String username) {
        return userRepository.findByUsername(username)
                .map(user -> ResponseEntity.ok(toPublicMap(user)))
                .orElseGet(() -> ResponseEntity.ok(Map.of("error", "用户不存在")));
    }

    /**
     * 按用户名或昵称模糊搜索用户（不区分大小写），返回用户列表
     */
    @GetMapping("/search/users")
    public ResponseEntity<List<Map<String, Object>>> searchUsersByKeyword(@RequestParam String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        String kw = keyword.trim();
        return ResponseEntity.ok(
                userRepository.findByUsernameContainingIgnoreCaseOrNicknameContainingIgnoreCase(kw, kw)
                        .stream()
                        .map(this::toPublicMap)
                        .collect(Collectors.toList())
        );
    }

    /**
     * 获取当前用户信息
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getMe(Authentication auth) {
        Long userId = getUserId(auth);
        if (userId == null) return ResponseEntity.status(401).build();

        return userRepository.findById(userId)
                .map(user -> {
                    Map<String, Object> result = toPublicMap(user);
                    result.put("status", user.getStatus());
                    return ResponseEntity.ok(result);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 更新用户资料（昵称、头像）
     */
    @PutMapping("/profile")
    public ResponseEntity<Map<String, Object>> updateProfile(
            Authentication auth,
            @RequestBody Map<String, String> body) {
        Long userId = getUserId(auth);
        if (userId == null) return ResponseEntity.status(401).build();

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        if (body.containsKey("nickname") && body.get("nickname") != null) {
            user.setNickname(body.get("nickname"));
        }
        if (body.containsKey("avatar") && body.get("avatar") != null) {
            user.setAvatar(body.get("avatar"));
        }
        if (body.containsKey("department") && body.get("department") != null) {
            user.setDepartment(body.get("department"));
        }
        if (body.containsKey("position") && body.get("position") != null) {
            user.setPosition(body.get("position"));
        }
        if (body.containsKey("email") && body.get("email") != null) {
            user.setEmail(body.get("email"));
        }
        if (body.containsKey("phone") && body.get("phone") != null) {
            user.setPhone(body.get("phone"));
        }
        if (body.containsKey("bio") && body.get("bio") != null) {
            String bio = body.get("bio").trim();
            if (bio.length() > 200) bio = bio.substring(0, 200);   // 前端也限 200，这里兜住直接打接口的人
            user.setBio(bio);
        }
        userRepository.save(user);

        return ResponseEntity.ok(toPublicMap(user));
    }

    /**
     * 修改密码
     */
    @PostMapping("/change-password")
    public ResponseEntity<Map<String, String>> changePassword(
            Authentication auth,
            @RequestBody Map<String, String> body) {
        Long userId = getUserId(auth);
        if (userId == null) return ResponseEntity.status(401).build();

        String oldPassword = body.get("oldPassword");
        String newPassword = body.get("newPassword");

        if (oldPassword == null || newPassword == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "参数不完整"));
        }
        if (newPassword.length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("error", "新密码长度至少6位"));
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            return ResponseEntity.badRequest().body(Map.of("error", "原密码错误"));
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("message", "密码已修改"));
    }

    private Map<String, Object> toPublicMap(User user) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        result.put("nickname", user.getNickname() != null ? user.getNickname() : user.getUsername());
        result.put("avatar", user.getAvatar() != null ? user.getAvatar() : "");
        result.put("location", user.getLocation() != null ? user.getLocation() : "");
        result.put("phone", user.getPhone() != null ? user.getPhone() : "");
        result.put("remark", user.getRemark() != null ? user.getRemark() : "");
        result.put("department", user.getDepartment() != null ? user.getDepartment() : "");
        result.put("position", user.getPosition() != null ? user.getPosition() : "");
        result.put("email", user.getEmail() != null ? user.getEmail() : "");
        result.put("bio", user.getBio() != null ? user.getBio() : "");
        return result;
    }

    /**
     * 在线用户 id 集合：网关把连接写进 Redis 的 user:online:{id}，这里只读出来。
     * 没有这个接口，稿子里的成员在线点就只能画假的。
     */
    @GetMapping("/online")
    public ResponseEntity<java.util.Set<Long>> onlineUserIds() {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        try {
            for (String key : redisTemplate.keys("user:online:*")) {
                String idPart = key.substring("user:online:".length());
                try {
                    // 哈希键就是完整的 user:online:{id}，别把削掉前缀的 id 当键查
                    if (!redisTemplate.<String, String>opsForHash().entries(key).isEmpty()) {
                        ids.add(Long.valueOf(idPart));
                    }
                } catch (NumberFormatException ignored) {
                    // 不是 userId 形态的键，跳过
                }
            }
        } catch (Exception e) {
            // Redis 不可用时返回空集合：界面按"全部离线"显示，好过画假绿点
            System.err.println("读取在线状态失败（按全部离线处理）: " + e);
            return ResponseEntity.ok(ids);
        }
        return ResponseEntity.ok(ids);
    }

    /**
     * 组织树：按部门分组并计数，设计稿「添加用户」的左侧结构。未填部门的归到「未分配」。
     */
    @GetMapping("/org")
    public ResponseEntity<List<Map<String, Object>>> org() {
        java.util.Map<String, List<Map<String, Object>>> byDept = new java.util.TreeMap<>();
        for (User u : userRepository.findAll()) {
            String dept = u.getDepartment() == null || u.getDepartment().isBlank() ? "未分配" : u.getDepartment();
            byDept.computeIfAbsent(dept, k -> new java.util.ArrayList<>()).add(toPublicMap(u));
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        byDept.forEach((dept, members) -> {
            Map<String, Object> node = new java.util.LinkedHashMap<>();
            node.put("department", dept);
            node.put("count", members.size());
            node.put("members", members);
            out.add(node);
        });
        return ResponseEntity.ok(out);
    }
}
