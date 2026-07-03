package com.zslab.mall.refund.handler;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.event.DeliveryStarted;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.notification.service.NotificationService;
import com.zslab.mall.refund.service.RefundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 교환품 출고 → 차액 환불 자동 트리거 핸들러(Track 30·D-115 결정1). {@link DeliveryStarted}(E4)를 소비해 차액이 발생하는
 * EXCHANGE 클레임 한정으로 {@link RefundService#initiate}를 자동 호출한다. CANCEL은 승인 시점({@code ClaimApprovedHandler}),
 * RETURN은 수거 확인 시점({@code ClaimPickedUpHandler})에 환불되며, 차액 없는 교환(refundAmount==0)은 본 핸들러 미대상이다.
 *
 * <p><b>위치·패턴(D-94 Q1 α 준용)</b>: 반응 도메인(Refund) 패키지에 두며 {@code ClaimApprovedHandler}·{@code ClaimPickedUpHandler}와
 * 1:1 대칭인 3번째 슬롯이다({@code claim} 패키지로의 {@code refund} 의존 역유입 회피).
 *
 * <p><b>실행 시점(D-75)</b>: {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code REQUIRES_NEW}로 교환 출고
 * 커밋 후 별도 트랜잭션에서 Refund를 생성한다(기존 2개 핸들러 패턴 1:1).
 *
 * <p><b>claimId 해소(D-115 실측)</b>: {@link DeliveryStarted}는 claimId를 운반하지 않으므로 deliveryId로 Delivery를 재조회해
 * {@code delivery.getClaimId()}로 라우팅한다. 일반 배송(claim_id NULL·{@code markShipping} 발행분)은 자연 skip한다.
 *
 * <p><b>amount·멱등(D-115 결정2·D-94 Q6 상속)</b>: amount는 {@code Claim.refundAmount}(승인 시 확정)를 전달한다. 동일 claimId
 * 활성 Refund 중복 차단은 {@link RefundService#initiate} 내부 게이트가 담당한다(본 핸들러는 가드를 중복하지 않는다).
 *
 * <p><b>실패 보상(D-96 Q3 1:1)</b>: {@code initiate}의 예외는 핸들러 밖으로 전파하지 않고
 * {@code NotificationService.recordRefundFailed(Claim)}로 운영 알림을 적재한다(Claim 환원 없음·재 initiate 허용).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeShipmentRefundHandler {

    private final DeliveryRepository deliveryRepository;
    private final ClaimRepository claimRepository;
    private final RefundService refundService;
    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(DeliveryStarted event) {
        Delivery delivery = deliveryRepository.findById(event.deliveryId()).orElse(null);
        if (delivery == null) {
            log.warn("[Refund] DeliveryStarted 수신·Delivery 미발견 → 차액 환불 건너뜀: deliveryId={}", event.deliveryId());
            return;
        }
        if (delivery.getClaimId() == null) {
            // 일반 배송(claim_id NULL·markShipping 발행분) — 교환 차액 환불 비대상
            return;
        }

        Claim claim = claimRepository.findById(delivery.getClaimId()).orElse(null);
        if (claim == null) {
            log.warn("[Refund] DeliveryStarted 소비·클레임 미발견 → 차액 환불 건너뜀: claimId={} deliveryId={}",
                    delivery.getClaimId(), event.deliveryId());
            return;
        }
        if (claim.getType() != ClaimType.EXCHANGE) {
            // 데이터 손상 방어 — claim_id는 교환 출고에서만 연결되나(D-98 Q13) 방어적으로 skip
            log.warn("[Refund] DeliveryStarted 소비·type 불일치·skip: claimId={} type={}", claim.getId(), claim.getType());
            return;
        }
        if (!claim.hasRefundDifference()) {
            // 차액 없는 교환(refundAmount==0·NULL) — Refund 미경유(기존 동작 100% 보존)
            log.info("[Refund] DeliveryStarted 소비·차액 없음 → 환불 미대상: claimId={}", claim.getId());
            return;
        }

        try {
            refundService.initiate(claim.getId(), claim.getRefundAmount());
        } catch (RuntimeException exception) {
            // PG 장애·도메인 위반은 핸들러 밖으로 전파하지 않는다(Claim 환원 없음·재 initiate 허용·D-94 Q8·D-96 Q3).
            notificationService.recordRefundFailed(claim);
        }
    }
}
