package com.zslab.mall.seller.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.seller.entity.SellerSalesDaily;
import com.zslab.mall.seller.entity.SellerSalesDailyId;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.PersistenceException;

/**
 * {@link SellerSalesDailyRepository} @DataJpaTest — @IdClass 복합 PK constraint 검증.
 */
class SellerSalesDailyRepositoryTest extends Batch1DataJpaTestBase {

    @Autowired
    private SellerSalesDailyRepository sellerSalesDailyRepository;

    @Test
    @DisplayName("save+findById(@IdClass) 성공: 복합 PK(sellerId·saleDate) 보존·updatedAt 자동 설정")
    void save_findById_success() {
        long sellerId = 2001L;
        LocalDate saleDate = LocalDate.of(2026, 6, 28);
        SellerSalesDaily saved = sellerSalesDailyRepository.saveAndFlush(
            SellerSalesDaily.create(sellerId, saleDate, 10, 500_000L, 50_000L, 450_000L));
        entityManager.clear();

        Optional<SellerSalesDaily> found = sellerSalesDailyRepository.findById(
            new SellerSalesDailyId(sellerId, saleDate));

        assertThat(found).isPresent();
        assertThat(found.get().getSellerId()).isEqualTo(sellerId);
        assertThat(found.get().getSaleDate()).isEqualTo(saleDate);
        assertThat(found.get().getOrderCount()).isEqualTo(10);
        assertThat(found.get().getGrossAmount()).isEqualTo(500_000L);
        assertThat(found.get().getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("동일 (seller_id, sale_date) nativeQuery 중복 삽입 → PersistenceException (복합 PK 중복)")
    void insert_duplicateCompositeKey_throwsPersistenceException() {
        long sellerId = 3001L;
        LocalDate saleDate = LocalDate.of(2026, 6, 1);
        sellerSalesDailyRepository.saveAndFlush(
            SellerSalesDaily.create(sellerId, saleDate, 5, 100_000L, 0L, 100_000L));

        // @IdClass 복합 PK 중복은 JPA save()가 merge()로 UPDATE 처리 — nativeQuery로 DB 레벨 중복 검증
        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO seller_sales_daily "
                    + "(seller_id, sale_date, order_count, gross_amount, refund_amount, net_amount, updated_at) "
                    + "VALUES (" + sellerId + ", '2026-06-01', 3, 60000, 0, 60000, NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }
}
