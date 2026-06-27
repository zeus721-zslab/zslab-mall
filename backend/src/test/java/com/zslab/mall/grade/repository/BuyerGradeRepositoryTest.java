package com.zslab.mall.grade.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.grade.entity.BuyerGrade;
import com.zslab.mall.grade.enums.BuyerGradeCode;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link BuyerGradeRepository} @DataJpaTest — CRUD 검증.
 */
class BuyerGradeRepositoryTest extends Batch1DataJpaTestBase {

    @Autowired
    private BuyerGradeRepository buyerGradeRepository;

    @Test
    @DisplayName("save+findById 성공: id 할당·code·name 보존")
    void save_findById_success() {
        BuyerGrade saved = buyerGradeRepository.saveAndFlush(
                BuyerGrade.create(BuyerGradeCode.SILVER, "실버"));
        entityManager.clear();

        Optional<BuyerGrade> found = buyerGradeRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getCode()).isEqualTo(BuyerGradeCode.SILVER);
        assertThat(found.get().getName()).isEqualTo("실버");
    }
}
