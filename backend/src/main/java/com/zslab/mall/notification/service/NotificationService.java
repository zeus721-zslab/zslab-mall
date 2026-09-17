package com.zslab.mall.notification.service;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.event.ClaimApproved;
import com.zslab.mall.claim.event.ClaimCompleted;
import com.zslab.mall.claim.event.ClaimInspectionPassed;
import com.zslab.mall.claim.event.ClaimPickedUp;
import com.zslab.mall.claim.event.ClaimRejected;
import com.zslab.mall.claim.event.ClaimRequested;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.common.observability.NotificationDispatchMetricsRecorder;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.event.DeliveryCompleted;
import com.zslab.mall.delivery.event.DeliveryStarted;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.notification.adapter.NotificationSender;
import com.zslab.mall.notification.adapter.SmsSender;
import com.zslab.mall.notification.entity.NotificationLog;
import com.zslab.mall.notification.enums.NotificationChannel;
import com.zslab.mall.notification.enums.NotificationLogStatus;
import com.zslab.mall.notification.repository.NotificationLogRepository;
import com.zslab.mall.notification.template.NotificationTemplateCodes;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.event.OrderPlaced;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.payment.event.PaymentCompleted;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.repository.UserRepository;
import java.time.LocalDateTime;
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
 * <p>channel은 EMAIL 기본이다. Track 80(D-169)부터 클레임 요청 접수·거부·취소 완료는 SMS 채널로도 적재·발송한다
 * ({@link SmsSender}·수신번호는 User.phone·없으면 skip+warn·본문은 주문번호·상품명·처리 결과만).
 *
 * <p><b>즉시 발송(Track 19·판단 2 α·save/dispatch 분리)</b>: 적재 직후 {@link NotificationSender}로 발송하고 성공 시 SENT·
 * 실패 시 FAILED로 전이한다({@code dispatch}). 발송 실패는 상위(핸들러)로 재throw하지 않으며(D-95 A2-α)
 * {@code zslab.notification.failed} 계측과 structured log만 남긴다.
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
    private final NotificationSender notificationSender;
    private final SmsSender smsSender;
    private final UserRepository userRepository;
    private final NotificationDispatchMetricsRecorder notificationDispatchMetricsRecorder;

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
                    PolymorphicTargetType.ORDER, event.orderId(), "주문 접수", content, "OrderPlaced");
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
                    PolymorphicTargetType.ORDER, event.orderId(), "결제 완료", content, "PaymentCompleted");
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
                    PolymorphicTargetType.CLAIM, event.claimId(), "클레임 승인", content, "ClaimApproved");
            if (event.claimType() == ClaimType.RETURN) {
                // Track 81-A D-170·R8: 반품 승인은 구매자가 회수 송장을 등록해야 하므로 SMS로 안내한다(취소 승인은 즉시 환불이라 완료 SMS로 갈음).
                ClaimSmsContext sms = resolveClaimSmsContext(event.claimId(), "ClaimApproved");
                if (sms != null) {
                    saveSms(sms, NotificationTemplateCodes.CLAIM_APPROVED, event.claimId(), "반품 승인",
                            "[zslab-mall] 주문 " + sms.orderNo() + " " + sms.productName()
                                    + " 반품 요청이 승인되었습니다. 상품을 보내신 뒤 회수 송장번호를 등록해 주세요.", "ClaimApproved");
                }
            }
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
                    PolymorphicTargetType.CLAIM, event.claimId(), "클레임 완료", content, "ClaimCompleted");
            if (event.claimType() == ClaimType.CANCEL || event.claimType() == ClaimType.RETURN) {
                // Track 80 D-169·Track 81-A D-170: 취소·반품 완료(=환불 완료)는 구매자 SMS 병행. 교환 완료 SMS는 Track 82 소관.
                ClaimSmsContext sms = resolveClaimSmsContext(event.claimId(), "ClaimCompleted");
                if (sms != null) {
                    String typeLabel = claimTypeLabel(event.claimType());
                    saveSms(sms, NotificationTemplateCodes.CLAIM_COMPLETED, event.claimId(), typeLabel + " 완료",
                            "[zslab-mall] 주문 " + sms.orderNo() + " " + sms.productName()
                                    + " " + typeLabel + " 및 환불이 완료되었습니다.", "ClaimCompleted");
                }
            }
        } catch (RuntimeException exception) {
            // 재조회·적재 실패는 원 흐름(클레임 완료)을 막지 않는다(A2-α·재throw 금지).
            log.warn("[Notification] ClaimCompleted 적재 실패 → 건너뜀: claimId={}", event.claimId(), exception);
        }
    }

    /**
     * ClaimRequested 소비 → 요청 접수 SMS 적재·발송(Track 80 D-169). 수신번호(User.phone) 없으면 skip+warn.
     */
    public void recordClaimRequested(ClaimRequested event) {
        try {
            ClaimSmsContext sms = resolveClaimSmsContext(event.claimId(), "ClaimRequested");
            if (sms == null) {
                return;
            }
            String content = "[zslab-mall] 주문 " + sms.orderNo() + " " + sms.productName() + " "
                    + claimTypeLabel(event.claimType()) + " 요청이 접수되었습니다.";
            saveSms(sms, NotificationTemplateCodes.CLAIM_REQUESTED, event.claimId(),
                    claimTypeLabel(event.claimType()) + " 요청 접수", content, "ClaimRequested");
        } catch (RuntimeException exception) {
            // 재조회·적재 실패는 원 흐름(클레임 요청)을 막지 않는다(A2-α·재throw 금지).
            log.warn("[Notification] ClaimRequested 적재 실패 → 건너뜀: claimId={}", event.claimId(), exception);
        }
    }

    /**
     * ClaimRejected 소비 → 거부 SMS 적재·발송(Track 80 D-169). 본문에 거부 사유 라벨을 싣는다(메모는 싣지 않음·개인정보 최소).
     */
    public void recordClaimRejected(ClaimRejected event) {
        try {
            ClaimSmsContext sms = resolveClaimSmsContext(event.claimId(), "ClaimRejected");
            if (sms == null) {
                return;
            }
            String reasonLabel = event.rejectReasonCode() == null ? "" : " 사유: " + event.rejectReasonCode().getLabel();
            String content = "[zslab-mall] 주문 " + sms.orderNo() + " " + sms.productName() + " "
                    + claimTypeLabel(event.claimType()) + " 요청이 거부되었습니다." + reasonLabel;
            saveSms(sms, NotificationTemplateCodes.CLAIM_REJECTED, event.claimId(),
                    claimTypeLabel(event.claimType()) + " 요청 거부", content, "ClaimRejected");
        } catch (RuntimeException exception) {
            // 재조회·적재 실패는 원 흐름(클레임 거부)을 막지 않는다(A2-α·재throw 금지).
            log.warn("[Notification] ClaimRejected 적재 실패 → 건너뜀: claimId={}", event.claimId(), exception);
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
     * Track 81-A D-170: RETURN 검수 합격 후 환불 자동 트리거({@code ClaimInspectionPassedHandler}) 실패 시 운영 알림을 적재한다
     * (구 수거 확인 시점 트리거 D-98 Q2를 대체·D-96 Q3 패턴 1:1).
     */
    public void recordRefundFailed(ClaimInspectionPassed event) {
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
                    PolymorphicTargetType.CLAIM, claimId, "환불 실패", content, "RefundFailed");
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
                    PolymorphicTargetType.CLAIM, event.claimId(), "수거 확인", content, "ClaimPickedUp");
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
                    PolymorphicTargetType.DELIVERY, event.deliveryId(), "배송 시작", content, "DeliveryStarted");
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
                    PolymorphicTargetType.DELIVERY, event.deliveryId(), "배송 완료", content, "DeliveryCompleted");
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

    /** 클레임 SMS 본문 조립·발송에 필요한 최소 컨텍스트(Track 80 D-169). 수신번호는 로그에 남기지 않는다. */
    private record ClaimSmsContext(Long recipientUserId, String phoneNumber, String orderNo, String productName) {
    }

    /**
     * 클레임 SMS 컨텍스트(구매자 ID·번호·주문번호·상품명)를 산정한다. claim → orderItem → order → user 체인 중 하나라도 미발견이거나
     * User.phone이 비어 있으면 skip 로그 후 null을 반환한다(NULL 수신번호 적재 회피).
     */
    private ClaimSmsContext resolveClaimSmsContext(Long claimId, String eventName) {
        Claim claim = claimRepository.findById(claimId).orElse(null);
        if (claim == null) {
            log.warn("[Notification] {} 소비·클레임 미발견 → SMS 건너뜀: claimId={}", eventName, claimId);
            return null;
        }
        OrderItem orderItem = orderItemRepository.findById(claim.getOrderItemId()).orElse(null);
        Long orderId = orderItem == null ? null : orderItemRepository.findOrderIdById(orderItem.getId()).orElse(null);
        if (orderItem == null || orderId == null) {
            log.warn("[Notification] {} 소비·주문 품목 미발견 → SMS 건너뜀: claimId={} orderItemId={}",
                    eventName, claimId, claim.getOrderItemId());
            return null;
        }
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("[Notification] {} 소비·주문 미발견 → SMS 건너뜀: claimId={} orderId={}", eventName, claimId, orderId);
            return null;
        }
        User buyer = userRepository.findById(order.getBuyerId()).orElse(null);
        if (buyer == null || buyer.getPhone() == null || buyer.getPhone().isBlank()) {
            log.warn("[Notification] {} 소비·구매자 연락처 없음 → SMS 건너뜀: claimId={} buyerId={}",
                    eventName, claimId, order.getBuyerId());
            return null;
        }
        return new ClaimSmsContext(buyer.getId(), buyer.getPhone(), order.getOrderNo(), orderItem.getProductName());
    }

    /** SMS 채널 NotificationLog를 적재하고 {@link SmsSender}로 즉시 발송한다(save/dispatch 분리·EMAIL 경로와 동일 상태 전이). */
    private void saveSms(ClaimSmsContext sms, String templateCode, Long claimId, String title, String content,
            String eventName) {
        NotificationLog notificationLog = NotificationLog.create(
                sms.recipientUserId(), NotificationChannel.SMS, templateCode, PolymorphicTargetType.CLAIM, claimId,
                title, content);
        notificationLogRepository.save(notificationLog);
        log.info("[Notification] SMS 적재 완료: template={} target_id={} recipient={}", templateCode, claimId,
                sms.recipientUserId());
        dispatch(notificationLog, eventName, () -> smsSender.send(sms.phoneNumber(), content));
    }

    /**
     * 민감 본문 SMS(임시 비밀번호 등·Track 84)를 발송한다. 발송은 원문으로 하되 {@code notification_log.content}에는 마스킹본만
     * 저장한다(DB에 평문 잔존 금지). 회원 대상이라 target은 USER·userId다. 클레임 SMS와 달리 발송 결과를 호출자에게 돌려주므로
     * 호출자가 FAILED를 예외로 바꿔 자기 트랜잭션을 롤백할 수 있다(발송 실패 시 로그 행도 함께 롤백).
     *
     * @param recipientUserId 수신 회원 id
     * @param phoneNumber     수신 번호(원문·저장 안 함)
     * @param templateCode    템플릿 코드
     * @param title           로그 제목
     * @param content         발송 원문(민감·저장 안 함)
     * @param maskedContent   저장용 마스킹 본문
     * @param eventName       실패 계측 태그
     * @return 발송 후 로그 상태(SENT·FAILED)
     */
    public NotificationLogStatus sendSensitiveSms(Long recipientUserId, String phoneNumber, String templateCode, String title,
            String content, String maskedContent, String eventName) {
        NotificationLog notificationLog = NotificationLog.create(
                recipientUserId, NotificationChannel.SMS, templateCode, PolymorphicTargetType.USER, recipientUserId,
                title, maskedContent);
        notificationLogRepository.save(notificationLog);
        log.info("[Notification] 민감 SMS 적재 완료(마스킹 저장): template={} recipient={}", templateCode, recipientUserId);
        dispatch(notificationLog, eventName, () -> smsSender.send(phoneNumber, content));
        return notificationLog.getStatus();
    }

    private void save(Long recipientUserId, String templateCode, PolymorphicTargetType targetType,
            Long targetId, String title, String content, String eventName) {
        NotificationLog notificationLog = NotificationLog.create(
                recipientUserId, DEFAULT_CHANNEL, templateCode, targetType, targetId, title, content);
        notificationLogRepository.save(notificationLog);
        log.info("[Notification] 적재 완료: template={} target_type={} target_id={} recipient={}",
                templateCode, targetType, targetId, recipientUserId);
        dispatch(notificationLog, eventName, () -> notificationSender.send(notificationLog));
    }

    /**
     * 적재된 알림을 즉시 발송한다(Track 19·판단 2 α·save/dispatch 분리). 발송 성공 시 SENT, 실패 시 FAILED로 전이하고
     * 발송 실패 카운터를 계측한다. 발송 예외는 상위(핸들러)로 재throw하지 않는다(D-95 A2-α·원 흐름 비차단).
     * {@code eventName}은 실패 계측 태그({@code zslab.notification.failed{event}})에 사용한다. {@code sendAction}은 채널별 발송
     * 호출(EMAIL: {@link NotificationSender}·SMS: {@link SmsSender})이다.
     */
    private void dispatch(NotificationLog notificationLog, String eventName, Runnable sendAction) {
        try {
            sendAction.run();
            notificationLog.markSent(LocalDateTime.now());
            notificationLogRepository.save(notificationLog);
        } catch (RuntimeException exception) {
            // 발송 실패는 원 흐름을 막지 않는다(A2-α·재throw 금지). FAILED 전이·계측 후 structured log만 남긴다.
            notificationLog.markFailed(exception.getMessage());
            notificationLogRepository.save(notificationLog);
            notificationDispatchMetricsRecorder.recordFailed(eventName, notificationLog.getChannel().name());
            log.warn("[Notification] 발송 실패: event={} template={} target_type={} target_id={} channel={} action=manual_review",
                    eventName, notificationLog.getTemplateCode(), notificationLog.getTargetType(),
                    notificationLog.getTargetId(), notificationLog.getChannel(), exception);
        }
    }
}
