package com.zslab.mall.claim.service;

import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimInspectionResult;
import com.zslab.mall.claim.enums.ClaimRejectReasonCode;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.event.ClaimInspectionPassed;
import com.zslab.mall.claim.event.ClaimPickedUp;
import com.zslab.mall.claim.event.ClaimRejected;
import com.zslab.mall.claim.exception.ClaimInvalidStateException;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.common.observability.TracedEventPublisher;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.service.DeliveryService;
import com.zslab.mall.inbox.stream.InboxSignalPublisher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 반품·교환 회수(송장 등록·회수 확인)와 검수 Application Service. {@link ClaimService}에서 분리했다(D-265).
 */
@Slf4j
@Service
@Transactional
public class ClaimReturnService {

    private final ClaimRepository claimRepository;
    private final TracedEventPublisher eventPublisher;
    private final DeliveryService deliveryService;
    private final ClaimExchangeService claimExchangeService;
    private final EntityManager entityManager;
    private final AuditRecorder auditRecorder;
    private final InboxSignalPublisher inboxSignalPublisher;
    private final ClaimAccess claimAccess;

    public ClaimReturnService(
            ClaimRepository claimRepository,
            TracedEventPublisher eventPublisher,
            DeliveryService deliveryService,
            ClaimExchangeService claimExchangeService,
            EntityManager entityManager,
            AuditRecorder auditRecorder,
            InboxSignalPublisher inboxSignalPublisher,
            ClaimAccess claimAccess) {
        this.claimRepository = claimRepository;
        this.eventPublisher = eventPublisher;
        this.deliveryService = deliveryService;
        this.claimExchangeService = claimExchangeService;
        this.entityManager = entityManager;
        this.auditRecorder = auditRecorder;
        this.inboxSignalPublisher = inboxSignalPublisher;
        this.claimAccess = claimAccess;
    }

    /**
     * 구매자 반품 회수 송장 등록(Track 81-A D-170·R4). 본인 주문의 RETURN·APPROVED·회수 확인 전 클레임에만 허용한다(소유 기준은 주문 구매자·Track 101-B).
     * 소유 위반·미존재는 404(정보 노출 회피·Q8), 유형·상태 위반은 422. Delivery 생성·SHIPPING·이벤트는 {@link DeliveryService#registerReturnShipment}.
     *
     * @throws ClaimNotFoundException     클레임이 없거나 주문의 구매자가 아닌 경우
     * @throws ClaimInvalidStateException type != RETURN·APPROVED 아님·이미 회수 확인됨·회수 송장 중복(422)
     */
    public Delivery registerReturnShipmentByBuyer(String claimPublicId, Long buyerId, DeliveryCarrier carrier, String trackingNo) {
        claimAccess.lockOrderOfClaimPublicId(claimPublicId);
        Claim claim = claimAccess.findClaimByPublicIdForUpdate(claimPublicId);
        claimAccess.verifyBuyerOwnership(claim, buyerId, claimPublicId);
        return registerReturnShipment(claim, carrier, trackingNo);
    }

    /**
     * 운영자 회수 송장 대행 등록(Track 101-A). 구매자가 회수 송장을 올리지 않으면 관리자는 회수 확인·검수로 넘어갈 수 없고
     * 독촉 수단도 없어 클레임이 그대로 멈춰 있었다(정찰 라운드 3 §3-1). 전화로 받은 송장번호를 운영자가 대신 넣어 흐름을 잇는다.
     *
     * <p>구매자 경로({@link #registerReturnShipmentByBuyer})와 <b>같은 도메인 경로</b>({@link #registerReturnShipment})를 쓰므로
     * 생성되는 RETURN Delivery·구매자 화면 표시·이후 회수 확인 동작이 모두 동일하다. 관리자는 전체 접근이라 소유 검증 단락만 없다
     * (D-93 Q3). 중복 등록은 {@code DeliveryService.registerReturnShipment}가 422로 막는다.
     *
     * <p>대행 등록임을 감사에 남긴다 — 구매자가 직접 올린 송장과 운영자가 대신 넣은 송장은 분쟁 시 의미가 달라진다.
     *
     * @throws ClaimNotFoundException     클레임이 없는 경우
     * @throws ClaimInvalidStateException type이 RETURN·EXCHANGE가 아님·APPROVED 아님·이미 회수 확인됨·회수 송장 중복(422)
     */
    public Delivery registerReturnShipmentByAdmin(String claimPublicId, DeliveryCarrier carrier, String trackingNo,
            AuditContext auditContext) {
        claimAccess.lockOrderOfClaimPublicId(claimPublicId);
        Claim claim = claimAccess.findClaimByPublicIdForUpdate(claimPublicId);
        Delivery delivery = registerReturnShipment(claim, carrier, trackingNo);
        auditRecorder.record(auditContext, AuditLogAction.CREATE, PolymorphicTargetType.DELIVERY, delivery.getId(),
                Map.of(),
                Map.of("claimId", claim.getId(), "direction", delivery.getDirection().name(),
                        "carrier", carrier.name(), "trackingNo", trackingNo, "registeredOnBehalfOfBuyer", true));
        return delivery;
    }

