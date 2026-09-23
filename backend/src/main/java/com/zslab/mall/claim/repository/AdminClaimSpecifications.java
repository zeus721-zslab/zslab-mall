package com.zslab.mall.claim.repository;

import com.zslab.mall.claim.controller.request.AdminClaimActionFilter;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimInspectionResult;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.refund.entity.Refund;
import com.zslab.mall.refund.enums.RefundStatus;
import com.zslab.mall.refund.repository.RefundedCondition;
import com.zslab.mall.user.entity.User;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
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

    /** 특정 구매자의 클레임(Track 84·claim → order_item → order.buyer_id 경로·requested_by 미사용). null이면 조건 없음. */
    public static Specification<Claim> buyerId(Long buyerId) {
        return (root, query, builder) -> {
            if (buyerId == null) {
                return null;
            }
            Subquery<Long> items = query.subquery(Long.class);
            Root<OrderItem> item = items.from(OrderItem.class);
            items.select(item.get("id"))
                    .where(builder.equal(item.get("order").get("buyerId"), buyerId));
            return root.get("orderItemId").in(items);
        };
    }

    /**
     * 최신 환불 상태(Track 89-A). 목록 행의 refundStatus(클레임별 id 최대 Refund 행의 status)와 같은 기준으로, 그 최신 환불이 주어진
     * status인 클레임만 남긴다. 환불이 없는 클레임은 어느 값에도 걸리지 않는다. null이면 조건 없음.
     */
    public static Specification<Claim> refundStatus(RefundStatus refundStatus) {
        return (root, query, builder) -> {
            if (refundStatus == null) {
                return null;
            }
            Subquery<Long> latestRefundId = query.subquery(Long.class);
            Root<Refund> anyRefund = latestRefundId.from(Refund.class);
            latestRefundId.select(builder.max(anyRefund.get("id")))
                    .where(builder.equal(anyRefund.get("claimId"), root.get("id")));

            Subquery<Long> matching = query.subquery(Long.class);
            Root<Refund> refund = matching.from(Refund.class);
            matching.select(refund.get("claimId"))
                    .where(builder.equal(refund.get("id"), latestRefundId),
                            builder.equal(refund.get("status"), refundStatus));
            return root.get("id").in(matching);
        };
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

    /**
     * 필요 액션(Track 96-4 D-205). {@code AdminClaimQueryService.availableActions}의 후속 처리 5종을 같은 조건으로 재표현한다 —
     * 규칙이 바뀌면 양쪽을 함께 고쳐야 하며 {@code AdminClaimActionFilterIntegrationTest} 매트릭스가 동치를 강제한다.
     * 방향별 최신 Delivery는 Java와 같이 id 최대 행(MAX(id) 서브쿼리·D4)이다. 환불은 클레임의 환불 행 전체에서 {@link RefundedCondition} 행 유무와
     * 품목 기환불액을 본다(Track 104-3a). FOLLOWUP은 5종 OR. null이면 조건 없음.
     */
    public static Specification<Claim> action(AdminClaimActionFilter action) {
        return (root, query, builder) -> {
            if (action == null) {
                return null;
            }
            return switch (action) {
                case FOLLOWUP -> builder.or(
                        confirmPickup(root, query, builder),
                        inspect(root, builder),
                        registerExchangeShipment(root, query, builder),
                        markExchangeDelivered(root, query, builder),
                        initiateRefund(root, query, builder));
                case CONFIRM_PICKUP -> confirmPickup(root, query, builder);
                case INSPECT -> inspect(root, builder);
                case REGISTER_EXCHANGE_SHIPMENT -> registerExchangeShipment(root, query, builder);
                case MARK_EXCHANGE_DELIVERED -> markExchangeDelivered(root, query, builder);
                case INITIATE_REFUND -> initiateRefund(root, query, builder);
            };
        };
    }

    /** RETURN·EXCHANGE APPROVED + 미회수 + 회수(RETURN) Delivery 있음 ↔ availableActions:250-252. */
    private static Predicate confirmPickup(Root<Claim> root, CriteriaQuery<?> query, CriteriaBuilder builder) {
        return builder.and(
                approvedPickupBased(root, builder),
                builder.isNull(root.get("pickedUpAt")),
                builder.isNotNull(latestDeliveryId(root, query, builder, DeliveryDirection.RETURN)));
    }

    /** RETURN·EXCHANGE APPROVED + 회수 후 + 미검수 ↔ availableActions:254-255. */
    private static Predicate inspect(Root<Claim> root, CriteriaBuilder builder) {
        return builder.and(
                approvedPickupBased(root, builder),
                builder.isNotNull(root.get("pickedUpAt")),
                builder.isNull(root.get("inspectionResult")));
    }

    /** EXCHANGE APPROVED + 회수 후 + 검수 PASS + 교환품(OUTBOUND) Delivery 없음 ↔ availableActions:257-259. */
    private static Predicate registerExchangeShipment(Root<Claim> root, CriteriaQuery<?> query, CriteriaBuilder builder) {
        return builder.and(
                approvedExchangePassed(root, builder),
                builder.isNull(latestDeliveryId(root, query, builder, DeliveryDirection.OUTBOUND)));
    }

    /** EXCHANGE APPROVED + 회수 후 + 검수 PASS + 최신 OUTBOUND Delivery가 SHIPPING ↔ availableActions:261-262. */
    private static Predicate markExchangeDelivered(Root<Claim> root, CriteriaQuery<?> query, CriteriaBuilder builder) {
        Subquery<Long> shippingClaims = query.subquery(Long.class);
        Root<Delivery> delivery = shippingClaims.from(Delivery.class);
        shippingClaims.select(delivery.get("claimId"))
                .where(builder.equal(delivery.get("id"), latestDeliveryId(root, query, builder, DeliveryDirection.OUTBOUND)),
                        builder.equal(delivery.get("status"), DeliveryStatus.SHIPPING));
        return builder.and(approvedExchangePassed(root, builder), root.get("id").in(shippingClaims));
    }

    /**
     * APPROVED + (CANCEL 또는 RETURN 회수 후 검수 PASS) + "환불된 금액" 환불 행 없음 + 품목 잔여 상한 있음 ↔ refundInitiatable
     * (Track 104-3a·환불 행 조건은 {@link RefundedCondition#predicate} 단일 소스). RETURN의 회수(picked_up_at) 조건은 availableActions의
     * early return과 같은 배타 조건이다.
     */
    private static Predicate initiateRefund(Root<Claim> root, CriteriaQuery<?> query, CriteriaBuilder builder) {
        Predicate refundDue = builder.or(
                builder.equal(root.get("type"), ClaimType.CANCEL),
                builder.and(
                        builder.equal(root.get("type"), ClaimType.RETURN),
                        builder.isNotNull(root.get("pickedUpAt")),
                        builder.equal(root.get("inspectionResult"), ClaimInspectionResult.PASS)));
        Subquery<Long> refundedRefunds = query.subquery(Long.class);
        Root<Refund> refund = refundedRefunds.from(Refund.class);
        refundedRefunds.select(refund.get("id"))
                .where(builder.equal(refund.get("claimId"), root.get("id")), RefundedCondition.predicate(refund, builder));
        Predicate noRefundedRefund = builder.not(builder.exists(refundedRefunds));
        return builder.and(builder.equal(root.get("status"), ClaimStatus.APPROVED), refundDue, noRefundedRefund,
                itemRemainingRefundable(root, query, builder));
    }

    /** 품목 기환불액({@link RefundedCondition} 행 합·클레임 무관 품목 전체) &lt; 품목 금액 ↔ RefundService.initiate 품목 상한. */
    private static Predicate itemRemainingRefundable(Root<Claim> root, CriteriaQuery<?> query, CriteriaBuilder builder) {
        Subquery<Long> itemRefunded = query.subquery(Long.class);
        Root<Refund> refund = itemRefunded.from(Refund.class);
        Root<Claim> itemClaim = itemRefunded.from(Claim.class);
        itemRefunded.select(builder.coalesce(builder.sum(refund.<Long>get("amount")), 0L))
                .where(builder.equal(refund.get("claimId"), itemClaim.get("id")),
                        builder.equal(itemClaim.get("orderItemId"), root.get("orderItemId")),
                        RefundedCondition.predicate(refund, builder));
        Subquery<Long> itemTotalPrice = query.subquery(Long.class);
        Root<OrderItem> item = itemTotalPrice.from(OrderItem.class);
        itemTotalPrice.select(item.<Long>get("totalPrice"))
                .where(builder.equal(item.get("id"), root.get("orderItemId")));
        return builder.lessThan(itemRefunded, itemTotalPrice);
    }

    private static Predicate approvedPickupBased(Root<Claim> root, CriteriaBuilder builder) {
        return builder.and(
                builder.equal(root.get("status"), ClaimStatus.APPROVED),
                root.get("type").in(ClaimType.RETURN, ClaimType.EXCHANGE));
    }

    private static Predicate approvedExchangePassed(Root<Claim> root, CriteriaBuilder builder) {
        return builder.and(
                builder.equal(root.get("status"), ClaimStatus.APPROVED),
                builder.equal(root.get("type"), ClaimType.EXCHANGE),
                builder.isNotNull(root.get("pickedUpAt")),
                builder.equal(root.get("inspectionResult"), ClaimInspectionResult.PASS));
    }

    /** 클레임에 연결된 방향별 최신 Delivery id(MAX(id)·없으면 NULL) — AdminClaimQueryService.latestByDirection과 같은 기준. */
    private static Subquery<Long> latestDeliveryId(Root<Claim> root, CriteriaQuery<?> query, CriteriaBuilder builder,
            DeliveryDirection direction) {
        Subquery<Long> latest = query.subquery(Long.class);
        Root<Delivery> delivery = latest.from(Delivery.class);
        latest.select(builder.max(delivery.get("id")))
                .where(builder.equal(delivery.get("claimId"), root.get("id")),
                        builder.equal(delivery.get("direction"), direction));
        return latest;
    }
}
