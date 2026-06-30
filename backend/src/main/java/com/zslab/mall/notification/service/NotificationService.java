package com.zslab.mall.notification.service;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.event.ClaimApproved;
import com.zslab.mall.claim.event.ClaimCompleted;
import com.zslab.mall.claim.event.ClaimPickedUp;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.event.DeliveryCompleted;
import com.zslab.mall.delivery.event.DeliveryStarted;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.notification.entity.NotificationLog;
import com.zslab.mall.notification.enums.NotificationChannel;
import com.zslab.mall.notification.repository.NotificationLogRepository;
import com.zslab.mall.notification.template.NotificationTemplateCodes;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.event.OrderPlaced;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.payment.event.PaymentCompleted;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 이벤트 → NotificationLog 적재 오케스트레이션(Track 12·D-95 Q5 α). 발행처 존재 4 이벤트(E1 OrderPlaced·
 * E2 PaymentCompleted·ClaimApproved·E9 ClaimCompleted)를 소비해 NotificationLog를 PENDING 적재한다.
 *
 * <p><b>재조회 기반 적재(D-95 Q5 α·D-30 사실 통지)</b>: 이벤트 payload는 식별자·금액·시각만 보유하므로
 * recipient(Buyer ID)·title·content는 이벤트 식별자로 원본 Aggregate를 재조회해 산정한다. payload는 무수정이며
 * 발행처에 영향을 주지 않는다.
 *
 * <p><b>skip 정책(D-95 A1-α·A2-α)</b>: 재조회 결과 Optional.empty()이거나 재조회 중 예외가 발생하면 적재를 건너뛰고
 * structured log(warn)만 남긴다. recipient·title·content·templateCode 중 하나라도 산정 불가하면 NULL 적재를
 * 회피하고 skip한다(적재 의미 보존). 예외는 핸들러 상위로 재throw하지 않는다(원 흐름 비차단).
 *
 * <p>channel은 EMAIL 고정이다(실 전송 어댑터 부재·발송 채널 선택은 본 트랙 OUT-OF-SCOPE).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final NotificationChannel DEFAULT_CHANNEL = NotificationChannel.EMAIL;

    private final NotificationLogRepository notificationLogRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ClaimRepository claimRepository;
    private final DeliveryRepository deliveryRepository;

    /**
     * OrderPlaced(E1) 소비 → 주문 접수 알림 적재. orderId로 Order를 재조회해 Buyer를 recipient로 산정한다.
     */
    public void recordOrderPlaced(OrderPlaced event) {
        try {
            Order order = orderRepository.findById(event.orderId()).orElse(null);
            if (order == null) {
                log.warn("[Notification] OrderPlaced 소비·주문 미발견 → 적재 건너뜀: orderId={}", event.orderId());
                return;
            }
            String content = "주문 " + event.publicId() + "이(가) 접수되었습니다.";
            save(order.getBuyerId(), NotificationTemplateCodes.ORDER_PLACED,
                    PolymorphicTargetType.ORDER, event.orderId(), "주문 접수", content);
        } catch (RuntimeException exception) {
            // 재조회·적재 실패는 원 흐름(주문)을 막지 않는다(A2-α·재throw 금지).
            log.warn("[Notification] OrderPlaced 적재 실패 → 건너뜀: orderId={}", event.orderId(), exception);
        }
    }

    /**
     * PaymentCompleted(E2) 소비 → 결제 완료 알림 적재. orderId로 Order를 재조회해 Buyer를 recipient로 산정한다.
     */
    public void recordPaymentCompleted(PaymentCompleted event) {
        try {
            Order order = orderRepository.findById(event.orderId()).orElse(null);
            if (order == null) {
                log.warn("[Notification] PaymentCompleted 소비·주문 미발견 → 적재 건너뜀: orderId={}", event.orderId());
                return;
            }
            String content = "결제 " + event.amount() + "원이 완료되었습니다.";
            save(order.getBuyerId(), NotificationTemplateCodes.PAYMENT_COMPLETED,
                    PolymorphicTargetType.ORDER, event.orderId(), "결제 완료", content);
        } catch (RuntimeException exception) {
            // 재조회·적재 실패는 원 흐름(결제)을 막지 않는다(A2-α·재throw 금지).
            log.warn("[Notification] PaymentCompleted 적재 실패 → 건너뜀: orderId={}", event.orderId(), exception);
        }
    }

    /**
     * ClaimApproved 소비 → 클레임 승인 알림 적재. claimId로 Claim을 재조회하고 orderItem → order를 경유해 Buyer를
     * recipient로 산정한다. recipient 산정 불가 시 NULL 적재를 회피하고 skip한다(D-95 A1-α).
     */
    public void recordClaimApproved(ClaimApproved event) {
        try {
            Long recipientUserId = resolveClaimRecipient(event.claimId(), "ClaimApproved");
            if (recipientUserId == null) {
                return;
            }
            String content = "클레임 " + event.claimPublicId() + " " + claimTypeLabel(event.claimType())
                    + " 요청이 승인되었습니다.";
            save(recipientUserId, NotificationTemplateCodes.CLAIM_APPROVED,
                    PolymorphicTargetType.CLAIM, event.claimId(), "클레임 승인", content);
        } catch (RuntimeException exception) {
            // 재조회·적재 실패는 원 흐름(클레임 승인)을 막지 않는다(A2-α·재throw 금지).
            log.warn("[Notification] ClaimApproved 적재 실패 → 건너뜀: claimId={}", event.claimId(), exception);
        }
    }

    /**
     * ClaimCompleted(E9) 소비 → 클레임 완료 알림 적재. claimId로 Claim을 재조회하고 orderItem → order를 경유해
     * Buyer를 recipient로 산정한다.
     */
    public void recordClaimCompleted(ClaimCompleted event) {
        try {
            Long recipientUserId = resolveClaimRecipient(event.claimId(), "ClaimCompleted");
            if (recipientUserId == null) {
                return;
            }
            String content = "클레임 " + event.claimPublicId() + " " + claimTypeLabel(event.claimType())
                    + " 요청이 완료되었습니다.";
            save(recipientUserId, NotificationTemplateCodes.CLAIM_COMPLETED,
                    PolymorphicTargetType.CLAIM, event.claimId(), "클레임 완료", content);
        } catch (RuntimeException exception) {
            // 재조회·적재 실패는 원 흐름(클레임 완료)을 막지 않는다(A2-α·재throw 금지).
            log.warn("[Notification] ClaimCompleted 적재 실패 → 건너뜀: claimId={}", event.claimId(), exception);
        }
    }

    /**
     * D-96 후속 PR: ClaimApprovedHandler catch 블록에서 호출되어 Refund 자동 트리거 실패 시 운영 알림을 적재한다.
     * resolveClaimRecipient 재사용으로 Buyer를 recipient로 산정한다.
     *
     * <p><b>recipient 재정의 가능(D-96 Q2 α')</b>: 본 시점 Buyer 채택은 admin user 모델 부재(D-93 stub) 회피.
     * 운영자 알림 채널 도입 시 재정의 가능·발송 어댑터 트랙(D-86 §후속) 진입 시점 결정.
     *
     * <p><b>Refund 행 비의존(D-96 Q3)</b>: 본 메서드는 Refund 행 존재 여부와 무관하게 ClaimApproved 식별자
     * 기반으로 적재한다. catch 발화 시점에 Refund INSERT 전(Claim/PAY-1/Payment 검증 단계)일 수 있다.
     */
    public void recordRefundFailed(ClaimApproved event) {
        recordRefundFailed(event.claimId(), event.claimPublicId());
    }

    /**
     * D-98 Q2: RETURN 수거 확인 후 환불 자동 트리거({@code ClaimPickedUpHandler}) 실패 시 운영 알림을 적재한다.
     * {@code ClaimApproved} 경로와 동일한 환불 실패 적재 패턴(D-96 Q3)을 {@link ClaimPickedUp}에 1:1 재사용한다.
     */
    public void recordRefundFailed(ClaimPickedUp event) {
        recordRefundFailed(event.claimId(), event.claimPublicId());
    }

    private void recordRefundFailed(Long claimId, String claimPublicId) {
        try {
            Long recipientUserId = resolveClaimRecipient(claimId, "RefundFailed");
            if (recipientUserId == null) {
                return;
            }
            String content = "클레임 " + claimPublicId + " 환불 처리에 실패했습니다. 운영 확인이 필요합니다.";
            save(recipientUserId, NotificationTemplateCodes.REFUND_FAILED,
                    PolymorphicTargetType.CLAIM, claimId, "환불 실패", content);
        } catch (RuntimeException exception) {
            // 재조회·적재 실패는 원 흐름(환불 자동 트리거 catch)을 막지 않는다(A2-α·재throw 금지).
            log.warn("[Notification] RefundFailed 적재 실패 → 건너뜀: claimId={}", claimId, exception);
        }
    }

    /**
     * ClaimPickedUp(E11) 소비 → 수거 확인 알림 적재(D-98 Q8). claimId로 Claim을 재조회하고 orderItem → order를 경유해
     * Buyer를 recipient로 산정한다. recipient 산정 불가 시 NULL 적재를 회피하고 skip한다(D-95 A1-α).
     */
    public void recordClaimPickedUp(ClaimPickedUp event) {
        try {
            Long recipientUserId = resolveClaimRecipient(event.claimId(), "ClaimPickedUp");
            if (recipientUserId == null) {
                return;
            }
            String content = "클레임 " + event.claimPublicId() + " 반품 상품 수거가 확인되었습니다.";
            save(recipientUserId, NotificationTemplateCodes.PICKUP_CONFIRMED,
                    PolymorphicTargetType.CLAIM, event.claimId(), "수거 확인", content);
        } catch (RuntimeException exception) {
            // 재조회·적재 실패는 원 흐름(수거 확인)을 막지 않는다(A2-α·재throw 금지).
            log.warn("[Notification] ClaimPickedUp 적재 실패 → 건너뜀: claimId={}", event.claimId(), exception);
        }
    }

    /**
     * DeliveryStarted(E4) 소비 → 배송 시작 알림 적재(Track 13·D-97 Q6). deliveryId로 Delivery를 재조회하고
     * orderItem → order를 경유해 Buyer를 recipient로 산정한다. recipient 산정 불가 시 NULL 적재를 회피하고 skip한다(D-95 A1-α).
     */
    public void recordDeliveryStarted(DeliveryStarted event) {
        try {
            Long recipientUserId = resolveDeliveryRecipient(event.deliveryId(), "DeliveryStarted");
            if (recipientUserId == null) {
                return;
            }
            String content = "배송이 시작되었습니다. 송장번호: " + event.trackingNo();
            save(recipientUserId, NotificationTemplateCodes.DELIVERY_STARTED,
                    PolymorphicTargetType.DELIVERY, event.deliveryId(), "배송 시작", content);
        } catch (RuntimeException exception) {
            // 재조회·적재 실패는 원 흐름(배송 시작)을 막지 않는다(A2-α·재throw 금지).
            log.warn("[Notification] DeliveryStarted 적재 실패 → 건너뜀: deliveryId={}", event.deliveryId(), exception);
        }
    }

    /**
     * DeliveryCompleted(E5) 소비 → 배송 완료 알림 적재(Track 13·D-97 Q6). deliveryId로 Delivery를 재조회하고
     * orderItem → order를 경유해 Buyer를 recipient로 산정한다.
     */
    public void recordDeliveryCompleted(DeliveryCompleted event) {
        try {
            Long recipientUserId = resolveDeliveryRecipient(event.deliveryId(), "DeliveryCompleted");
            if (recipientUserId == null) {
                return;
            }
            String content = "배송이 완료되었습니다.";
            save(recipientUserId, NotificationTemplateCodes.DELIVERY_COMPLETED,
                    PolymorphicTargetType.DELIVERY, event.deliveryId(), "배송 완료", content);
        } catch (RuntimeException exception) {
            // 재조회·적재 실패는 원 흐름(배송 완료)을 막지 않는다(A2-α·재throw 금지).
            log.warn("[Notification] DeliveryCompleted 적재 실패 → 건너뜀: deliveryId={}", event.deliveryId(), exception);
        }
    }

    /**
     * 배송 알림 recipient(Buyer ID)를 산정한다(D-97 Q6). delivery → orderItem → order 체인 중 어느 한 단계라도
     * 미발견이면 NULL 적재를 회피하기 위해 null을 반환한다(D-95 A1-α). {@code eventName}은 skip 로그 식별용이다.
     */
    private Long resolveDeliveryRecipient(Long deliveryId, String eventName) {
        Delivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
        if (delivery == null) {
            log.warn("[Notification] {} 소비·배송 미발견 → 적재 건너뜀: deliveryId={}", eventName, deliveryId);
            return null;
        }
        Long orderId = orderItemRepository.findOrderIdById(delivery.getOrderItemId()).orElse(null);
        if (orderId == null) {
            log.warn("[Notification] {} 소비·주문 품목 미발견 → 적재 건너뜀: deliveryId={} orderItemId={}",
                    eventName, deliveryId, delivery.getOrderItemId());
            return null;
        }
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("[Notification] {} 소비·주문 미발견 → 적재 건너뜀: deliveryId={} orderId={}", eventName, deliveryId, orderId);
            return null;
        }
        return order.getBuyerId();
    }

    /**
     * 클레임 알림 recipient(Buyer ID)를 산정한다. claim → orderItem → order 체인 중 어느 한 단계라도 미발견이면
     * NULL 적재를 회피하기 위해 null을 반환한다(D-95 A1-α). {@code eventName}은 skip 로그 식별용이다.
     */
    private Long resolveClaimRecipient(Long claimId, String eventName) {
        Claim claim = claimRepository.findById(claimId).orElse(null);
        if (claim == null) {
            log.warn("[Notification] {} 소비·클레임 미발견 → 적재 건너뜀: claimId={}", eventName, claimId);
            return null;
        }
        Long orderId = orderItemRepository.findOrderIdById(claim.getOrderItemId()).orElse(null);
        if (orderId == null) {
            log.warn("[Notification] {} 소비·주문 품목 미발견 → 적재 건너뜀: claimId={} orderItemId={}",
                    eventName, claimId, claim.getOrderItemId());
            return null;
        }
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("[Notification] {} 소비·주문 미발견 → 적재 건너뜀: claimId={} orderId={}", eventName, claimId, orderId);
            return null;
        }
        return order.getBuyerId();
    }

    private static String claimTypeLabel(ClaimType type) {
        return switch (type) {
            case CANCEL -> "취소";
            case RETURN -> "반품";
            case EXCHANGE -> "교환";
        };
    }

    private void save(Long recipientUserId, String templateCode, PolymorphicTargetType targetType,
            Long targetId, String title, String content) {
        NotificationLog notificationLog = NotificationLog.create(
                recipientUserId, DEFAULT_CHANNEL, templateCode, targetType, targetId, title, content);
        notificationLogRepository.save(notificationLog);
        log.info("[Notification] 적재 완료: template={} target_type={} target_id={} recipient={}",
                templateCode, targetType, targetId, recipientUserId);
    }
}
