package com.zslab.mall.claim.repository;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.user.entity.User;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDateTime;
import org.springframework.data.jpa.domain.Specification;

/**
 * 관리자 클레임 목록 필터(Track 80 D-169·{@code AdminOrderSpecifications} 패턴). 각 조건은 null이면 조건 없음. 검색어는 주문번호
 * 정확일치·구매자 이름/이메일 부분일치·품목 상품명 스냅샷 부분일치 OR이며, Claim은 OrderItem을 ID로만 참조하므로 타 Aggregate
 * (OrderItem·Order·User)는 서브쿼리로 참조한다(JPQL 파라미터 바인딩·SQL injection 위험 없음).
 */
public final class AdminClaimSpecifications {

    private AdminClaimSpecifications() {
    }

    public static Specification<Claim> type(ClaimType type) {
        return (root, query, builder) -> type == null ? null : builder.equal(root.get("type"), type);
    }

    public static Specification<Claim> status(ClaimStatus status) {
        return (root, query, builder) -> status == null ? null : builder.equal(root.get("status"), status);
    }

    /** 요청일시(requested_at) 범위. from·to 각각 null 허용(포함 경계). */
    public static Specification<Claim> requestedBetween(LocalDateTime from, LocalDateTime to) {
        return (root, query, builder) -> {
            if (from == null && to == null) {
                return null;
            }
            if (from == null) {
                return builder.lessThanOrEqualTo(root.get("requestedAt"), to);
            }
            if (to == null) {
                return builder.greaterThanOrEqualTo(root.get("requestedAt"), from);
            }
            return builder.between(root.get("requestedAt"), from, to);
        };
    }

    /** 검색: 주문번호 정확일치 OR 구매자 이름/이메일 부분일치 OR 품목 상품명 스냅샷 부분일치 → 해당 품목의 클레임. */
    public static Specification<Claim> keyword(String likePattern, String rawKeyword) {
        return (root, query, builder) -> {
            if (likePattern == null) {
                return null;
            }
            Subquery<Long> buyers = query.subquery(Long.class);
            Root<User> user = buyers.from(User.class);
            buyers.select(user.get("id"))
                    .where(builder.or(
                            builder.like(user.get("name"), likePattern, '\\'),
                            builder.like(user.get("email"), likePattern, '\\')));

            Subquery<Long> items = query.subquery(Long.class);
            Root<OrderItem> item = items.from(OrderItem.class);
            items.select(item.get("id"))
                    .where(builder.or(
                            builder.like(item.get("productName"), likePattern, '\\'),
                            builder.equal(item.get("order").get("orderNo"), rawKeyword),
                            item.get("order").get("buyerId").in(buyers)));

            return root.get("orderItemId").in(items);
        };
    }
}
