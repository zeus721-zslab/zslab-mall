package com.zslab.mall.delivery.service;

import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자동 배송완료 단건 처리(Track 99 D-210). 배송 조회에서 배달 완료로 확인된 배송 1건을 독립 트랜잭션에서 DELIVERED로 전이한다
 * ({@code OrderAutoConfirmService.confirmOne} 패턴 1:1).
 *
 * <p><b>단건 트랜잭션 경계</b>: {@link #completeOne}은 배송 1건당 독립 {@code @Transactional}이다. 배치 조회·배송 조회는 이 트랜잭션
 * 밖에서 끝나 있으므로 외부 호출이 트랜잭션을 붙들지 않는다.
 *
 * <p><b>재확인</b>: 행 락({@code PESSIMISTIC_WRITE}) 후 방향·상태·<b>택배사·송장번호</b>를 조회 시점 값과 다시 맞춰 본다.
 * 방향·상태는 사람이 먼저 배송완료를 눌렀는지를, 택배사·송장번호는 <b>조회한 송장과 다른 송장이 되었는지</b>를 가린다 —
 * 운영자가 송장을 정정하면({@code Delivery.correctTracking}·SHIPPING 유지·상태는 그대로) 방금 조회한 결과는 더 이상 이 배송의 것이 아니므로
 * 그 결과로 전이하면 안 된다. 하나라도 어긋나면 전이하지 않고 false를 돌려주며, 다음 실행이 새 송장으로 다시 조회한다.
 *
 * <p><b>전이는 기존 경로를 그대로 쓴다</b>: {@link DeliveryService#markDelivered}를 재사용하므로 품목 DELIVERED 전이·주문 상태 재계산·
 * 교환 종결·배송완료 알림이 수동 처리와 똑같이 일어난다(같은 트랜잭션 동기 소비).
 *
 * <p><b>감사</b>: 수동 배송완료 경로에는 감사 로그가 없어 자동/수동을 사후에 구분할 수단이 없었다. 자동 전이만
 * {@link AuditContext#system()}(actorRole=SYSTEM)으로 1행 남긴다 — 기존 action 체계(UPDATE·DELIVERY)를 그대로 쓰며 스키마 변경이 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryAutoCompleteService {

    private final DeliveryRepository deliveryRepository;
    private final DeliveryService deliveryService;
    private final AuditRecorder auditRecorder;
    private final EntityManager entityManager;

    /**
     * 배송 1건을 자동 배송완료 처리한다. 행 락 후 재확인에 어긋나면 무처리(false)다.
     *
     * @param deliveryId      전이 대상 배송 id
     * @param trackedCarrier  배송 조회에 쓴 택배사(조회 시점 값)
     * @param trackedNo       배송 조회에 쓴 운송장번호(조회 시점 값)
     * @return 전이가 실제로 수행됐으면 true, 재확인 불일치로 skip이면 false
     */
    @Transactional
    public boolean completeOne(Long deliveryId, DeliveryCarrier trackedCarrier, String trackedNo) {
        Optional<Delivery> found = deliveryRepository.findById(deliveryId);
        if (found.isEmpty()) {
            log.debug("[DeliveryAutoComplete] skip: 배송 없음 deliveryId={}", deliveryId);
            return false;
        }
        Delivery delivery = found.get();
        entityManager.refresh(delivery, LockModeType.PESSIMISTIC_WRITE);

        if (delivery.getDirection() != DeliveryDirection.OUTBOUND) {
            // 회수(RETURN) 배송의 완료는 클레임 회수 확인 단일 경로다(Track 92-a D-197).
            log.debug("[DeliveryAutoComplete] skip: 발송 배송 아님 deliveryId={} direction={}", deliveryId, delivery.getDirection());
            return false;
        }
        if (delivery.getStatus() != DeliveryStatus.SHIPPING) {
            log.debug("[DeliveryAutoComplete] skip: 배송중 아님 deliveryId={} status={}", deliveryId, delivery.getStatus());
            return false;
        }
        if (delivery.getCarrier() != trackedCarrier || !Objects.equals(delivery.getTrackingNo(), trackedNo)) {
            // 조회 후 운영자가 송장을 정정했다(correctTracking은 SHIPPING을 유지하므로 상태 가드로는 걸리지 않는다).
            // 방금 받은 조회 결과는 다른 송장의 것이므로 버리고, 다음 실행이 새 송장으로 다시 조회하게 둔다.
            log.debug("[DeliveryAutoComplete] skip: 조회 후 송장 정정 deliveryId={} 조회={}/{} 현재={}/{}",
                    deliveryId, trackedCarrier, trackedNo, delivery.getCarrier(), delivery.getTrackingNo());
            return false;
        }

        deliveryService.markDelivered(deliveryId);
        auditRecorder.record(AuditContext.system(), AuditLogAction.UPDATE, PolymorphicTargetType.DELIVERY, deliveryId,
                Map.of("status", DeliveryStatus.SHIPPING.name()),
                Map.of("status", DeliveryStatus.DELIVERED.name()));
        log.info("[DeliveryAutoComplete] 자동 배송완료 deliveryId={} trackingNo={}", deliveryId, delivery.getTrackingNo());
        return true;
    }
}
