package com.zslab.mall.grade.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.grade.entity.GradePolicy;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * V15 grade_policy 시드 안전망(R2-a) — 활성 정책 구간 [min_amount, max_amount) 상호배타 검증.
 *
 * <p>등급 간 구간 겹침 방지 DB 제약이 없으므로(GRD-3은 단일 행 min≤max뿐) 시드가 반개구간으로 연결되고 겹치지 않음을
 * 테스트로 고정한다. 반개구간이므로 인접 정책의 {@code max == 다음 min}(연결)은 허용하고 {@code max > 다음 min}(겹침)은 금지한다.
 */
class GradePolicySeedOverlapTest extends Batch1DataJpaTestBase {

    // 시드 effective_from(2026-01-01) 이후·센티넬(9999) 이전 임의 시각 — 활성 정책 3행 전부 매칭
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 5, 0, 0);

    @Autowired
    private GradePolicyRepository gradePolicyRepository;

    @Test
    @DisplayName("V15 시드 활성 grade_policy는 min_amount 정렬 시 인접 [min,max) 겹침이 없다(max <= 다음 min)")
    void activePolicies_haveNoOverlappingBrackets() {
        List<GradePolicy> active = gradePolicyRepository.findActivePolicies(NOW).stream()
                .sorted(Comparator.comparingLong(GradePolicy::getMinAmount))
                .toList();

        assertThat(active).hasSize(3);
        for (int index = 0; index < active.size() - 1; index++) {
            long currentMax = active.get(index).getMaxAmount();
            long nextMin = active.get(index + 1).getMinAmount();
            assertThat(currentMax)
                    .as("등급 구간 겹침: policy[%d].max=%d > policy[%d].min=%d", index, currentMax, index + 1, nextMin)
                    .isLessThanOrEqualTo(nextMin);
        }
    }
}
