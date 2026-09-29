package com.chat.user.controller;

import com.chat.user.service.CallService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 通话话单。身份只来自验过签名的令牌，不接受自报的 userId ——
 * 否则任何人都能给别人写一条"未接"。
 */
@RestController
@RequestMapping("/api/call")
@CrossOrigin(origins = "*")
public class CallController {

    private final CallService callService;

    public CallController(CallService callService) {
        this.callService = callService;
    }

    private Long needUser(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof Long) {
            return (Long) auth.getPrincipal();
        }
        throw new RuntimeException("用户ID不能为空");
    }

    @PostMapping("/start")
    public Map<String, Object> start(Authentication auth, @RequestBody Map<String, Object> body) {
        Long me = needUser(auth);
        String room = (String) body.get("room");
        Long calleeId = num(body.get("calleeId"));
        String media = (String) body.getOrDefault("mediaType", "AUDIO");
        if (room == null || room.isEmpty() || calleeId == null) {
            throw new RuntimeException("room 和 calleeId 不能为空");
        }
        return Map.of("callId", callService.start(room, me, calleeId, media), "room", room);
    }

    @PostMapping("/connect")
    public Map<String, Object> connect(Authentication auth, @RequestBody Map<String, Object> body) {
        callService.connect((String) body.get("room"), needUser(auth));
        return Map.of("message", "已接通");
    }

    @PostMapping("/end")
    public Map<String, Object> end(Authentication auth, @RequestBody Map<String, Object> body) {
        String reason = (String) body.getOrDefault("reason", "HANGUP");
        return callService.end((String) body.get("room"), needUser(auth), reason);
    }

    @GetMapping("/records")
    public List<Map<String, Object>> records(Authentication auth,
                                             @RequestParam(defaultValue = "50") int limit) {
        return callService.records(needUser(auth), limit);
    }

    private static Long num(Object v) {
        return v instanceof Number ? ((Number) v).longValue() : null;
    }
}