    /**
     * 회수 송장 등록 primitive(actor 비의존·D-92). 유형·상태·회수 확인 여부를 가드한 뒤 Delivery 생성을 위임한다.
     * 소유 검증은 액터별 wrapper 책임이다.
     *
     * <p><b>전제(Track 101-A 외부 검토 반영)</b>: 호출자는 {@link ClaimAccess#findClaimByPublicIdForUpdate}로 <b>클레임 행을 잠근 뒤</b>
     * 이 메서드를 부른다. 중복 등록 가드는 {@code delivery} 행을 세는데 그 행에는 유니크 제약이 없어, 클레임을 잠그지 않으면
     * 구매자와 관리자가 동시에 들어와 둘 다 "회수 송장 없음"으로 판정하고 RETURN Delivery를 2건 만들 수 있다.
     * 클레임 행이 직렬화 지점이다.
     */
    private Delivery registerReturnShipment(Claim claim, DeliveryCarrier carrier, String trackingNo) {
        if (!claim.getType().isPickupBased()) {
            throw new ClaimInvalidStateException("회수 송장은 RETURN·EXCHANGE 클레임에만 등록할 수 있습니다: type=" + claim.getType());
        }
        if (claim.getStatus() != ClaimStatus.APPROVED) {
            throw new ClaimInvalidStateException("승인된 반품·교환만 회수 송장을 등록할 수 있습니다: " + claim.getStatus());
        }
        if (claim.getPickedUpAt() != null) {
            throw new ClaimInvalidStateException("이미 회수 확인된 반품입니다: claimId=" + claim.getId());
        }
        Delivery delivery = deliveryService.registerReturnShipment(claim, carrier, trackingNo);
        inboxSignalPublisher.adminChanged();
        return delivery;
    }

    /**
     * 클레임 수거 확인 도메인 전이 primitive(D-98 Q1·actor·type 비의존). save 직후 {@link ClaimPickedUp}(E11)을 발행한다(D-29).
     *
     * <p>멱등 no-op: 이미 picked_up_at != null이면 변경 없이 log.info 후 return({@link ClaimService#markCompleted} 멱등 가드 패턴 1:1).
     * 합법 상태 전이(status == APPROVED 가드)는 {@link Claim#confirmPickup}이 수행한다.
     *
     * <p>외부 HTTP 진입점 직접 호출 금지. 외부 액터(Admin) 호출은 wrapper(confirmPickupByAdmin) 경유 의무(셀러 wrapper는 Track 92에서 제거).
     *
     * @throws ClaimNotFoundException     클레임이 없는 경우
     * @throws ClaimInvalidStateException APPROVED가 아닌 경우(CLM-4)
     */
    public void confirmPickup(Long claimId, LocalDateTime pickedUpAt) {
        claimAccess.lockOrderOfClaim(claimId);
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: claimId=" + claimId));
        if (claim.getPickedUpAt() != null) {
            log.info("[Claim] confirmPickup 멱등 NO-OP(이미 picked_up_at 설정됨): claimId={}", claimId);
            return;
        }
        claim.confirmPickup(pickedUpAt);
        if (claim.getType().isPickupBased()) {
            // Track 81-A D-170·D-177: 반품·교환 회수 확인은 구매자 회수 송장(RETURN Delivery)이 선행돼야 하며 그 Delivery를 DELIVERED로 마감한다(부재 422).
            deliveryService.completeReturnShipment(claimId);
        }
        claimRepository.save(claim);
        eventPublisher.publishEvent(new ClaimPickedUp(
                claim.getId(), claim.getPublicId(), claim.getOrderItemId(),
                claim.getType(), pickedUpAt, LocalDateTime.now()));
        inboxSignalPublisher.adminChanged();
    }

