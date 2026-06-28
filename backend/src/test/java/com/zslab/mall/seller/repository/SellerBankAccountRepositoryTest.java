package com.zslab.mall.seller.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.entity.SellerBankAccount;
import com.zslab.mall.seller.enums.SellerBankAccountStatus;
import jakarta.persistence.PersistenceException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link SellerBankAccountRepository} @DataJpaTest — CRUD·FK·ENUM constraint 검증.
 */
class SellerBankAccountRepositoryTest extends Batch1DataJpaTestBase {

    @Autowired
    private SellerBankAccountRepository sellerBankAccountRepository;

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
    @DisplayName("save+findById 성공: seller FK 보존·bankCode·accountNumber·status PENDING 확인")
    void save_findById_success() {
        Seller seller = seedSeller("slr_sba01234567890123456789012");
        SellerBankAccount saved = sellerBankAccountRepository.saveAndFlush(
            SellerBankAccount.create(seller, "004", "1234567890", "홍길동", true));
        entityManager.clear();

        Optional<SellerBankAccount> found = sellerBankAccountRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getSeller().getId()).isEqualTo(seller.getId());
        assertThat(found.get().getBankCode()).isEqualTo("004");
        assertThat(found.get().getAccountNumber()).isEqualTo("1234567890");
        assertThat(found.get().getAccountHolder()).isEqualTo("홍길동");
        assertThat(found.get().isPrimary()).isTrue();
        assertThat(found.get().getStatus()).isEqualTo(SellerBankAccountStatus.PENDING);
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("seller_id FK 위반 삽입 → PersistenceException (FK RESTRICT)")
    void insert_invalidSellerId_throwsPersistenceException() {
        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO seller_bank_account "
                    + "(seller_id, bank_code, account_number, account_holder, is_primary, status, created_at, updated_at) "
                    + "VALUES (99999, '004', '0000000000', '홍길동', 1, 'PENDING', NOW(6), NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("status ENUM 외 값 삽입 → PersistenceException (ENUM constraint)")
    void insert_invalidStatus_throwsPersistenceException() {
        Seller seller = seedSeller("slr_sba11234567890123456789012");
        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO seller_bank_account "
                    + "(seller_id, bank_code, account_number, account_holder, is_primary, status, created_at, updated_at) "
                    + "VALUES (" + seller.getId() + ", '004', '0000000001', '홍길동', 0, 'INVALID_STATUS', NOW(6), NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }
}
