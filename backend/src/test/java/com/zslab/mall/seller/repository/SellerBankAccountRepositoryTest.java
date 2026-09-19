package com.zslab.mall.seller.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.common.crypto.AesGcmTextEncryptor;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.entity.SellerBankAccount;
import com.zslab.mall.seller.enums.SellerBankAccountStatus;
import jakarta.persistence.PersistenceException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link SellerBankAccountRepository} @DataJpaTest — CRUD·FK·ENUM constraint 검증 + Track 89-F Converter 투명성(엔티티 평문·컬럼 v1: 암호문)·
 * V32 주 계좌 UNIQUE·strict 복호(평문 컬럼 로드 예외).
 */
class SellerBankAccountRepositoryTest extends Batch1DataJpaTestBase {

    @Autowired
    private SellerBankAccountRepository sellerBankAccountRepository;
    @Autowired
    private AesGcmTextEncryptor bankAccountEncryptor;

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
    @DisplayName("Converter 투명성: 엔티티 필드는 평문, DB 컬럼은 v1: 암호문(평문 미포함)·복호 시 원문 / 주 계좌 없이 저장한 행은 primary_seller_id NULL")
    void save_columnIsEncrypted_entityIsPlain() {
        Seller seller = seedSeller("slr_sba21234567890123456789012");
        SellerBankAccount saved = sellerBankAccountRepository.saveAndFlush(
            SellerBankAccount.create(seller, "004", "5555-6666-7777", "홍길동", false));
        entityManager.clear();

        String column = (String) entityManager.getEntityManager()
            .createNativeQuery("SELECT account_number FROM seller_bank_account WHERE id = :id")
            .setParameter("id", saved.getId()).getSingleResult();
        assertThat(AesGcmTextEncryptor.isEncrypted(column)).isTrue();
        assertThat(column).doesNotContain("5555-6666-7777");
        assertThat(bankAccountEncryptor.decrypt(column)).isEqualTo("5555-6666-7777");
        assertThat(sellerBankAccountRepository.findById(saved.getId()).orElseThrow().getAccountNumber()).isEqualTo("5555-6666-7777");
        assertThat(entityManager.getEntityManager()
            .createNativeQuery("SELECT primary_seller_id FROM seller_bank_account WHERE id = :id")
            .setParameter("id", saved.getId()).getSingleResult()).isNull();
    }

    @Test
    @DisplayName("V32 UNIQUE: 같은 셀러 주 계좌 2건 saveAndFlush → DataIntegrityViolationException(uk_seller_bank_account_primary) / 다른 셀러는 허용")
    void twoPrimaryAccountsForSameSeller_rejected() {
        Seller seller = seedSeller("slr_sba31234567890123456789012");
        Seller other = seedSeller("slr_sba41234567890123456789012");
        sellerBankAccountRepository.saveAndFlush(SellerBankAccount.create(seller, "004", "1111-2222", "홍길동", true));
        sellerBankAccountRepository.saveAndFlush(SellerBankAccount.create(other, "004", "1111-3333", "김철수", true));

        assertThatThrownBy(() -> sellerBankAccountRepository.saveAndFlush(
                SellerBankAccount.create(seller, "088", "1111-4444", "홍길동", true)))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("uk_seller_bank_account_primary");
    }

    @Test
    @DisplayName("strict 복호: raw SQL로 들어온 평문 행을 엔티티로 로드하면 예외(v1: 접두사 없음) — 조용히 평문 통과시키지 않는다")
    void plaintextRow_loadFails() {
        Seller seller = seedSeller("slr_sba51234567890123456789012");
        entityManager.getEntityManager().createNativeQuery(
                "INSERT INTO seller_bank_account "
                + "(seller_id, bank_code, account_number, account_holder, is_primary, status, created_at, updated_at) "
                + "VALUES (:sellerId, '004', '9999-0000', '홍길동', 0, 'VERIFIED', NOW(6), NOW(6))")
            .setParameter("sellerId", seller.getId()).executeUpdate();
        long id = ((Number) entityManager.getEntityManager()
            .createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
        entityManager.clear();

        assertThatThrownBy(() -> sellerBankAccountRepository.findById(id))
            .hasMessageContaining("AttributeConverter")
            .rootCause().isInstanceOf(IllegalStateException.class).hasMessageContaining("v1:");
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
