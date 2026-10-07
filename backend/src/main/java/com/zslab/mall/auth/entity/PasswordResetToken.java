package com.zslab.mall.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 구매자 비밀번호 재설정 토큰(D-269·V48). 원문은 메일 링크에만 있고 여기에는 SHA-256 해시만 둔다. 상태 컬럼 없이
 * {@code usedAt == null && expiresAt > now}가 유효다. 사용 처리는 엔티티 변경이 아니라 {@code PasswordResetTokenRepository.markUsed}
 * 조건부 UPDATE로 한다(동시 확정 1회 보장). user_id는 User Aggregate 외부 참조라 Long 필드만 둔다(D-01).
 */
@Entity
@Table(name = "password_reset_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(onlyExplicitlyIncluded = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    @ToString.Include
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    @ToString.Include
    private Long userId;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * @throws IllegalArgumentException 필수값 누락 또는 만료 시각이 발급 시각 이후가 아닌 경우
     */
    public static PasswordResetToken issue(Long userId, String tokenHash, LocalDateTime now, LocalDateTime expiresAt) {
        if (userId == null || tokenHash == null || now == null || expiresAt == null || !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("PasswordResetToken 필수값 누락 또는 만료 시각 오류.");
        }
        PasswordResetToken token = new PasswordResetToken();
        token.userId = userId;
        token.tokenHash = tokenHash;
        token.createdAt = now;
        token.expiresAt = expiresAt;
        return token;
    }
}
