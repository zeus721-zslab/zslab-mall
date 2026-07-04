package com.zslab.mall.seller.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.auth.entity.Role;
import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.auth.repository.RoleRepository;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.entity.SellerUser;
import jakarta.persistence.PersistenceException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link SellerUserRepository} @DataJpaTest — CRUD·UK·FK constraint 검증.
 */
class SellerUserRepositoryTest extends Batch1DataJpaTestBase {

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private SellerUserRepository sellerUserRepository;

    private Seller seedSeller(String publicId) {
        entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO seller (public_id, company_name, ceo_name, status, created_at, updated_at) "
            + "VALUES ('" + publicId + "', '테스트셀러', '대표', 'ACTIVE', NOW(6), NOW(6))")
            .executeUpdate();
        long id = ((Number) entityManager.getEntityManager()
            .createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
        return entityManager.find(Seller.class, id);
    }

    private long seedUser(String publicId) {
        entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO `user` (public_id, created_at, updated_at) VALUES ('" + publicId + "', NOW(6), NOW(6))")
            .executeUpdate();
        return ((Number) entityManager.getEntityManager()
            .createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }

    @Test
    @DisplayName("save+findById 성공: seller·userId·roleId 보존·createdAt 자동 설정")
    void save_findById_success() {
        Seller seller = seedSeller("slr_01234567890123456789012345");
        long userId = seedUser("usr_01234567890123456789012345");
        Role role = roleRepository.findByCode(RoleCode.SELLER_OWNER).orElseThrow();
        SellerUser saved = sellerUserRepository.saveAndFlush(
            SellerUser.create(seller, userId, role.getId()));
        entityManager.clear();

        Optional<SellerUser> found = sellerUserRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getSeller().getId()).isEqualTo(seller.getId());
        assertThat(found.get().getUserId()).isEqualTo(userId);
        assertThat(found.get().getRoleId()).isEqualTo(role.getId());
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("user_id 단독 UNIQUE(V12): 동일 user_id를 다른 seller에 매핑 → DataIntegrityViolationException(1 user=1 seller)")
    void insert_sameUserDifferentSeller_throwsDataIntegrityViolation() {
        Seller sellerA = seedSeller("slr_11234567890123456789012345");
        Seller sellerB = seedSeller("slr_31234567890123456789012345");
        long userId = seedUser("usr_11234567890123456789012345");
        Role role = roleRepository.findByCode(RoleCode.SELLER_OWNER).orElseThrow();
        sellerUserRepository.saveAndFlush(SellerUser.create(sellerA, userId, role.getId()));

        // V12: uk_seller_user_user_id(user_id 단독)이므로 동일 user_id는 seller가 달라도 두 번째 매핑이 불가하다.
        assertThatThrownBy(() ->
            sellerUserRepository.saveAndFlush(SellerUser.create(sellerB, userId, role.getId()))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("seller_id FK 위반 삽입 → PersistenceException (FK RESTRICT)")
    void insert_invalidSellerId_throwsPersistenceException() {
        long userId = seedUser("usr_21234567890123456789012345");
        Role role = roleRepository.findByCode(RoleCode.BUYER).orElseThrow();

        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO seller_user (seller_id, user_id, role_id, created_at, updated_at) "
                    + "VALUES (99999, " + userId + ", " + role.getId() + ", NOW(6), NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("user_id FK 위반 삽입 → PersistenceException (FK RESTRICT)")
    void insert_invalidUserId_throwsPersistenceException() {
        Seller seller = seedSeller("slr_21234567890123456789012345");
        Role role = roleRepository.findByCode(RoleCode.ADMIN_OPERATOR).orElseThrow();

        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO seller_user (seller_id, user_id, role_id, created_at, updated_at) "
                    + "VALUES (" + seller.getId() + ", 99999, " + role.getId() + ", NOW(6), NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }
}
