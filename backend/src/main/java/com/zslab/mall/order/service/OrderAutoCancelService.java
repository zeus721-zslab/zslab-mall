package com.zslab.mall.order.service;

import com.zslab.mall.common.observability.TracedEventPublisher;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.enums.OrderStatus;
import com.zslab.mall.order.event.OrderTerminated;
import com.zslab.mall.order.repository.OrderRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 미결제 주문 종료 Application Service(FE-12c·D-153 레벨3). 유예 경과·결제 실패·결제창 이탈로 결제가 완료되지 못한
 * PENDING_PAYMENT 주문 1건을 PAYMENT_EXPIRED로 종료하고 {@link OrderTerminated}를 발행한다. 재고 예약 해제는 본 서비스가
 * 직접 호출하지 않고 {@code InventoryOrderTerminatedHandler}(OrderTerminated AFTER_COMMIT 소비)가 담당한다(원칙 3·관심사 분리).
 *
 * <p><b>종료 방식(FE-12c)</b>: Phase 1은 OrderItem을 CANCELLED로 전이 후 Resolver로 CANCELLED를 파생했으나, 미결제 종료를
 * CANCELLED(결제 후 취소 전용)와 분리하기 위해 Order.status를 PAYMENT_EXPIRED로 직접 세팅한다(OrderItem 무변경·Resolver 미경유·
 * PENDING_PAYMENT 직접 세팅 선례). Track 78 D-167부터는 {@link Order#expirePayment} 엔티티 전이 대신
 * {@link OrderRepository#transitionStatus} 조건부 UPDATE로 세팅한다. 결제창 취소·PG 실패·만료·시작 실패의 공통 Order 종료 실행체다.
 *
 * <p><b>단건 트랜잭션 경계</b>: {@link #cancelOne}은 주문 1건당 독립 {@code @Transactional}이다. 배치 오케스트레이션
 * ({@code OrderAutoCancelScheduler})·결제 콜백 경로(PaymentService)는 id별로 본 메서드를 호출하며, 한 건 실패가 다른 건의
 * 커밋을 롤백하지 않는다(부분 실패 격리).
 *
 * <p><b>멱등</b>: 조건부 UPDATE 영향 행이 0이면(이미 종료·결제 완료·행 없음) no-op skip한다. Payment 도메인은 참조하지 않는다(원칙 4).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderAutoCancelService {

    private final OrderRepository orderRepository;
    private final TracedEventPublisher eventPublisher;

    /**
     * PENDING_PAYMENT 주문 1건을 미결제 종료(PAYMENT_EXPIRED)한다. {@link OrderRepository#transitionStatus} 조건부 UPDATE
     * (WHERE status = PENDING_PAYMENT)로 전이하고(OrderItem 무변경), 영향 행이 1일 때만 {@link OrderTerminated}를 발행한다.
     * 영향 행 0(이미 종료·결제 완료·행 없음)이면 무처리다 — 동시 종료 2건이 모두 통과해 OrderTerminated가 2회 발행되는 경로를
     * DB 레벨에서 닫는다(Track 78 D-167·R3). 이벤트 payload용 public_id는 UPDATE 이후 재조회한다(clearAutomatically 정합).
     *
     * @param orderId 종료 대상 주문 id
     * @return 전이·발행이 실제로 수행됐으면 true, 영향 행 0(이미 종료·결제 완료·행 없음)으로 무처리면 false(Track 79 관리자 취소 409 판정용)
     */
    @Transactional
    public boolean cancelOne(Long orderId) {
        int affected = orderRepository.transitionStatus(
                orderId, OrderStatus.PENDING_PAYMENT, OrderStatus.PAYMENT_EXPIRED, LocalDateTime.now());
        if (affected == 0) {
            // 행 없음·이미 종료·결제 완료(조회~전이 사이 경합 포함) — 조건부 UPDATE가 0건이면 무처리(멱등).
            log.debug("[OrderAutoCancel] cancelOne skip: PENDING_PAYMENT 전이 대상 아님(영향 행 0) orderId={}", orderId);
            return false;
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("전이 직후 주문 행 미존재: orderId=" + orderId));

        eventPublisher.publishEvent(new OrderTerminated(order.getPublicId(), order.getId(), LocalDateTime.now()));

        log.info("[OrderAutoCancel] cancelOne PAYMENT_EXPIRED 종료 완료 orderId={} publicId={}", orderId, order.getPublicId());
        return true;
    }
}
