package com.chat.user.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * 用户服务调用群组服务时的短期令牌签发，密钥与 chat-gateway / chat-group 一致。
 * 替代原来写死的 X-Internal-Key：那个字符串在仓库里公开，拿到就能冒充任意用户。
 */
@Component
public class InternalTokenIssuer {

    private final SecretKey key;

    public InternalTokenIssuer(@Value("${internal.jwt.secret:}") String internalSecret) {
        this.key = internalSecret.isBlank() ? null
                : Keys.hmacShaKeyFor(internalSecret.getBytes(StandardCharsets.UTF_8));
    }

    public String forUser(Long userId) {
        if (key == null) {
            throw new IllegalStateException("internal.jwt.secret is not configured");
        }
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuer(JwtAuthenticationFilter.INTERNAL_ISSUER)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofSeconds(30))))
                .signWith(key)
                .compact();
    }
}
