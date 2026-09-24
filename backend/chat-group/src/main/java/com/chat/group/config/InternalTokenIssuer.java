package com.chat.group.config;

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
 * 群服务调用用户服务的内部接口时，用它签一个 30 秒的令牌表明"这次调用是以某人身份发起的服务调用"。
 * 密钥与 chat-gateway / chat-user 的 internal.jwt.secret 相同，替代原来那个写死的 X-Internal-Key。
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
                .issuer(AuthFilter.INTERNAL_ISSUER)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofSeconds(30))))
                .signWith(key)
                .compact();
    }
}
