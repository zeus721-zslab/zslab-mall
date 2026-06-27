package com.zslab.mall.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.auth.entity.Permission;
import com.zslab.mall.auth.entity.Role;
import com.zslab.mall.auth.entity.RolePermission;
import com.zslab.mall.auth.enums.RoleCode;
import jakarta.persistence.PersistenceException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link RolePermissionRepository} @DataJpaTest — CRUD·UK·FK constraint 검증.
 */
class RolePermissionRepositoryTest extends Batch1DataJpaTestBase {

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @Test
    @DisplayName("save+findById 성공: role·permission 보존·createdAt 자동 설정")
    void save_findById_success() {
        Role role = roleRepository.saveAndFlush(Role.create(RoleCode.BUYER, "구매자"));
        Permission permission = permissionRepository.saveAndFlush(Permission.create("PRODUCT_READ", "상품 조회"));
        RolePermission saved = rolePermissionRepository.saveAndFlush(RolePermission.create(role, permission));
        entityManager.clear();

        Optional<RolePermission> found = rolePermissionRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getRole().getId()).isEqualTo(role.getId());
        assertThat(found.get().getPermission().getId()).isEqualTo(permission.getId());
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("UK(role_id, permission_id) 중복 삽입 → DataIntegrityViolationException")
    void insert_duplicateRolePermission_throwsDataIntegrityViolation() {
        Role role = roleRepository.saveAndFlush(Role.create(RoleCode.SELLER_OWNER, "판매자"));
        Permission permission = permissionRepository.saveAndFlush(Permission.create("ORDER_READ", "주문 조회"));
        rolePermissionRepository.saveAndFlush(RolePermission.create(role, permission));

        assertThatThrownBy(() ->
            rolePermissionRepository.saveAndFlush(RolePermission.create(role, permission))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("role_id FK 위반 삽입 → PersistenceException (FK RESTRICT)")
    void insert_invalidRoleId_throwsPersistenceException() {
        Permission permission = permissionRepository.saveAndFlush(Permission.create("SELLER_READ", "판매자 조회"));

        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO role_permission (role_id, permission_id, created_at) "
                    + "VALUES (99999, " + permission.getId() + ", NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("permission_id FK 위반 삽입 → PersistenceException (FK RESTRICT)")
    void insert_invalidPermissionId_throwsPersistenceException() {
        Role role = roleRepository.saveAndFlush(Role.create(RoleCode.ADMIN_OPERATOR, "관리자"));

        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO role_permission (role_id, permission_id, created_at) "
                    + "VALUES (" + role.getId() + ", 99999, NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }
}
