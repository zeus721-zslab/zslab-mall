package com.zslab.mall.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.auth.entity.Role;
import com.zslab.mall.auth.entity.UserRole;
import com.zslab.mall.auth.enums.RoleCode;
import jakarta.persistence.PersistenceException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link UserRoleRepository} @DataJpaTest — CRUD·UK·FK constraint 검증.
 */
class UserRoleRepositoryTest extends Batch1DataJpaTestBase {

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    private long seedUser(String publicId) {
        entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO `user` (public_id, created_at, updated_at) VALUES ('" + publicId + "', NOW(6), NOW(6))")
            .executeUpdate();
        return ((Number) entityManager.getEntityManager()
            .createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }

    @Test
    @DisplayName("save+findById 성공: userId·role 보존·createdAt 자동 설정")
    void save_findById_success() {
        long userId = seedUser("usr_01234567890123456789012345");
        Role role = roleRepository.findByCode(RoleCode.BUYER).orElseThrow();
        UserRole saved = userRoleRepository.saveAndFlush(UserRole.create(userId, role));
        entityManager.clear();

        Optional<UserRole> found = userRoleRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getUserId()).isEqualTo(userId);
        assertThat(found.get().getRole().getId()).isEqualTo(role.getId());
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("UK(user_id, role_id) 중복 삽입 → DataIntegrityViolationException")
    void insert_duplicateUserRole_throwsDataIntegrityViolation() {
        long userId = seedUser("usr_11234567890123456789012345");
        Role role = roleRepository.findByCode(RoleCode.SELLER_OWNER).orElseThrow();
        userRoleRepository.saveAndFlush(UserRole.create(userId, role));

        assertThatThrownBy(() ->
            userRoleRepository.saveAndFlush(UserRole.create(userId, role))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("user_id FK 위반 삽입 → PersistenceException (FK RESTRICT)")
    void insert_invalidUserId_throwsPersistenceException() {
        Role role = roleRepository.findByCode(RoleCode.BUYER).orElseThrow();

        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO user_role (user_id, role_id, created_at) "
                    + "VALUES (99999, " + role.getId() + ", NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("role_id FK 위반 삽입 → PersistenceException (FK RESTRICT)")
    void insert_invalidRoleId_throwsPersistenceException() {
        long userId = seedUser("usr_21234567890123456789012345");

        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO user_role (user_id, role_id, created_at) "
                    + "VALUES (" + userId + ", 99999, NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }
}
