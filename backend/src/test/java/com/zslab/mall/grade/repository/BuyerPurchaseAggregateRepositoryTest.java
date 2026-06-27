package com.zslab.mall.grade.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.grade.entity.BuyerPurchaseAggregate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link BuyerPurchaseAggregateRepository} @DataJpaTest — 논리 PK 직접 할당·updatedAt 자동 설정 검증.
 */
class BuyerPurchaseAggregateRepositoryTest extends Batch1DataJpaTestBase {

    @Autowired
    private BuyerPurchaseAggregateRepository buyerPurchaseAggregateRepository;

    @Test
    @DisplayName("save+findById 성공: buyerId 논리 PK 직접 할당·lifetimePurchaseAmount=0·lastOrderedAt NULL·updatedAt 자동 설정")
    void save_findById_success() {
        long buyerId = 1001L;
        BuyerPurchaseAggregate saved = buyerPurchaseAggregateRepository.saveAndFlush(
            BuyerPurchaseAggregate.create(buyerId));
        entityManager.clear();

        Optional<BuyerPurchaseAggregate> found = buyerPurchaseAggregateRepository.findById(buyerId);

        assertThat(found).isPresent();
        assertThat(found.get().getBuyerId()).isEqualTo(buyerId);
        assertThat(found.get().getLifetimePurchaseAmount()).isEqualTo(0L);
        assertThat(found.get().getLastOrderedAt()).isNull();
        assertThat(found.get().getUpdatedAt()).isNotNull();
    }
}
