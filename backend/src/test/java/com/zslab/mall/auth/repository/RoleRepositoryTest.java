package com.zslab.mall.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.auth.entity.Role;
import com.zslab.mall.auth.enums.RoleCode;
import jakarta.persistence.PersistenceException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link RoleRepository} @DataJpaTest — CRUD·DB ENUM constraint 검증.
 */
class RoleRepositoryTest extends Batch1DataJpaTestBase {

    @Autowired
    private RoleRepository roleRepository;

    @Test
    @DisplayName("findByCode 성공: seed된 Role의 code·name·id(non-null) 보존")
    void findByCode_seededRole_success() {
        // V11 seed된 BUYER Role을 조회하는 계약 검증(seed 기반 설계·직접 save 아님)
        Optional<Role> found = roleRepository.findByCode(RoleCode.BUYER);

        assertThat(found).isPresent();
        assertThat(found.get().getCode()).isEqualTo(RoleCode.BUYER);
        assertThat(found.get().getName()).isEqualTo("구매자");
        assertThat(found.get().getId()).isNotNull();
    }

    @Test
    @DisplayName("code ENUM 외 값 삽입 시도 → PersistenceException (DB ENUM constraint)")
    void insert_invalidEnumCode_throwsPersistenceException() {
        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO role (code, name, created_at, updated_at) "
                    + "VALUES ('INVALID_ROLE', 'Invalid', NOW(6), NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }
}
