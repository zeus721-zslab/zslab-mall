package com.zslab.mall.delivery.repository;

import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.order.entity.OrderItem;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

/**
 * 셀러 배송 목록의 범위 조건(Track 90-B-1). 필터 축(scope·status·carrier·기간·keyword)은 {@link AdminDeliverySpecifications}를 그대로
 * 재사용하고(데이터 정의라 복제하면 두 벌이 어긋난다), 셀러 소유 조건만 여기 둔다. Delivery는 셀러 컬럼이 없고 OrderItem을 id로만 참조하므로
 * {@code order_item.seller_id} 서브쿼리로 건다(JPQL 파라미터 바인딩·SQL injection 위험 없음).
 */
public final class SellerDeliverySpecifications {

    private SellerDeliverySpecifications() {
    }

    /** 자기 품목의 배송만(Delivery 1행 = 품목 1건 = 셀러 1명이라 행 내용이 셀러 범위를 넘지 않는다). */
    public static Specification<Delivery> ownedBySeller(Long sellerId) {
        return (root, query, builder) -> {
            Subquery<Long> items = query.subquery(Long.class);
            Root<OrderItem> item = items.from(OrderItem.class);
            items.select(item.get("id")).where(builder.equal(item.get("sellerId"), sellerId));
            return root.get("orderItemId").in(items);
        };
    }
}
