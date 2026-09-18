package com.zslab.mall.settlement.repository;

import com.zslab.mall.settlement.entity.Settlement;
import com.zslab.mall.settlement.enums.SettlementStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import org.springframework.data.jpa.domain.Specification;

/**
 * 관리자 정산 목록 Specification(Track 85·AdminMemberSpecifications 패턴). 기간(정확 일치)·상태·셀러 id 집합 필터.
 */
public final class SettlementSpecifications {

    private SettlementSpecifications() {
    }

    public static Specification<Settlement> period(LocalDateTime periodStart, LocalDateTime periodEnd) {
        return (root, query, builder) -> builder.and(
                builder.equal(root.get("periodStart"), periodStart),
                builder.equal(root.get("periodEnd"), periodEnd));
    }

    /** status가 null이면 필터 없음. */
    public static Specification<Settlement> status(SettlementStatus status) {
        return (root, query, builder) -> status == null ? null : builder.equal(root.get("status"), status);
    }

    /** sellerIds가 null이면 필터 없음·빈 집합이면 항상 false(keyword 불일치 → 0건). */
    public static Specification<Settlement> sellerIdIn(Collection<Long> sellerIds) {
        return (root, query, builder) -> {
            if (sellerIds == null) {
                return null;
            }
            if (sellerIds.isEmpty()) {
                return builder.disjunction();
            }
            return root.get("sellerId").in(sellerIds);
        };
    }
}
