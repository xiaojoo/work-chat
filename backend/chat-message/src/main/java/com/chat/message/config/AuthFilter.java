package com.chat.message.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 消息服务此前完全裸奔：senderId 从请求体自取，任何人都能以别人名义存消息、标记别人已读。
 * 现在每个请求必须带浏览器 access 令牌或网关签发的短期内部令牌，身份只从签名里取。
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    public static final String ATTR_USER_ID = "chat.userId";
    public static final String INTERNAL_ISSUER = "chat-gateway";

    private final SecretKey userKey;
    private final SecretKey internalKey;

    public AuthFilter(@Value("${jwt.secret}") String userSecret,
                      @Value("${internal.jwt.secret:}") String internalSecret) {
        this.userKey = Keys.hmacShaKeyFor(userSecret.getBytes(StandardCharsets.UTF_8));
        this.internalKey = internalSecret.isBlank() ? null
                : Keys.hmacShaKeyFor(internalSecret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            // 回显来源而不是 *：桌面壳的来源是 app://chat 这种非 http 方案
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Vary", "Origin");
            response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            response.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Internal-Token");
            response.setHeader("Access-Control-Max-Age", "600");
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            // 预检不带令牌，必须挡在鉴权之前放行，否则跨源的列表接口全挂
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }

        String bearer = request.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            Long userId = parseUserToken(bearer.substring(7));
            if (userId != null) {
                request.setAttribute(ATTR_USER_ID, userId);
                chain.doFilter(request, response);
                return;
            }
        }

        String internal = request.getHeader("X-Internal-Token");
        if (internalKey != null && internal != null && !internal.isBlank()) {
            Long userId = parseInternalToken(internal);
            if (userId != null) {
                request.setAttribute(ATTR_USER_ID, userId);
                chain.doFilter(request, response);
                return;
            }
        }

        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "missing or invalid token");
    }

    private Long parseUserToken(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(userKey).build()
                    .parseSignedClaims(token).getPayload();
            // 同一把钥匙也签 refresh token，不挡掉刷新令牌就能当访问令牌用
            if (!"access".equals(claims.get("type", String.class))) {
                return null;
            }
            return Long.valueOf(claims.getSubject());
        } catch (Exception e) {
            return null;
        }
    }

    private Long parseInternalToken(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(internalKey)
                    .requireIssuer(INTERNAL_ISSUER).build()
                    .parseSignedClaims(token).getPayload();
            return Long.valueOf(claims.getSubject());
        } catch (Exception e) {
            return null;
        }
    }
}
