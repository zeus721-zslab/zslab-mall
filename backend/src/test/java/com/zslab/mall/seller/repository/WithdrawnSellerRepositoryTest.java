package com.zslab.mall.seller.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.entity.WithdrawnSeller;
import jakarta.persistence.PersistenceException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link WithdrawnSellerRepository} @DataJpaTest — CRUD·FK constraint 검증.
 */
class WithdrawnSellerRepositoryTest extends Batch1DataJpaTestBase {

    @Autowired
    private WithdrawnSellerRepository withdrawnSellerRepository;

    private Seller seedSeller(String publicId) {
        entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO seller (public_id, company_name, ceo_name, status, created_at, updated_at) "
            + "VALUES ('" + publicId + "', '테스트셀러', '대표', 'ACTIVE', NOW(6), NOW(6))")
            .executeUpdate();
        long id = ((Number) entityManager.getEntityManager()
            .createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
        return entityManager.find(Seller.class, id);
    }

    @Test
    @DisplayName("save+findById 성공: originalSeller FK 보존·terminateReason·createdAt 확인")
    void save_findById_success() {
        Seller seller = seedSeller("slr_ws001234567890123456789012");
        WithdrawnSeller saved = withdrawnSellerRepository.saveAndFlush(
            WithdrawnSeller.create(seller, "판매자 자진 탈퇴", null));
        entityManager.clear();

        Optional<WithdrawnSeller> found = withdrawnSellerRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getOriginalSeller().getId()).isEqualTo(seller.getId());
        assertThat(found.get().getTerminateReason()).isEqualTo("판매자 자진 탈퇴");
        assertThat(found.get().getLegalRetentionUntil()).isNull();
        assertThat(found.get().getAnonymizedAt()).isNull();
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("original_seller_id FK 위반 삽입 → PersistenceException (FK RESTRICT)")
    void insert_invalidSellerId_throwsPersistenceException() {
        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO withdrawn_seller "
                    + "(original_seller_id, terminate_reason, created_at, updated_at) "
                    + "VALUES (99999, '탈퇴 사유', NOW(6), NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }
}
