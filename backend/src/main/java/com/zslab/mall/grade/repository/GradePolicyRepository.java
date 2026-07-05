package com.zslab.mall.grade.repository;

import com.zslab.mall.grade.entity.GradePolicy;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GradePolicyRepository extends JpaRepository<GradePolicy, Long> {

    /**
     * {@code now} 시점에 유효한 활성 등급 정책 전체를 version DESC로 조회한다(GRD-1·invariants §2.3).
     *
     * <p>필터: {@code effective_from <= now < effective_to}(반개구간)·{@code is_active = true}. 특정 등급 단일이
     * 아니라 활성 정책 <b>목록</b>을 반환하며, lifetime_amount로 구간을 선택하는 책임은 Service에 둔다(단일 등급 반환 금지).
     * effective_to는 무기한 상한 센티넬('9999-12-31 23:59:59.999999')로 심어 IS NULL 분기가 없다(V15 시드).
     */
    @Query("SELECT gp FROM GradePolicy gp "
            + "WHERE gp.isActive = true "
            + "AND gp.effectiveFrom <= :now "
            + "AND gp.effectiveTo > :now "
            + "ORDER BY gp.version DESC")
    List<GradePolicy> findActivePolicies(@Param("now") LocalDateTime now);
}
