package com.zslab.mall.inbox.collector;

import com.zslab.mall.inbox.enums.InboxItemType;
import com.zslab.mall.inventory.entity.Inventory;
import com.zslab.mall.inventory.policy.LowStockThreshold;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductVariant;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * 셀러 · 재고 임박 — 자기 상품 옵션의 가용 재고가 [{@link LowStockThreshold#MIN}, {@link LowStockThreshold#MAX}](수동 품절 제외·대시보드와
 * 같은 조건). 기한이 없어 항상 오늘 탭이다: 정렬값을 가용 재고로 두고 탭 경계를 MAX + 1로 잡아 예정 탭에는 아무것도 들어가지 않는다.
 */
@Component
public class LowStockInboxSource extends CriteriaInboxSource<Inventory, Integer> {

    private static final int NEVER_UPCOMING = LowStockThreshold.MAX + 1;

    public LowStockInboxSource(EntityManager entityManager) {
        super(entityManager, Inventory.class, InboxItemType.LOW_STOCK);
    }

    @Override
    protected InboxSelection<Integer> select(Root<Inventory> root, CriteriaQuery<?> query, CriteriaBuilder builder,
            InboxViewer viewer, LocalDateTime now) {
        Root<ProductVariant> variant = query.from(ProductVariant.class);
        Root<Product> product = query.from(Product.class);
        return new InboxSelection<>(
                builder.and(builder.equal(variant.get("id"), root.get("variantId")),
                        builder.equal(product.get("id"), variant.get("productId")),
                        builder.equal(product.get("sellerId"), viewer.sellerId()),
                        builder.isFalse(variant.get("soldoutManual")),
                        builder.isFalse(product.get("soldoutManual")),
                        builder.between(root.get("quantityAvailable"), LowStockThreshold.MIN, LowStockThreshold.MAX)),
                root.get("quantityAvailable"), variant.get("publicId"), product.get("name"), variant.get("variantCode"));
    }

    @Override
    protected Integer tabCutoff(InboxWindow window) {
        return NEVER_UPCOMING;
    }

    @Override
    protected LocalDateTime baseAtOf(Integer base) {
        return null;
    }

    @Override
    protected LocalDateTime dueAtOf(Integer base) {
        return null;
    }
}
