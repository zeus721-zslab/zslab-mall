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
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.service.OrderService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 셀러 배송 명령(Track 90-B-1·{@link AdminDeliveryCommandService} 복제 + 소유 대조). 송장 정정 1건 — 관리자와 같은 제약(SHIPPING만·
 * 타 배송 송장번호 중복 409·사유 필수·상태 불변·값이 바뀐 경우에만 감사)에 셀러 소유 검증을 더한다. 관리자 서비스에 sellerId를 주입하지
 * 않고 셀러용을 따로 둔다(관리자 무수정 원칙). 쓰기 메서드라 SUSPENDED 셀러는 resolver가 403으로 먼저 막는다(D-190).
 */
@Service
@Transactional
@RequiredArgsConstructor
public class SellerDeliveryCommandService {

    private final DeliveryRepository deliveryRepository;
    private final OrderItemRepository orderItemRepository;
    private final AuditRecorder auditRecorder;
    private final OrderService orderService;

    /**
     * 셀러 송장 정정. Delivery는 sellerId를 직접 보유하지 않으므로 order_item.seller_id로 소유를 대조한다({@code OrderShippingService.
     * markDeliveredBySeller} 계약 동일). 타 셀러 배송은 미존재와 같은 404(존재 은닉).
     *
     * @throws DeliveryNotFoundException           deliveryPublicId 미존재·타 셀러 배송(404)
     * @throws DeliveryInvalidStateException       SHIPPING이 아닌 배송(422)
     * @throws DeliveryTrackingNoConflictException 다른 배송 행이 같은 송장번호를 이미 사용(409·자기 행의 기존 번호는 충돌 아님)
     */
    public Delivery correctTracking(Long sellerId, String deliveryPublicId, DeliveryCarrier carrier, String trackingNo,
            String reason, AuditContext auditContext) {
        // Track 104-1 D-215(P5): 배송 행 락보다 주문 쓰기 락을 먼저 잡는다(타 셀러 배송이면 뒤의 소유 대조가 404로 끝낸다).
        deliveryRepository.findOrderIdByPublicId(deliveryPublicId).ifPresent(orderService::lockForWrite);
        // 행 락 후 상태를 읽는다(Track 99 외부 검토 4·관리자 경로와 동일 — lost update 차단).
        Delivery delivery = deliveryRepository.findWithLockByPublicId(deliveryPublicId)
                .filter(candidate -> isOwnedBy(candidate, sellerId))
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

    private boolean isOwnedBy(Delivery delivery, Long sellerId) {
        return orderItemRepository.findById(delivery.getOrderItemId())
                .map(item -> item.getSellerId().equals(sellerId))
                .orElse(false);
    }
}
