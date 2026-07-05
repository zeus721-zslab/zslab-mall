package com.zslab.mall.settlement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.settlement.entity.Settlement;
import com.zslab.mall.settlement.enums.SettlementStatus;
import jakarta.persistence.PersistenceException;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link SettlementRepository} @DataJpaTest — CRUD·FK·ENUM constraint 검증.
 */
class SettlementRepositoryTest extends Batch1DataJpaTestBase {

    @Autowired
    private SettlementRepository settlementRepository;

    private long seedSeller(String publicId) {
        entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO seller (public_id, company_name, ceo_name, status, created_at, updated_at) "
            + "VALUES ('" + publicId + "', '정산셀러', '대표', 'ACTIVE', NOW(6), NOW(6))")
            .executeUpdate();
        return ((Number) entityManager.getEntityManager()
            .createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }

    private long seedSellerBankAccount(long sellerId) {
        entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO seller_bank_account "
            + "(seller_id, bank_code, account_number, account_holder, is_primary, status, created_at, updated_at) "
            + "VALUES (" + sellerId + ", '004', '9876543210', '대표', 1, 'VERIFIED', NOW(6), NOW(6))")
            .executeUpdate();
        return ((Number) entityManager.getEntityManager()
            .createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }

    @Test
    @DisplayName("save+findById 성공: sellerId·bankAccountId·netAmount 계산·status PENDING 확인")
    void save_findById_success() {
        long sellerId = seedSeller("slr_01234567890123456789012345");
        long bankAccountId = seedSellerBankAccount(sellerId);
        LocalDateTime start = LocalDateTime.of(2026, 6, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 6, 30, 23, 59);
        Settlement saved = settlementRepository.saveAndFlush(
            Settlement.create(sellerId, bankAccountId, start, end, 1_000_000L, 30_000L, 1000, 20_000L));
        entityManager.clear();

        Optional<Settlement> found = settlementRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getSellerId()).isEqualTo(sellerId);
        assertThat(found.get().getBankAccountId()).isEqualTo(bankAccountId);
        assertThat(found.get().getGrossAmount()).isEqualTo(1_000_000L);
        assertThat(found.get().getFeeAmount()).isEqualTo(30_000L);
        assertThat(found.get().getRefundAmount()).isEqualTo(20_000L);
        assertThat(found.get().getNetAmount()).isEqualTo(950_000L);
        assertThat(found.get().getCommissionRate()).isEqualTo(1000);
        assertThat(found.get().getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(found.get().getPaidAt()).isNull();
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("seller_id FK 위반 삽입 → PersistenceException (FK RESTRICT)")
    void insert_invalidSellerId_throwsPersistenceException() {
        long sellerId = seedSeller("slr_11234567890123456789012345");
        long bankAccountId = seedSellerBankAccount(sellerId);
        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO settlement "
                    + "(seller_id, bank_account_id, period_start, period_end, gross_amount, fee_amount, "
                    + "commission_rate, refund_amount, net_amount, status, created_at, updated_at) "
                    + "VALUES (99999, " + bankAccountId + ", NOW(6), NOW(6), 0, 0, 1000, 0, 0, 'PENDING', NOW(6), NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("bank_account_id FK 위반 삽입 → PersistenceException (FK RESTRICT)")
    void insert_invalidBankAccountId_throwsPersistenceException() {
        long sellerId = seedSeller("slr_21234567890123456789012345");
        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO settlement "
                    + "(seller_id, bank_account_id, period_start, period_end, gross_amount, fee_amount, "
                    + "commission_rate, refund_amount, net_amount, status, created_at, updated_at) "
                    + "VALUES (" + sellerId + ", 99999, NOW(6), NOW(6), 0, 0, 1000, 0, 0, 'PENDING', NOW(6), NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("status ENUM 외 값 삽입 → PersistenceException (ENUM constraint)")
    void insert_invalidStatus_throwsPersistenceException() {
        long sellerId = seedSeller("slr_31234567890123456789012345");
        long bankAccountId = seedSellerBankAccount(sellerId);
        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO settlement "
                    + "(seller_id, bank_account_id, period_start, period_end, gross_amount, fee_amount, "
                    + "commission_rate, refund_amount, net_amount, status, created_at, updated_at) "
                    + "VALUES (" + sellerId + ", " + bankAccountId + ", NOW(6), NOW(6), 0, 0, 1000, 0, 0, 'INVALID_STATUS', NOW(6), NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }
}
