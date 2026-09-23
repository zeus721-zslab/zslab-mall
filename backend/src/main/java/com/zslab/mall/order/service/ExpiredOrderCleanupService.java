package com.zslab.mall.order.service;

import com.zslab.mall.common.observability.ExpiredOrderCleanupMetrics;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.enums.OrderStatus;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.order.repository.OrderShippingSnapshotRepository;
import com.zslab.mall.payment.enums.PaymentStatus;
import com.zslab.mall.payment.repository.PaymentRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 미결제 종료(PAYMENT_EXPIRED) 주문 hard delete Application Service(FE-12c-2). 유예(GRACE_DAYS) 경과 주문 1건을
 * 자식(payment·shipping_snapshot·order_item)→부모(order) 순으로 물리삭제한다. 되돌릴 수 없으므로 삭제 가능 조건을 만족한 주문만
 * 삭제하고, 미충족 시 skip한다(안전성 우선·삭제 &lt; 안전).
 *
 * <p><b>단건 트랜잭션 경계</b>: {@link #cleanupOne}은 주문 1건당 독립 {@code @Transactional}이다. 배치 오케스트레이션
 * ({@code ExpiredOrderCleanupScheduler})이 id별로 호출하며, 한 건 실패가 다른 건 커밋을 롤백하지 않는다(부분 실패 격리).
 * 자식→부모 삭제는 동일 트랜잭션 내에서 수행돼 원자적이다(부분 삭제 방지·FK RESTRICT 위반 시 전체 롤백).
 *
 * <p><b>삭제 가능 조건(모두 충족 시에만 삭제)</b>:
 * <ol>
 *   <li>(0) 조회~삭제 사이 status가 PAYMENT_EXPIRED 유지(재확인). 아니면 skip.</li>
 *   <li>(1) PENDING payment 부재. 존재 시 skip(결제 진행 가능성·삭제 위험).</li>
 * </ol>
 *
 * <p><b>보존 정책(D-175·검수 5단계 Q1)</b>: audit_log·notification_log·inventory_history·order_idempotency_key는 삭제하지 않는다.
 * 네 테이블은 order에 FK 없는 논리 참조(target_id·reference_id·order_id)라 삭제를 막지 않으며, append-only 감사·이력이므로 주문 행이
 * 사라져도 보존한다(주문 내부 id만 남고 order_no는 없음 — audit_log는 status diff, notification_log는 content에 주문 publicId 텍스트).
 *
 * <p><b>조회 단계 제외(D-175)</b>: 삭제 불가 주문(PENDING 결제·delivery/claim 손자)은 {@code OrderRepository.findExpiredCleanupCandidateIds}가
 * NOT EXISTS로 미리 걸러 배치 선두 점유 기아를 막는다. 아래 가드 (0)(1)은 조회~처리 사이 변경 대비로 유지한다.
 *
 * <p><b>재고 판정 없음(Track 78 D-167 보충2·γ)</b>: 구 (2) "variant reserved &gt; 0이면 OrderTerminated 재발행 후 이연"은 폐기했다.
 * 예약 해제는 종료 전이(OrderAutoCancelService.cancelOne 조건부 UPDATE)와 같은 트랜잭션에서 1회 실행되고 실패 시 전이가
 * 롤백되므로, PAYMENT_EXPIRED 주문은 해제가 이미 끝난 주문이다. variant 합계 reserved는 같은 variant의 살아있는 타 주문 예약과
 * 구분할 수 없어 재발행이 타 주문 예약을 해제하는 오판(false positive)을 일으켰다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpiredOrderCleanupService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderShippingSnapshotRepository shippingSnapshotRepository;
    private final PaymentRepository paymentRepository;
    private final ExpiredOrderCleanupMetrics cleanupMetrics;
    private final OrderService orderService;

    /**
     * PAYMENT_EXPIRED 주문 1건을 삭제 가능 조건 충족 시 hard delete한다. 미충족 시 skip한다.
     *
     * @param orderId 삭제 대상 주문 id
     */
    @Transactional
    public void cleanupOne(Long orderId) {
        // Track 104-1 D-215(P5): 가드 판정·자식 삭제 전에 주문 쓰기 락을 먼저 잡는다(재결제 시작 등과 직렬화).
        orderService.lockForWrite(orderId);
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            // 배치 조회~트랜잭션 사이 행이 사라지는 경우는 정상 흐름상 없으나 방어적으로 skip한다.
            log.info("[ExpiredCleanup] cleanupOne skip: 주문 행 없음 orderId={}", orderId);
            return;
        }
        // (0) 조회~삭제 사이 상태 변경(재결제 성공 등) 방어. PAYMENT_EXPIRED가 아니면 삭제 대상이 아니다.
        // 결제 전 단계 — Order.status가 원천 상태
        if (order.getStatus() != OrderStatus.PAYMENT_EXPIRED) {
            log.info("[ExpiredCleanup] cleanupOne skip: PAYMENT_EXPIRED 아님 status={} orderId={}", order.getStatus(), orderId);
            return;
        }
        // (1) PENDING payment 존재 시 skip. initiate 가드(STEP 1)로 신규 PENDING 생성은 차단되나, 조회 시점 잔존분 방어.
        if (paymentRepository.existsByOrderIdAndStatus(orderId, PaymentStatus.PENDING)) {
            log.info("[ExpiredCleanup] cleanupOne skip: PENDING payment 존재 orderId={}", orderId);
            return;
        }
        // (2) 자식→부모 순차 삭제. 손자(delivery·claim·refund) 존재 시 FK RESTRICT로 차단(정상 흐름상 미발생).
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
}
