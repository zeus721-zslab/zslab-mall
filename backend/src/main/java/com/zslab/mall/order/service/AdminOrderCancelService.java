package com.zslab.mall.order.service;

import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimReasonCode;
import com.zslab.mall.claim.exception.ClaimInvalidStateException;
import com.zslab.mall.claim.service.ClaimService;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.order.controller.response.AdminOrderCancelResponse;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.enums.OrderStatus;
import com.zslab.mall.order.exception.OrderNotFoundException;
import com.zslab.mall.order.repository.OrderRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 주문 취소 Application Service(Track 79 D-168·A·C). 주문 상태로 경로를 분기한다.
 * <ul>
 *   <li><b>미결제(PENDING_PAYMENT)</b>: {@link OrderAutoCancelService#cancelOne} 재사용(조건부 UPDATE + 동기 예약 해제). Claim이
 *       없으므로 사유 코드·메모는 {@link AuditRecorder}에 ORDER 대상으로 기록한다(C α·Flyway 무변경). 조건부 전이가 0건(이미 종료·
 *       결제 완료 경합)이면 {@link OptimisticLockingFailureException}(기존 409 매핑)으로 응답한다.</li>
 *   <li><b>결제 후</b>: 항목별 {@link ClaimService#requestByAdmin}(Claim(CANCEL) 생성 + approve 1 TX). 다품목 선택 시 항목별 Claim이며
 *       1건이라도 실패(상태 불가·CLM-5)하면 전체 롤백된다. 사유는 Claim 컬럼에 저장되므로 별도 audit 기록은 두지 않는다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOrderCancelService {

    private static final Set<OrderStatus> TERMINAL_STATUSES =
            Set.of(OrderStatus.CANCELLED, OrderStatus.PAYMENT_EXPIRED);

    private final OrderRepository orderRepository;
    private final OrderAutoCancelService orderAutoCancelService;
    private final ClaimService claimService;
    private final AuditRecorder auditRecorder;

    /**
     * 관리자 주문 취소. 미결제 경로는 PAYMENT_EXPIRED·빈 claims, 결제 후 경로는 항목별 생성·승인된 Claim 목록을 응답으로 조립한다.
     *
     * @throws OrderNotFoundException             주문 미존재(404)
     * @throws OptimisticLockingFailureException  미결제 주문의 조건부 종료 전이가 0건(이미 종료·결제 완료 경합)일 때(409)
     * @throws ClaimInvalidStateException         결제 후 주문에서 취소 가능한 항목이 없거나 항목 상태·CLM-5 위반(422)
     */
    @Transactional
    public AdminOrderCancelResponse cancel(String orderPublicId, List<String> orderItemPublicIds, ClaimReasonCode reasonCode,
            String reasonDetail, AuditContext auditContext) {
        Order order = orderRepository.findByPublicIdWithItems(orderPublicId)
                .orElseThrow(() -> new OrderNotFoundException("주문을 찾을 수 없습니다: " + orderPublicId));

        if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
            cancelUnpaid(order, reasonCode, reasonDetail, auditContext);
            return new AdminOrderCancelResponse(orderPublicId, OrderStatus.PAYMENT_EXPIRED.name(), List.of());
        }
        if (TERMINAL_STATUSES.contains(order.getStatus())) {
            throw new OptimisticLockingFailureException("이미 종료된 주문입니다: " + orderPublicId + ", status=" + order.getStatus());
        }
        List<AdminOrderCancelResponse.CancelledItem> cancelled =
                cancelPaidItems(order, orderItemPublicIds, reasonCode, reasonDetail, auditContext.actorUserId());
        // 항목 전이는 동기 핸들러가 같은 TX에서 반영했고 Order.status는 Resolver로 재계산됐다(관리 엔티티 최신값).
        return new AdminOrderCancelResponse(orderPublicId, order.getStatus().name(), cancelled);
    }

    private void cancelUnpaid(Order order, ClaimReasonCode reasonCode, String reasonDetail, AuditContext auditContext) {
        boolean terminated = orderAutoCancelService.cancelOne(order.getId());
        if (!terminated) {
            // 조회~전이 사이 결제 완료·이미 종료(조건부 UPDATE 0건) — 관리자 요청은 무처리가 아니라 충돌로 알린다.
            throw new OptimisticLockingFailureException(
                    "미결제 취소 대상이 아닙니다(이미 종료·결제 완료): " + order.getPublicId());
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", OrderStatus.PAYMENT_EXPIRED.name());
        after.put("reasonCode", reasonCode.name());
        after.put("reasonDetail", reasonDetail);
        auditRecorder.record(auditContext, AuditLogAction.UPDATE, PolymorphicTargetType.ORDER, order.getId(),
                Map.of("status", OrderStatus.PENDING_PAYMENT.name()), after);
    }

    private List<AdminOrderCancelResponse.CancelledItem> cancelPaidItems(Order order, List<String> orderItemPublicIds,
            ClaimReasonCode reasonCode, String reasonDetail, Long adminUserId) {
        List<OrderItem> targets = resolveTargets(order, orderItemPublicIds);
        if (targets.isEmpty()) {
            throw new ClaimInvalidStateException("취소 가능한 주문 품목이 없습니다: " + order.getPublicId());
        }
        LocalDateTime now = LocalDateTime.now();
        List<AdminOrderCancelResponse.CancelledItem> cancelled = new ArrayList<>();
        for (OrderItem item : targets) {
            Claim claim = claimService.requestByAdmin(item.getId(), reasonCode, reasonDetail, adminUserId, now);
            cancelled.add(new AdminOrderCancelResponse.CancelledItem(claim.getPublicId(), item.getPublicId(), claim.getStatus()));
        }
        log.info("[AdminOrderCancel] 결제 후 취소 승인 완료 orderId={} items={} actor={}",
                order.getId(), cancelled.size(), adminUserId);
        return cancelled;
    }

    /**
     * 취소 대상 항목을 해소한다. 명시 목록이 있으면 주문 소속 검증 후 그대로(상태 검증은 Claim 코어가 422로 수행), 비어 있으면
     * CANCEL 요청 전이가 가능한 항목 전부.
     *
     * @throws ClaimInvalidStateException 명시한 항목이 주문에 속하지 않을 때
     */
    private List<OrderItem> resolveTargets(Order order, List<String> orderItemPublicIds) {
        if (orderItemPublicIds == null || orderItemPublicIds.isEmpty()) {
            return order.getItems().stream()
                    .filter(item -> item.getItemStatus().canTransitionTo(OrderItemStatus.CANCEL_REQUESTED))
                    .toList();
        }
        Map<String, OrderItem> byPublicId = new LinkedHashMap<>();
        for (OrderItem item : order.getItems()) {
            byPublicId.put(item.getPublicId(), item);
        }
        List<OrderItem> targets = new ArrayList<>();
        for (String publicId : orderItemPublicIds) {
            OrderItem item = byPublicId.get(publicId);
            if (item == null) {
                throw new ClaimInvalidStateException("주문에 속하지 않는 품목입니다: " + publicId);
            }
            targets.add(item);
        }
        return targets;
    }
}
