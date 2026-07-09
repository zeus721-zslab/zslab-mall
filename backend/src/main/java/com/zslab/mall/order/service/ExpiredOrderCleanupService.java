package com.zslab.mall.order.service;

import com.zslab.mall.common.observability.ExpiredOrderCleanupMetrics;
import com.zslab.mall.common.observability.TracedEventPublisher;
import com.zslab.mall.inventory.entity.Inventory;
import com.zslab.mall.inventory.repository.InventoryRepository;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderStatus;
import com.zslab.mall.order.event.OrderTerminated;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.order.repository.OrderShippingSnapshotRepository;
import com.zslab.mall.payment.enums.PaymentStatus;
import com.zslab.mall.payment.repository.PaymentRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 미결제 종료(PAYMENT_EXPIRED) 주문 hard delete Application Service(FE-12c-2). 유예(GRACE_DAYS) 경과·재고 해제 완료 주문 1건을
 * 자식(payment·shipping_snapshot·order_item)→부모(order) 순으로 물리삭제한다. 되돌릴 수 없으므로 삭제 가능 조건을 만족한 주문만
 * 삭제하고, 미충족 시 skip 또는 이연한다(안전성 우선·삭제 &lt; 안전).
 *
 * <p><b>단건 트랜잭션 경계</b>: {@link #cleanupOne}은 주문 1건당 독립 {@code @Transactional}이다. 배치 오케스트레이션
 * ({@code ExpiredOrderCleanupScheduler})이 id별로 호출하며, 한 건 실패가 다른 건 커밋을 롤백하지 않는다(부분 실패 격리).
 * 자식→부모 삭제는 동일 트랜잭션 내에서 수행돼 원자적이다(부분 삭제 방지·FK RESTRICT 위반 시 전체 롤백).
 *
 * <p><b>삭제 가능 조건(모두 충족 시에만 삭제)</b>:
 * <ol>
 *   <li>(0) 조회~삭제 사이 status가 PAYMENT_EXPIRED 유지(재확인). 아니면 skip.</li>
 *   <li>(1) PENDING payment 부재. 존재 시 skip(결제 진행 가능성·삭제 위험).</li>
 *   <li>(2) 모든 order_item의 variant 재고 reserved == 0(해제 완료). reserved &gt; 0이면 {@link OrderTerminated} 재발행 후 이연.</li>
 * </ol>
 *
 * <p><b>재고 미해제 재시도(reserved &gt; 0)</b>: 새 해제 로직을 만들지 않고 기존 {@link OrderTerminated} 이벤트를 활성 트랜잭션에서
 * 재발행한다. {@code OrderAutoCancelService.cancelOne} 재호출은 status가 PAYMENT_EXPIRED라 멱등 skip(이벤트 미발행)이므로,
 * 발행 형태(publicId·id·occurredAt)를 동일하게 직접 publish한다. 커밋 후 {@code InventoryOrderTerminatedHandler}(AFTER_COMMIT)가
 * release를 재시도하며(reserved==0 멱등 skip), 이번 회차 삭제는 이연해 다음 배치에서 재확인한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpiredOrderCleanupService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderShippingSnapshotRepository shippingSnapshotRepository;
    private final PaymentRepository paymentRepository;
    private final InventoryRepository inventoryRepository;
    private final TracedEventPublisher eventPublisher;
    private final ExpiredOrderCleanupMetrics cleanupMetrics;

    /**
     * PAYMENT_EXPIRED 주문 1건을 삭제 가능 조건 충족 시 hard delete한다. 미충족 시 skip 또는 이연한다.
     *
     * @param orderId 삭제 대상 주문 id
     */
    @Transactional
    public void cleanupOne(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            // 배치 조회~트랜잭션 사이 행이 사라지는 경우는 정상 흐름상 없으나 방어적으로 skip한다.
            log.info("[ExpiredCleanup] cleanupOne skip: 주문 행 없음 orderId={}", orderId);
            return;
        }
        // (0) 조회~삭제 사이 상태 변경(재결제 성공 등) 방어. PAYMENT_EXPIRED가 아니면 삭제 대상이 아니다.
        if (order.getStatus() != OrderStatus.PAYMENT_EXPIRED) {
            log.info("[ExpiredCleanup] cleanupOne skip: PAYMENT_EXPIRED 아님 status={} orderId={}", order.getStatus(), orderId);
            return;
        }
        // (1) PENDING payment 존재 시 skip. initiate 가드(STEP 1)로 신규 PENDING 생성은 차단되나, 조회 시점 잔존분 방어.
        if (paymentRepository.existsByOrderIdAndStatus(orderId, PaymentStatus.PENDING)) {
            log.info("[ExpiredCleanup] cleanupOne skip: PENDING payment 존재 orderId={}", orderId);
            return;
        }
        // (2) 재고 해제 완료 확인(order_item 삭제 전). 하나라도 reserved>0이면 OrderTerminated 재발행 후 이연.
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        if (hasUnreleasedReservation(items)) {
            eventPublisher.publishEvent(new OrderTerminated(order.getPublicId(), order.getId(), LocalDateTime.now()));
            cleanupMetrics.recordDeletionFailed(ExpiredOrderCleanupMetrics.REASON_RESERVED_UNRELEASED);
            log.error("[ExpiredCleanup] cleanupOne 삭제 이연: 재고 미해제(reserved>0) → OrderTerminated 재발행 orderId={} publicId={}",
                    orderId, order.getPublicId());
            return;
        }

        // (3) reserved==0: 자식→부모 순차 삭제. 손자(delivery·claim·refund) 존재 시 FK RESTRICT로 차단(정상 흐름상 미발생).
        try {
            paymentRepository.deleteByOrderId(orderId);
            shippingSnapshotRepository.deleteByOrderId(orderId);
            orderItemRepository.deleteByOrderId(orderId);
            orderRepository.deleteByIdBulk(orderId);
        } catch (DataIntegrityViolationException restrictViolation) {
            // 손자 FK RESTRICT 위반 = 미결제 종료 주문에 delivery/claim/refund 존재(데이터 이상). 계측·ERROR 후 rethrow하여
            // 건별 TX 전체 롤백(부분 삭제 방지). 스케줄러가 Exception으로 격리해 다음 건을 계속 처리한다.
            cleanupMetrics.recordDeletionFailed(ExpiredOrderCleanupMetrics.REASON_RESTRICT_VIOLATION);
            log.error("[ExpiredCleanup] cleanupOne 삭제 실패(FK RESTRICT·데이터 이상·손자 존재) orderId={} publicId={}",
                    orderId, order.getPublicId(), restrictViolation);
            throw restrictViolation;
        }
        log.info("[ExpiredCleanup] cleanupOne 삭제 완료 orderId={} publicId={} at={}",
                orderId, order.getPublicId(), LocalDateTime.now());
    }

    /**
     * order_item 중 하나라도 variant 재고 예약(reserved)이 남아 있는지 판정한다. Inventory 행이 없으면 해제할 예약도 없어
     * 미해제로 보지 않는다(InventoryOrderTerminatedHandler의 reserved==0 skip 관습 정합).
     */
    private boolean hasUnreleasedReservation(List<OrderItem> items) {
        for (OrderItem item : items) {
            Inventory inventory = inventoryRepository.findByVariantId(item.getVariantId()).orElse(null);
            if (inventory != null && inventory.getQuantityReserved() > 0) {
                return true;
            }
        }
        return false;
    }
}