    /**
     * Admin 액터의 Claim 수거 확인 진입점(D-98 Q9·D-92 횡단 원칙 재사용 2회차).
     *
     * <p>Admin은 전체 Claim 접근 권한을 가지므로 권한 검증 단락이 부재한다(D-93 Q3). Claim 미존재만 404다.
     * {@link #confirmPickup} primitive에 위임한다.
     *
     * @throws ClaimNotFoundException     클레임이 없는 경우
     * @throws ClaimInvalidStateException APPROVED가 아닌 경우(CLM-4)
     */
    public void confirmPickupByAdmin(Long claimId, LocalDateTime pickedUpAt, AuditContext auditContext) {
        // 기존 값은 락 뒤에 읽는다 — 락 전에 클레임을 1차 캐시에 올리면 primitive가 동시 회수 확인의 커밋을 못 본다(lockOrderOfClaim 규약).
        claimAccess.lockOrderOfClaim(claimId);
        LocalDateTime previous = claimAccess.findClaim(claimId).getPickedUpAt();
        confirmPickup(claimId, pickedUpAt);
        // before에 같은 키를 실어야 멱등 no-op(이미 회수 확인)에서 before/after가 같아 적재가 skip된다(W3). 첫 확인의 before는 값 없음(null).
        Map<String, Object> before = new HashMap<>();
        before.put("pickedUpAt", previous == null ? null : String.valueOf(previous));
        LocalDateTime confirmed = claimAccess.findClaim(claimId).getPickedUpAt();
        recordClaimAudit(auditContext, AuditLogAction.UPDATE, claimId,
                before, Map.of("pickedUpAt", String.valueOf(confirmed)));
    }

