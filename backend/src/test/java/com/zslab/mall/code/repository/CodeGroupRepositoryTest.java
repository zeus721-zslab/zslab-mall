package com.zslab.mall.code.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.code.entity.CodeGroup;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link CodeGroupRepository} @DataJpaTest — CRUD 검증.
 */
class CodeGroupRepositoryTest extends Batch1DataJpaTestBase {

    @Autowired
    private CodeGroupRepository codeGroupRepository;

    @Test
    @DisplayName("save+findById 성공: id 할당·code·name 보존")
    void save_findById_success() {
        CodeGroup saved = codeGroupRepository.saveAndFlush(
                CodeGroup.create("ORDER_STATUS", "주문상태", "주문 상태 코드 그룹"));
        entityManager.clear();

        Optional<CodeGroup> found = codeGroupRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getCode()).isEqualTo("ORDER_STATUS");
        assertThat(found.get().getName()).isEqualTo("주문상태");
        assertThat(found.get().getDescription()).isEqualTo("주문 상태 코드 그룹");
    }
}
