package com.zslab.mall.product.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.inventory.entity.Inventory;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductVariant;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.product.enums.ProductVariantStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link ProductPurchasePolicy} 단위 테스트(Track 76). 상태·판매기간 경계·상품/변형 수동품절·변형 상태·재고·삭제 조합을 실 엔티티로
 * 검증한다(mock 금지·Product.create + 상태 필드는 ReflectionTestUtils).
 */
class ProductPurchasePolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 16, 12, 0);

    @Test
    @DisplayName("SALE·기간 무제한·재고 있음 → 구매 가능")
    void purchasable_whenSaleAndInStock() {
        Product product = product(ProductStatus.SALE, null, null);
        ProductVariant variant = variant(ProductVariantStatus.SALE, false);
        assertThat(ProductPurchasePolicy.evaluate(product, variant, inventory(5), 1, NOW)).isEmpty();
        assertThat(ProductPurchasePolicy.isPurchasable(product, variant, inventory(5), NOW)).isTrue();
    }

    @Test
    @DisplayName("상품 상태 SALE 아님(PENDING·STOPPED·HIDDEN·REJECTED) → NOT_ON_SALE")
    void notOnSale_whenStatusIsNotSale() {
        for (ProductStatus status : new ProductStatus[] {
                ProductStatus.PENDING, ProductStatus.STOPPED, ProductStatus.HIDDEN, ProductStatus.REJECTED}) {
            Product product = product(status, null, null);
            assertThat(ProductPurchasePolicy.evaluate(product, variant(ProductVariantStatus.SALE, false), inventory(5), 1, NOW))
                    .as(status.name()).contains(PurchaseBlockReason.NOT_ON_SALE);
        }
    }

    @Test
    @DisplayName("판매기간 경계 — 시작=now 포함·종료=now 배타·시작 이전·종료 이후는 NOT_ON_SALE")
    void salePeriodBoundaries() {
        ProductVariant variant = variant(ProductVariantStatus.SALE, false);
        assertThat(ProductPurchasePolicy.isOnSale(product(ProductStatus.SALE, NOW, null), NOW)).as("시작=now 포함").isTrue();
        assertThat(ProductPurchasePolicy.isOnSale(product(ProductStatus.SALE, NOW.plusSeconds(1), null), NOW)).as("시작 전").isFalse();
        assertThat(ProductPurchasePolicy.isOnSale(product(ProductStatus.SALE, null, NOW), NOW)).as("종료=now 배타").isFalse();
        assertThat(ProductPurchasePolicy.isOnSale(product(ProductStatus.SALE, null, NOW.plusSeconds(1)), NOW)).as("종료 전").isTrue();
        assertThat(ProductPurchasePolicy.isOnSale(product(ProductStatus.SALE, NOW.minusDays(1), NOW.plusDays(1)), NOW)).isTrue();
        assertThat(ProductPurchasePolicy.evaluate(product(ProductStatus.SALE, null, NOW.minusDays(1)), variant, inventory(5), 1, NOW))
                .contains(PurchaseBlockReason.NOT_ON_SALE);
    }

    @Test
    @DisplayName("상품 수동품절 → SOLD_OUT(판매중이라도)·isSoldOut true")
    void soldOut_whenProductManuallySoldOut() {
        Product product = product(ProductStatus.SALE, null, null);
        product.changeSoldoutManual(true);
        ProductVariant variant = variant(ProductVariantStatus.SALE, false);
        assertThat(ProductPurchasePolicy.evaluate(product, variant, inventory(5), 1, NOW)).contains(PurchaseBlockReason.SOLD_OUT);
        assertThat(ProductPurchasePolicy.isSoldOut(product, variant, inventory(5), 1)).isTrue();
    }

    @Test
    @DisplayName("변형 수동품절 → SOLD_OUT / 변형 비-SALE(HIDDEN·STOPPED) → NOT_ON_SALE / 변형 null → NOT_ON_SALE")
    void variantAxis() {
        Product product = product(ProductStatus.SALE, null, null);
        assertThat(ProductPurchasePolicy.evaluate(product, variant(ProductVariantStatus.SALE, true), inventory(5), 1, NOW))
                .contains(PurchaseBlockReason.SOLD_OUT);
        assertThat(ProductPurchasePolicy.evaluate(product, variant(ProductVariantStatus.HIDDEN, false), inventory(5), 1, NOW))
                .contains(PurchaseBlockReason.NOT_ON_SALE);
        assertThat(ProductPurchasePolicy.evaluate(product, variant(ProductVariantStatus.STOPPED, false), inventory(5), 1, NOW))
                .contains(PurchaseBlockReason.NOT_ON_SALE);
        assertThat(ProductPurchasePolicy.evaluate(product, null, inventory(5), 1, NOW)).contains(PurchaseBlockReason.NOT_ON_SALE);
    }

    @Test
    @DisplayName("재고 — 요청수량 초과·재고 행 없음 → SOLD_OUT, 정확히 요청수량이면 통과")
    void stockAxis() {
        Product product = product(ProductStatus.SALE, null, null);
        ProductVariant variant = variant(ProductVariantStatus.SALE, false);
        assertThat(ProductPurchasePolicy.evaluate(product, variant, inventory(1), 2, NOW)).contains(PurchaseBlockReason.SOLD_OUT);
        assertThat(ProductPurchasePolicy.evaluate(product, variant, null, 1, NOW)).contains(PurchaseBlockReason.SOLD_OUT);
        assertThat(ProductPurchasePolicy.evaluate(product, variant, inventory(2), 2, NOW)).isEmpty();
        assertThat(ProductPurchasePolicy.saleBlock(product, variant, NOW)).as("재고 제외 판정은 통과").isEmpty();
    }

    @Test
    @DisplayName("삭제 상품(deleted_at 존재·null) → NOT_ON_SALE")
    void notOnSale_whenDeleted() {
        Product deleted = product(ProductStatus.SALE, null, null);
        deleted.markDeleted();
        ProductVariant variant = variant(ProductVariantStatus.SALE, false);
        assertThat(ProductPurchasePolicy.evaluate(deleted, variant, inventory(5), 1, NOW)).contains(PurchaseBlockReason.NOT_ON_SALE);
        assertThat(ProductPurchasePolicy.evaluate(null, variant, inventory(5), 1, NOW)).contains(PurchaseBlockReason.NOT_ON_SALE);
    }

    @Test
    @DisplayName("우선순위 — 판매 상태 차단이 품절보다 먼저 보고된다(STOPPED ∧ 수동품절 → NOT_ON_SALE)")
    void saleBlockTakesPrecedenceOverSoldOut() {
        Product product = product(ProductStatus.STOPPED, null, null);
        product.changeSoldoutManual(true);
        Optional<PurchaseBlockReason> reason =
                ProductPurchasePolicy.evaluate(product, variant(ProductVariantStatus.SALE, true), null, 1, NOW);
        assertThat(reason).contains(PurchaseBlockReason.NOT_ON_SALE);
    }

    // ---------- fixtures ----------

    private static Product product(ProductStatus status, LocalDateTime saleStartAt, LocalDateTime saleEndAt) {
        Product product = Product.create(1L, 1L, "정책상품", null, 10_000L, null);
        ReflectionTestUtils.setField(product, "status", status);
        product.applySaleTerms(null, saleStartAt, saleEndAt);
        return product;
    }

    private static ProductVariant variant(ProductVariantStatus status, boolean soldoutManual) {
        ProductVariant variant = ProductVariant.create(1L, "V1", null, null, 0L, 0, 1L, null, null);
        ReflectionTestUtils.setField(variant, "id", 10L);
        variant.updateMeta("V1", null, null, 0L, status, soldoutManual, 0);
        return variant;
    }

    private static Inventory inventory(int available) {
        return Inventory.create(10L, available);
    }
}