    /**
     * 반품 검수 primitive(Track 81-A D-170·R5·actor 비의존). 클레임 행을 {@code refresh(PESSIMISTIC_WRITE)}로 잠가 동시 검수를 직렬화한
     * 뒤(늦은 쪽은 "이미 검수됨" 422) PASS/FAIL을 적용한다.
     * <ul>
     *   <li>PASS: {@link Claim#passInspection}(restock 필수) → {@link ClaimInspectionPassed} 발행 → 환불 개시(핸들러)</li>
     *   <li>FAIL: {@link Claim#failInspection}(거부 사유 필수·APPROVED → REJECTED 예외 전이) → 재발송 Delivery(OUTBOUND·택배사·송장 필수)
     *       → {@link ClaimRejected} 발행 → 품목 스냅샷 원복(DELIVERED)·거부 SMS(기존 경로)</li>
     * </ul>
     * 외부 HTTP 진입점 직접 호출 금지. 외부 액터(Admin) 호출은 wrapper(inspectByAdmin) 경유 의무(셀러 wrapper는 Track 92에서 제거).
     *
     * @throws ClaimNotFoundException     클레임이 없는 경우
     * @throws ClaimInvalidStateException type != RETURN·APPROVED 아님·미회수·이미 검수됨·재발송 중복(422)
     * @throws IllegalArgumentException   PASS인데 restock 누락 / FAIL인데 사유·택배사·송장 누락·사유가 INSPECTION_FAILED가 아님(400·D-172)
     */
    public void inspect(Long claimId, ClaimInspectionResult result, Boolean restock, ClaimRejectReasonCode rejectReasonCode,
            String memo, DeliveryCarrier reshipCarrier, String reshipTrackingNo, LocalDateTime inspectedAt) {
        if (result == null) {
            throw new IllegalArgumentException("inspect: 검수 결과는 필수입니다.");
        }
        claimAccess.lockOrderOfClaim(claimId);
        Claim claim = claimAccess.findClaim(claimId);
        entityManager.refresh(claim, LockModeType.PESSIMISTIC_WRITE);
        if (result == ClaimInspectionResult.PASS) {
            if (restock == null) {
                throw new IllegalArgumentException("inspect: PASS는 재입고 여부(restock)가 필수입니다.");
            }
            claim.passInspection(restock, inspectedAt);
            claimRepository.save(claim);
            eventPublisher.publishEvent(new ClaimInspectionPassed(
                    claim.getId(), claim.getPublicId(), claim.getOrderItemId(), restock, LocalDateTime.now()));
            inboxSignalPublisher.adminChanged();
            return;
        }
        if (reshipCarrier == null || reshipTrackingNo == null || reshipTrackingNo.isBlank()) {
            throw new IllegalArgumentException("inspect: FAIL은 재발송 택배사·송장번호가 필수입니다.");
        }
        claim.failInspection(rejectReasonCode, memo, inspectedAt);
        claimRepository.save(claim);
        deliveryService.registerReshipment(claim, reshipCarrier, reshipTrackingNo);
        // D-177 결정 8: 교환 검수 FAIL은 종결(REJECTED)이므로 승인 시 예약한 교환 옵션 재고를 되돌린다(Inventory 최후·멱등).
        claimExchangeService.releaseExchangeReservationIfAny(claim);
        eventPublisher.publishEvent(new ClaimRejected(
                claim.getId(), claim.getPublicId(), claim.getOrderItemId(),
                claim.getType(), claim.getStatus(), claim.getRejectReasonCode(), LocalDateTime.now()));
        inboxSignalPublisher.adminChanged(); // 관리자 클레임 후속(검수 단계) 이탈
    }

    /** Admin 액터의 반품 검수 진입점(Track 81-A·전체 접근·미존재만 404). */
    public void inspectByAdmin(Long claimId, ClaimInspectionResult result, Boolean restock,
            ClaimRejectReasonCode rejectReasonCode, String memo, DeliveryCarrier reshipCarrier, String reshipTrackingNo,
            LocalDateTime inspectedAt, AuditContext auditContext) {
        claimAccess.lockOrderOfClaim(claimId);
        ClaimStatus before = claimAccess.findClaim(claimId).getStatus();
        inspect(claimId, result, restock, rejectReasonCode, memo, reshipCarrier, reshipTrackingNo, inspectedAt);
        Claim inspected = claimAccess.findClaim(claimId);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", inspected.getStatus().name());
        after.put("inspectionResult", result.name());
        if (restock != null) {
            after.put("restock", restock);
        }
        if (result == ClaimInspectionResult.FAIL) {
            // 불합격은 재발송 송장까지 같은 조작에서 만들어지므로 함께 남긴다(추적 시 Delivery 감사와 대조).
            after.put("reshipCarrier", reshipCarrier.name());
            after.put("reshipTrackingNo", reshipTrackingNo);
        }
        recordClaimAudit(auditContext, AuditLogAction.UPDATE, claimId, Map.of("status", before.name()), after);
    }

    /**
     * 관리자 클레임 조작을 감사 로그로 남긴다(Track 101-A). 클레임 전이는 불가역인데 그동안 행위자 기록이 없었다
     * (정찰 라운드 3 §2-4). 전이 필드(status·pickedUpAt·inspectionResult) 중심으로 before/after를 싣고 action은
     * 승인 APPROVE·거부 REJECT·그 외 UPDATE다(기존 AuditLogAction 재사용·DDL 무변경).
     *
     * <p>호출자 트랜잭션에 그대로 참여하므로 감사 적재 실패는 클레임 전이와 함께 롤백된다(AuditRecorder 규약).
     */
    private void recordClaimAudit(AuditContext auditContext, AuditLogAction action, Long claimId,
            Map<String, Object> before, Map<String, Object> after) {
        auditRecorder.record(auditContext, action, PolymorphicTargetType.CLAIM, claimId, before, after);
    }
}
