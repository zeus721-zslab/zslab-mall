package com.zslab.mall.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.auth.entity.Permission;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link PermissionRepository} @DataJpaTest — CRUD 검증.
 */
class PermissionRepositoryTest extends Batch1DataJpaTestBase {

    @Autowired
    private PermissionRepository permissionRepository;

    @Test
    @DisplayName("save+findById 성공: id 할당·code·name 보존")
    void save_findById_success() {
        Permission saved = permissionRepository.saveAndFlush(
                Permission.create("PRODUCT_READ", "상품 조회"));
        entityManager.clear();

        Optional<Permission> found = permissionRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getCode()).isEqualTo("PRODUCT_READ");
        assertThat(found.get().getName()).isEqualTo("상품 조회");
    }
}
