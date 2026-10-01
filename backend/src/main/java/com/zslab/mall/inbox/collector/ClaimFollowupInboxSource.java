package com.zslab.mall.inbox.collector;

import com.zslab.mall.claim.controller.request.AdminClaimActionFilter;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.repository.AdminClaimSpecifications;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.inbox.enums.InboxItemType;
import com.zslab.mall.order.entity.OrderItem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 관리자 · 클레임 후속(환불 실패 포함). 대기 조건은 목록 "필요 액션" 필터와 같은 Specification({@link AdminClaimSpecifications#action})을
 * 단계별로 재사용한다 — 5단계는 서로 배타라 합집합이 FOLLOWUP과 같다. 기준 시각은 그 단계에 들어온 시각이다(D-248).
 * <ul>
 *   <li>회수 확인: 최신 회수(RETURN) 송장 등록 시각</li>
 *   <li>검수: pickedUpAt · 교환품 발송: inspectedAt</li>
 *   <li>교환 배송완료: 최신 교환 발송(OUTBOUND) shippedAt</li>
 *   <li>환불 개시: CANCEL processedAt / RETURN inspectedAt(CANCEL은 inspectedAt이 비어 있어 COALESCE 한 식으로 표현)</li>
 * </ul>
 */
@Component
public class ClaimFollowupInboxSource implements InboxSource {

    private static final Comparator<InboxRow> BASE_ORDER = Comparator
            .comparing(InboxRow::baseAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(InboxRow::ref);

    private final List<StepSource> steps;

    public ClaimFollowupInboxSource(EntityManager entityManager) {
        this.steps = List.of(
                new StepSource(entityManager, AdminClaimActionFilter.CONFIRM_PICKUP, "PICKUP"),
                new StepSource(entityManager, AdminClaimActionFilter.INSPECT, "INSPECT"),
                new StepSource(entityManager, AdminClaimActionFilter.REGISTER_EXCHANGE_SHIPMENT, "EXCH_SHIP"),
                new StepSource(entityManager, AdminClaimActionFilter.MARK_EXCHANGE_DELIVERED, "EXCH_DLVD"),
                new StepSource(entityManager, AdminClaimActionFilter.INITIATE_REFUND, "REFUND"));
    }

    @Override
    public InboxItemType type() {
        return InboxItemType.CLAIM_FOLLOWUP;
    }

    @Override
    public InboxSlice collect(InboxViewer viewer, InboxWindow window) {
        List<InboxRow> merged = new ArrayList<>();
        long total = 0;
        for (StepSource step : steps) {
            InboxSlice slice = step.collect(viewer, window);
            merged.addAll(slice.rows());
            total += slice.total();
        }
        List<InboxRow> rows = merged.stream().sorted(BASE_ORDER).limit(window.limit()).toList();
        return new InboxSlice(rows, total);
    }

    @Override
    public long count(InboxViewer viewer, InboxWindow window) {
        long total = 0;
        for (StepSource step : steps) {
            total += step.count(viewer, window);
        }
        return total;
    }

    @Override
    public boolean isPending(InboxViewer viewer, String ref) {
        return steps.stream().anyMatch(step -> step.isPending(viewer, ref));
    }

    /**
     * 후속 단계 1개. 빈으로 등록하지 않고 위 합성 수집기 안에서만 쓴다. ref는 "클레임 publicId:단계 코드"다(D-248) — 보류 키에 단계가 들어가
     * 다음 단계로 넘어가면 이전 보류가 적용되지 않는다. 단계 코드는 item_ref VARCHAR(40) − publicId 30자 − 구분자 1자 = 9자 이내.
     */
    private static final class StepSource extends TimedInboxSource<Claim> {

        private final AdminClaimActionFilter step;
        private final String refSuffix;

        private StepSource(EntityManager entityManager, AdminClaimActionFilter step, String stepCode) {
            super(entityManager, Claim.class, InboxItemType.CLAIM_FOLLOWUP);
            this.step = step;
            this.refSuffix = ":" + stepCode;
        }

        @Override
        protected InboxSelection<LocalDateTime> select(Root<Claim> root, CriteriaQuery<?> query, CriteriaBuilder builder,
                InboxViewer viewer, LocalDateTime now) {
            Root<OrderItem> item = query.from(OrderItem.class);
            List<Predicate> pending = new ArrayList<>();
            pending.add(builder.equal(item.get("id"), root.get("orderItemId")));
            pending.add(AdminClaimSpecifications.action(step).toPredicate(root, query, builder));
            Expression<LocalDateTime> base = switch (step) {
                case CONFIRM_PICKUP -> latestDelivery(root, query, builder, DeliveryDirection.RETURN, pending).get("createdAt");
                case INSPECT -> root.get("pickedUpAt");
                case REGISTER_EXCHANGE_SHIPMENT -> root.get("inspectedAt");
                case MARK_EXCHANGE_DELIVERED ->
                        latestDelivery(root, query, builder, DeliveryDirection.OUTBOUND, pending).get("shippedAt");
                case INITIATE_REFUND -> builder.coalesce(root.<LocalDateTime>get("inspectedAt"), root.get("processedAt"));
                case FOLLOWUP -> throw new IllegalStateException("FOLLOWUP은 단계가 아니라 합집합이다");
            };
            return new InboxSelection<>(builder.and(pending.toArray(Predicate[]::new)), base,
                    builder.concat(root.<String>get("publicId"), refSuffix), item.get("productName"),
                    item.get("order").get("orderNo"));
        }

        /**
         * 클레임의 방향별 최신 배송(id 최대 — AdminClaimSpecifications와 같은 기준)을 루트로 붙인다. 두 단계 모두 대기 조건이 그 배송의
         * 존재를 요구하므로 내부 조인으로 행이 빠지지 않는다.
         */
        private static Root<Delivery> latestDelivery(Root<Claim> root, CriteriaQuery<?> query, CriteriaBuilder builder,
                DeliveryDirection direction, List<Predicate> pending) {
            Root<Delivery> delivery = query.from(Delivery.class);
            Subquery<Long> latestId = query.subquery(Long.class);
            Root<Delivery> candidate = latestId.from(Delivery.class);
            latestId.select(builder.max(candidate.get("id")))
                    .where(builder.equal(candidate.get("claimId"), root.get("id")),
                            builder.equal(candidate.get("direction"), direction));
            pending.add(builder.equal(delivery.get("id"), latestId));
            return delivery;
        }
    }
}
