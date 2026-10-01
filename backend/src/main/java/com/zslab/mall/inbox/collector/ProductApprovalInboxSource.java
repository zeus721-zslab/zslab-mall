package com.zslab.mall.inbox.collector;

import com.zslab.mall.inbox.enums.InboxItemType;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.seller.entity.Seller;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * 관리자 · 상품 승인(PENDING) — 기준 updatedAt(PENDING 진입 시각 필드가 없어 근사·D-248). 부제 셀러명은 스칼라 서브쿼리라
 * 셀러가 삭제돼도 항목은 빠지지 않고 부제만 비는다.
 */
@Component
public class ProductApprovalInboxSource extends TimedInboxSource<Product> {

    public ProductApprovalInboxSource(EntityManager entityManager) {
        super(entityManager, Product.class, InboxItemType.PRODUCT_APPROVAL);
    }

    @Override
    protected InboxSelection<LocalDateTime> select(Root<Product> root, CriteriaQuery<?> query, CriteriaBuilder builder,
            InboxViewer viewer, LocalDateTime now) {
        Subquery<String> sellerName = query.subquery(String.class);
        Root<Seller> seller = sellerName.from(Seller.class);
        sellerName.select(seller.get("companyName")).where(builder.equal(seller.get("id"), root.get("sellerId")));
        return new InboxSelection<>(builder.equal(root.get("status"), ProductStatus.PENDING), root.get("updatedAt"),
                root.get("publicId"), root.get("name"), sellerName);
    }
}
