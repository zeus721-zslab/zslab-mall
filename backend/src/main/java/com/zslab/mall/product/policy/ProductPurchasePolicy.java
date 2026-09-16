package com.zslab.mall.product.policy;

import com.zslab.mall.inventory.entity.Inventory;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductVariant;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.product.enums.ProductVariantStatus;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 구매 가능 판정 도메인 정책(Track 76·D-165·단일 소스). 카탈로그 목록/상세·장바구니 담기/조회·주문 생성·재결제 재검증이 전부
 * 본 클래스를 호출하며, 분산돼 있던 판정식(D-160 §1-A 당시 6지점)을 여기로 수렴한다.
 *
 * <p><b>판정식</b>: 구매가능 = 상품 판매중({@link #isOnSale}) ∧ 변형 SALE ∧ ¬상품 수동품절 ∧ ¬변형 수동품절 ∧ 가용재고 ≥ 요청수량.
 * 상품 판매중 = status=SALE ∧ 판매기간 내(시작 NULL=즉시·종료 NULL=무기한·종료 배타) ∧ ¬삭제. 삭제 상품은 {@code @SQLRestriction}으로
 * 조회 자체가 비어 null로 들어오므로 null도 판매중 아님으로 본다.
 *
 * <p>판매자 상태(ACTIVE)는 본 정책에 포함하지 않는다(D-160 §8 이월·목록 JPQL·장바구니 조회 enrich만 별도 반영). 목록 JPQL
 * ({@code ProductRepository.findDisplayable})의 판매기간 조건은 본 정책과 동일 식을 SQL로 옮긴 것이다.
 */
public final class ProductPurchasePolicy {

    private ProductPurchasePolicy() {
    }

    /** 상품 판매중 = status=SALE ∧ 판매기간 내 ∧ ¬삭제. null은 미존재·삭제로 간주한다. */
    public static boolean isOnSale(Product product, LocalDateTime now) {
        return product != null
                && product.getDeletedAt() == null
                && product.getStatus() == ProductStatus.SALE
                && product.isWithinSalePeriod(now);
    }

    /**
     * 품절 축 판정(판매 상태 제외). 품절 = 상품 수동품절 ∨ 변형 없음 ∨ 변형≠SALE ∨ 변형 수동품절 ∨ 재고 행 없음 ∨ 가용재고 &lt; 요청수량.
     * 카탈로그 soldOut 플래그처럼 "판매 상태와 무관하게 품절인가"만 묻는 호출처가 사용한다.
     */
    public static boolean isSoldOut(Product product, ProductVariant variant, Inventory inventory, int quantity) {
        if (product == null || variant == null || variant.getStatus() != ProductVariantStatus.SALE) {
            return true;
        }
        if (product.isSoldoutManual() || variant.isSoldoutManual()) {
            return true;
        }
        return inventory == null || inventory.getQuantityAvailable() < quantity;
    }

    /**
     * 판매 상태 판정(재고 제외). 주문 생성처럼 재고를 별도 배치로 검증하는 호출처가 사용한다.
     *
     * @return 차단 사유. 비어 있으면 판매 상태 통과(재고는 미확인)
     */
    public static Optional<PurchaseBlockReason> saleBlock(Product product, ProductVariant variant, LocalDateTime now) {
        if (!isOnSale(product, now) || variant == null || variant.getStatus() != ProductVariantStatus.SALE) {
            return Optional.of(PurchaseBlockReason.NOT_ON_SALE);
        }
        if (product.isSoldoutManual() || variant.isSoldoutManual()) {
            return Optional.of(PurchaseBlockReason.SOLD_OUT);
        }
        return Optional.empty();
    }

    /**
     * 구매 가능 판정(재고 포함). inventory null은 재고 행 없음 = 품절로 본다.
     *
     * @param quantity 요청 수량(구매가능 플래그 계산은 1)
     * @return 차단 사유. 비어 있으면 구매 가능
     */
    public static Optional<PurchaseBlockReason> evaluate(
            Product product, ProductVariant variant, Inventory inventory, int quantity, LocalDateTime now) {
        Optional<PurchaseBlockReason> saleBlock = saleBlock(product, variant, now);
        if (saleBlock.isPresent()) {
            return saleBlock;
        }
        if (isSoldOut(product, variant, inventory, quantity)) {
            return Optional.of(PurchaseBlockReason.SOLD_OUT);
        }
        return Optional.empty();
    }

    /** 수량 1 기준 구매 가능 여부(카탈로그·장바구니 플래그용). */
    public static boolean isPurchasable(Product product, ProductVariant variant, Inventory inventory, LocalDateTime now) {
        return evaluate(product, variant, inventory, 1, now).isEmpty();
    }
}
