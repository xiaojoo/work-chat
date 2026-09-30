package com.chat.user.controller;

import com.chat.user.service.ControlService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 远程控制话单接口。身份只认验过签名的令牌，客户端不许自报"我是谁发起的"。
 * 网关调的是内部短期令牌（X-Internal-Token），浏览器/手机调的是普通 access token。
 */
@RestController
@RequestMapping("/api/control")
@CrossOrigin(origins = "*")
public class ControlController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ControlController.class);

    private final ControlService svc;

    public ControlController(ControlService svc) { this.svc = svc; }

    private Long needUser(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof Long) return (Long) auth.getPrincipal();
        throw new RuntimeException("用户ID不能为空");
    }

    @PostMapping("/request")
    public Map<String, Object> request(Authentication auth, @RequestBody Map<String, Object> body) {
        String sid = str(body.get("sessionId"));
        Long targetId = num(body.get("targetId"));
        String conv = str(body.get("conversationId"));
        if (sid == null || targetId == null) throw new RuntimeException("sessionId 和 targetId 不能为空");
        svc.request(sid, needUser(auth), targetId, conv);
        return Map.of("ok", true);
    }

    @PostMapping("/accept")
    public Map<String, Object> accept(Authentication auth, @RequestBody Map<String, Object> body) {
        String sid = str(body.get("sessionId"));
        Long uid = needUser(auth);
        log.info("control.accept sid={} by={}", sid, uid);
        svc.accept(sid, uid, numOr(body.get("initiatorId"), uid), numOr(body.get("targetId"), 0L), str(body.get("conversationId")));
        return Map.of("ok", true);
    }

    @PostMapping("/reject")
    public Map<String, Object> reject(Authentication auth, @RequestBody Map<String, Object> body) {
        String sid = str(body.get("sessionId"));
        Long uid = needUser(auth);
        log.info("control.reject sid={} by={}", sid, uid);
        svc.reject(sid, uid, numOr(body.get("initiatorId"), uid), numOr(body.get("targetId"), 0L), str(body.get("conversationId")));
        return Map.of("ok", true);
    }

    @PostMapping("/stop")
    public Map<String, Object> stop(Authentication auth, @RequestBody Map<String, Object> body) {
        String sid = str(body.get("sessionId"));
        Long uid = needUser(auth);
        log.info("control.stop sid={} by={}", sid, uid);
        svc.stop(sid, uid, numOr(body.get("initiatorId"), uid), numOr(body.get("targetId"), 0L), str(body.get("conversationId")));
        return Map.of("ok", true);
    }

    @PostMapping("/timeout")
    public Map<String, Object> timeout(Authentication auth, @RequestBody Map<String, Object> body) {
        String sid = str(body.get("sessionId"));
        Long uid = needUser(auth);
        svc.timeout(sid, numOr(body.get("initiatorId"), uid), numOr(body.get("targetId"), 0L), str(body.get("conversationId")));
        return Map.of("ok", true);
    }

    @PostMapping("/disconnected")
    public Map<String, Object> disconnected(Authentication auth, @RequestBody Map<String, Object> body) {
        String sid = str(body.get("sessionId"));
        Long uid = needUser(auth);
        svc.disconnected(sid, uid, numOr(body.get("initiatorId"), uid), numOr(body.get("targetId"), 0L), str(body.get("conversationId")));
        return Map.of("ok", true);
    }

    @GetMapping("/records")
    public List<Map<String, Object>> records(Authentication auth,
                                             @RequestParam(defaultValue = "50") int limit) {
        return svc.records(needUser(auth), limit);
    }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }
    private static Long num(Object v) { return v instanceof Number ? ((Number) v).longValue() : null; }
    private static long numOr(Object v, long fallback) { return v instanceof Number ? ((Number) v).longValue() : fallback; }
}
