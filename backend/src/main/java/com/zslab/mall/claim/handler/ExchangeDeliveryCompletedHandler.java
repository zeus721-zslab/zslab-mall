package com.zslab.mall.claim.handler;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.claim.service.ClaimExchangeService;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.event.DeliveryCompleted;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 교환품 배송 완료 이벤트의 종결 핸들러(Track 83 D-177·구 D-98 Q5 재설계). {@link DeliveryCompleted}가 EXCHANGE 클레임에 연결된
 * OUTBOUND Delivery(교환 발송)에서 왔으면 {@link ClaimExchangeService#completeExchange}에 위임한다 — 예약 확정·회수 재입고·품목 옵션 갱신·
 * Claim COMPLETED → 품목 DELIVERED 복귀(결정 1 α)가 그 안에서 같은 TX로 처리된다.
 *
 * <p>이중 가드: {@code delivery.claimId == null}(일반 배송)·{@code claim.type != EXCHANGE}(검수 FAIL 재발송 등)는 비대상 skip.
 * 멱등: 같은 이벤트 2회 소비는 completeExchange의 COMPLETED no-op이 흡수한다.
 *
 * <p><b>실행 시점(D-172)</b>: {@code @EventListener} 동기 소비 — 발행 TX({@code DeliveryService.markDelivered})와 같은 TX에서 실행되며 예외는
 * 그대로 전파돼 배송 완료 전이까지 롤백한다.
 */
@Slf4j
@Component
public class ExchangeDeliveryCompletedHandler {

    private final DeliveryRepository deliveryRepository;
    private final ClaimRepository claimRepository;
    private final ClaimExchangeService claimExchangeService;

    public ExchangeDeliveryCompletedHandler(DeliveryRepository deliveryRepository, ClaimRepository claimRepository,
            ClaimExchangeService claimExchangeService) {
        this.deliveryRepository = deliveryRepository;
        this.claimRepository = claimRepository;
        this.claimExchangeService = claimExchangeService;
    }

    @EventListener
    public void handle(DeliveryCompleted event) {
        if (event.direction() != DeliveryDirection.OUTBOUND) {
            // 반품 회수(RETURN) Delivery는 발송이 아니다(Track 81-A D-170) — 품목·알림·환불 소비처 비대상
            log.info("[ExchangeDelivery] DeliveryCompleted direction={} → 배송완료 소비처 비대상·건너뜀: deliveryId={}", event.direction(), event.deliveryId());
            return;
        }
        Delivery delivery = deliveryRepository.findById(event.deliveryId()).orElse(null);
        if (delivery == null) {
            log.warn("[ExchangeDelivery] DeliveryCompleted 수신·Delivery 미발견: deliveryId={}", event.deliveryId());
            return;
        }
        if (delivery.getClaimId() == null) {
            // 일반 배송 — 본 핸들러 비대상
            return;
        }
        Claim claim = claimRepository.findById(delivery.getClaimId()).orElse(null);
        if (claim == null) {
            log.warn("[ExchangeDelivery] Claim 미발견: claimId={} deliveryId={}", delivery.getClaimId(), event.deliveryId());
            return;
        }
        if (claim.getType() != ClaimType.EXCHANGE) {
            // 검수 FAIL 재발송(RETURN·REJECTED) 등 — 품목은 이미 원복돼 있어 비대상
            log.info("[ExchangeDelivery] type={} claim 연결 배송 → 교환 종결 비대상·skip: claimId={}", claim.getType(), claim.getId());
            return;
        }
        if (claim.getStatus() != ClaimStatus.APPROVED) {
            // REJECTED = 교환 검수 FAIL 재발송(품목 이미 DELIVERED 원복) / COMPLETED = 중복 이벤트 — 둘 다 종결 비대상
            log.info("[ExchangeDelivery] status={} EXCHANGE claim 연결 배송 → 종결 비대상·skip: claimId={}", claim.getStatus(), claim.getId());
            return;
        }
        claimExchangeService.completeExchange(claim.getId());
    }
}
