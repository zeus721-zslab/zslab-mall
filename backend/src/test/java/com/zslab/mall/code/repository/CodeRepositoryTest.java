package com.zslab.mall.code.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.code.entity.Code;
import com.zslab.mall.code.entity.CodeGroup;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link CodeRepository} @DataJpaTest — UK(group_id,code) 중복·FK 위반 검증.
 */
class CodeRepositoryTest extends Batch1DataJpaTestBase {

    @Autowired
    private CodeGroupRepository codeGroupRepository;

    @Autowired
    private CodeRepository codeRepository;

    @Test
    @DisplayName("UK(group_id,code) 중복 삽입 → DataIntegrityViolationException")
    void insert_duplicateGroupCode_throwsDataIntegrityViolation() {
        CodeGroup group = codeGroupRepository.saveAndFlush(
                CodeGroup.create("PAYMENT_METHOD", "결제수단", null));

        codeRepository.saveAndFlush(Code.create(group, "CARD", "신용카드", 1));

        assertThatThrownBy(() ->
            codeRepository.saveAndFlush(Code.create(group, "CARD", "체크카드", 2))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("group_id FK 위반 삽입 → PersistenceException (FK RESTRICT)")
    void insert_invalidGroupId_throwsPersistenceException() {
        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO code (group_id, code, label, display_order, is_active, is_system, created_at, updated_at) "
                    + "VALUES (99999, 'FK_INVALID', 'label', 1, 1, 0, NOW(6), NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }
}
