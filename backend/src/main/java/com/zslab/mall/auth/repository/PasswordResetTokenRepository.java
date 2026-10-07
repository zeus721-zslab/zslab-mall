package com.zslab.mall.auth.repository;

import com.zslab.mall.auth.entity.PasswordResetToken;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    /** 확정 요청의 토큰 해시 조회(uk_password_reset_token_hash). */
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * 같은 회원의 미사용 토큰을 지운다 — 새 요청이 이전 링크를 무효화한다(D-269). 이미 사용된 행은 이력으로 남긴다.
     *
     * @return 삭제 행 수
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM PasswordResetToken t WHERE t.userId = :userId AND t.usedAt IS NULL")
    int deleteUnusedByUserId(@Param("userId") Long userId);

    /**
     * 미사용·미만료일 때만 사용 처리한다. 같은 토큰의 동시 확정은 행 락으로 직렬화되고, 뒤 요청은 앞 커밋의 used_at을 보고 0행이 된다
     * (READ COMMITTED · D-215) — 반환값 1인 요청만 비밀번호를 바꾼다.
     *
     * @return 갱신 행 수(1 = 이번 요청이 사용 · 0 = 이미 사용·만료·없음)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE PasswordResetToken t SET t.usedAt = :now WHERE t.id = :id AND t.usedAt IS NULL AND t.expiresAt > :now")
    int markUsed(@Param("id") Long id, @Param("now") LocalDateTime now);
}
