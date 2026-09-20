package com.zslab.mall.claim.repository;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.order.entity.OrderItem;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

/**
 * 셀러 클레임 목록 필터(Track 90-D-1). 셀러 클레임 조회 Specification에는 seller ownership predicate({@link #ownedBySeller})가
 * 반드시 AND로 결합되어야 한다 — 조합 순서는 보안 보장이 아니며, 다른 조건이 OR로 붙으면 범위 제한이 무력화된다(외부 검토 r2a 반영).
 * 유형·상태·기간은 {@link AdminClaimSpecifications}를 그대로 쓴다. 검색만 셀러용으로 따로 둔다 — 관리자 검색은 구매자 이름·이메일 축을 포함하므로
 * 셀러에게 재사용하면 구매자 정보로 필터링하는 경로가 된다(JPQL 파라미터 바인딩·SQL injection 위험 없음).
 */
public final class SellerClaimSpecifications {

    private SellerClaimSpecifications() {
    }

    /** 자기 품목의 클레임만(claim.order_item_id → order_item.seller_id·{@code SellerDeliverySpecifications.ownedBySeller} 동형). */
    public static Specification<Claim> ownedBySeller(Long sellerId) {
        return (root, query, builder) -> {
            Subquery<Long> items = query.subquery(Long.class);
            Root<OrderItem> item = items.from(OrderItem.class);
            items.select(item.get("id")).where(builder.equal(item.get("sellerId"), sellerId));
            return root.get("orderItemId").in(items);
        };
    }

    /** 클레임 public_id 등치(상세 조회·소유 조건과 and 결합해 타 셀러는 미존재와 같은 404). */
    public static Specification<Claim> publicId(String publicId) {
        return (root, query, builder) -> builder.equal(root.get("publicId"), publicId);
    }

    /** 검색: 주문번호 정확일치 OR 품목 상품명 스냅샷 부분일치(셀러 품목 목록 keyword와 같은 축·구매자 축 없음). null이면 조건 없음. */
    public static Specification<Claim> keyword(String likePattern, String rawKeyword) {
        return (root, query, builder) -> {
            if (likePattern == null) {
                return null;
            }
            Subquery<Long> items = query.subquery(Long.class);
            Root<OrderItem> item = items.from(OrderItem.class);
            items.select(item.get("id"))
                    .where(builder.or(
                            builder.like(item.get("productName"), likePattern, '\\'),
                            builder.equal(item.get("order").get("orderNo"), rawKeyword)));
            return root.get("orderItemId").in(items);
        };
    }
}
