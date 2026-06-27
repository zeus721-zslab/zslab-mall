package com.zslab.mall.grade.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.grade.entity.BuyerGrade;
import com.zslab.mall.grade.entity.GradePolicy;
import com.zslab.mall.grade.enums.BuyerGradeCode;
import jakarta.persistence.PersistenceException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link GradePolicyRepository} @DataJpaTest — FK 위반·CHECK(min<=max) 위반 검증.
 */
class GradePolicyRepositoryTest extends Batch1DataJpaTestBase {

    @Autowired
    private BuyerGradeRepository buyerGradeRepository;

    @Autowired
    private GradePolicyRepository gradePolicyRepository;

    @Test
    @DisplayName("grade_id FK 위반 삽입 → PersistenceException (FK RESTRICT)")
    void insert_invalidGradeId_throwsPersistenceException() {
        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO grade_policy "
                    + "(grade_id, min_amount, max_amount, discount_rate, point_rate, "
                    + "effective_from, effective_to, version, is_active, created_at, updated_at) "
                    + "VALUES (99999, 0, 100000, 5, 1, NOW(6), NOW(6), 1, 1, NOW(6), NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("min_amount > max_amount 삽입 → DataIntegrityViolationException (CHECK constraint)")
    void insert_minGreaterThanMax_throwsDataIntegrityViolation() {
        BuyerGrade grade = buyerGradeRepository.saveAndFlush(
                BuyerGrade.create(BuyerGradeCode.GOLD, "골드"));

        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 12, 31, 23, 59);

        assertThatThrownBy(() ->
            gradePolicyRepository.saveAndFlush(
                GradePolicy.create(grade, 1_000_000L, 500_000L, 5, 1, from, to, 1))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }
}
