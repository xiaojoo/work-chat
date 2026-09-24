package com.chat.user.config;

import com.chat.user.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 服务间调用的短期令牌，由 chat-gateway 签发 */
    public static final String INTERNAL_HEADER = "X-Internal-Token";
    public static final String INTERNAL_ISSUER = "chat-gateway";
    public static final String INTERNAL_AUTHORITY = "ROLE_INTERNAL";

    private final JwtUtil jwtUtil;
    private final SecretKey internalKey;

    public JwtAuthenticationFilter(JwtUtil jwtUtil,
                                   @Value("${internal.jwt.secret:}") String internalSecret) {
        this.jwtUtil = jwtUtil;
        this.internalKey = internalSecret.isBlank() ? null
                : Keys.hmacShaKeyFor(internalSecret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtUtil.parseToken(header.substring(7));
                // 同一把钥匙也签 refresh token，不挡掉的话刷新令牌就能当访问令牌用
                if (!"access".equals(claims.get("type", String.class))) {
                    throw new IllegalArgumentException("not an access token");
                }
                authenticate(request, response, filterChain, Long.valueOf(claims.getSubject()),
                        Collections.emptyList());
                return;
            } catch (Exception e) {
                SecurityContextHolder.clearContext();
            }
        }

        if (internalKey != null) {
            String internal = request.getHeader(INTERNAL_HEADER);
            if (internal != null && !internal.isBlank()) {
                try {
                    Claims claims = Jwts.parser()
                            .verifyWith(internalKey)
                            .requireIssuer(INTERNAL_ISSUER)
                            .build()
                            .parseSignedClaims(internal)
                            .getPayload();
                    authenticate(request, response, filterChain, Long.valueOf(claims.getSubject()),
                            List.of(new SimpleGrantedAuthority(INTERNAL_AUTHORITY)));
                    return;
                } catch (Exception e) {
                    SecurityContextHolder.clearContext();
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, HttpServletResponse response,
                              FilterChain filterChain, Long userId,
                              List<GrantedAuthority> authorities)
            throws ServletException, IOException {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);
        filterChain.doFilter(request, response);
    }
}
