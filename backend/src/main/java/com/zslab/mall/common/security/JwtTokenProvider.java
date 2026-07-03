package com.zslab.mall.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;

/**
 * JWT 기반 {@link TokenProvider} 구현. HS256 서명·subject=actorId·claim {@code role}=역할명. (Track 33)
 *
 * <p>시크릿·만료는 생성자 주입({@code jwt.secret}·{@code jwt.expiration-ms}). 시크릿은 HS256 요건상 최소
 * 32바이트(256bit)여야 하며 미달 시 생성 시점에 {@link IllegalArgumentException}. 검증 실패(서명·만료·형식·클레임
 * 누락)는 {@link BadCredentialsException}으로 일원화한다.
 */
@Component
public class JwtTokenProvider implements TokenProvider {

    private static final String ROLE_CLAIM = "role";
    private static final int MIN_SECRET_BYTES = 32; // HS256 최소 256bit

    private final SecretKey secretKey;
    private final long expirationMs;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms}") long expirationMs) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "jwt.secret는 HS256 요건상 최소 " + MIN_SECRET_BYTES + "바이트(256bit) 이상이어야 합니다. 현재 "
                            + keyBytes.length + "바이트.");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "HmacSHA256");
        this.expirationMs = expirationMs;
    }

    @Override
    public String issue(Long actorId, ActorRole role) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(actorId))
                .claim(ROLE_CLAIM, role.name())
                .issuedAt(new Date(now))
                .expiration(new Date(now + expirationMs))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    @Override
    public TokenPayload verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Long actorId = Long.valueOf(claims.getSubject());
            String roleName = claims.get(ROLE_CLAIM, String.class);
            if (roleName == null) {
                throw new BadCredentialsException("role 클레임 누락");
            }
            return new TokenPayload(actorId, ActorRole.valueOf(roleName));
        } catch (JwtException | IllegalArgumentException ex) {
            throw new BadCredentialsException("유효하지 않은 인증 토큰", ex);
        }
    }
}
