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
    @DisplayName("save+findById 성공: id 할당·code·name 보존")
    void save_findById_success() {
        Role saved = roleRepository.saveAndFlush(Role.create(RoleCode.BUYER, "구매자"));
        entityManager.clear();

        Optional<Role> found = roleRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getCode()).isEqualTo(RoleCode.BUYER);
        assertThat(found.get().getName()).isEqualTo("구매자");
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
