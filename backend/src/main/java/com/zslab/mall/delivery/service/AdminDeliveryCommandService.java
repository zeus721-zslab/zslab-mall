package com.zslab.mall.delivery.service;

import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.delivery.exception.DeliveryInvalidStateException;
import com.zslab.mall.delivery.exception.DeliveryNotFoundException;
import com.zslab.mall.delivery.exception.DeliveryTrackingNoConflictException;
import com.zslab.mall.delivery.repository.AdminDeliverySpecifications;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 배송 명령(Track 89-B D-184). 송장 정정 1건 — 상태 전이가 아니라 값 보정이므로 {@link DeliveryService}(전이·이벤트 발행)를
 * 거치지 않고 도메인 메서드 {@link Delivery#correctTracking}에 직접 위임하며, 사유·감사(AuditRecorder·89-A mark-cancelled 선례)를 붙인다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class AdminDeliveryCommandService {

    private final DeliveryRepository deliveryRepository;
    private final AuditRecorder auditRecorder;

    /**
     * 송장 정정. SHIPPING에서만 허용하며 상태·발송 시각은 바꾸지 않는다. 감사(UPDATE·DELIVERY·before carrier/trackingNo·after
     * carrier/trackingNo+reason)는 값이 실제로 바뀐 경우에만 남는다(무변경 재요청은 AuditRecorder diff 빈 → skip·응답 200).
     *
     * @throws DeliveryNotFoundException           deliveryPublicId 미존재(404)
     * @throws DeliveryInvalidStateException       SHIPPING이 아닌 배송(422)
     * @throws DeliveryTrackingNoConflictException 다른 배송 행이 같은 송장번호를 이미 사용(409·자기 행의 기존 번호는 충돌 아님)
     */
    public Delivery correctTracking(String deliveryPublicId, DeliveryCarrier carrier, String trackingNo, String reason,
            AuditContext auditContext) {
        // 행 락 후 상태를 읽는다(Track 99 외부 검토 4) — 락이 없으면 배송완료 커밋 뒤 이 트랜잭션의 save가 전 컬럼을 옛 값으로 덮어써
        // status·delivered_at이 되돌아간다(lost update). 락을 잡으면 아래 SHIPPING 가드가 최신 상태를 보고 422로 막는다.
        Delivery delivery = deliveryRepository.findWithLockByPublicId(deliveryPublicId)
                .orElseThrow(() -> new DeliveryNotFoundException("배송을 찾을 수 없습니다: publicId=" + deliveryPublicId));
        if (delivery.getStatus() != DeliveryStatus.SHIPPING) {
            throw new DeliveryInvalidStateException("송장 정정은 배송중(SHIPPING)에서만 가능합니다: status=" + delivery.getStatus());
        }
        String normalizedTrackingNo = trackingNo.trim();
        long conflicts = deliveryRepository.count(
                AdminDeliverySpecifications.trackingNoOfOther(normalizedTrackingNo, delivery.getId()));
        if (conflicts > 0) {
            throw new DeliveryTrackingNoConflictException("다른 배송이 이미 사용 중인 송장번호입니다: trackingNo=" + normalizedTrackingNo);
        }
        DeliveryCarrier beforeCarrier = delivery.getCarrier();
        String beforeTrackingNo = delivery.getTrackingNo();
        delivery.correctTracking(carrier, normalizedTrackingNo);
        deliveryRepository.save(delivery);
        // reason은 after에만 실리므로 값이 바뀐 경우에만 기록한다(무변경 재요청 감사 0행·89-A 규약).
        if (beforeCarrier != carrier || !normalizedTrackingNo.equals(beforeTrackingNo)) {
            auditRecorder.record(auditContext, AuditLogAction.UPDATE, PolymorphicTargetType.DELIVERY, delivery.getId(),
                    Map.of("carrier", beforeCarrier.name(), "trackingNo", beforeTrackingNo),
                    Map.of("carrier", carrier.name(), "trackingNo", normalizedTrackingNo, "reason", reason));
        }
        return delivery;
    }
}
