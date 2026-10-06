package com.zslab.mall.claim.service;

import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimDecision;
import com.zslab.mall.claim.enums.ClaimRejectReasonCode;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimSuggestion;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.event.ClaimApproved;
import com.zslab.mall.claim.event.ClaimCompleted;
import com.zslab.mall.claim.event.ClaimRejected;
import com.zslab.mall.claim.exception.ClaimInvalidStateException;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.claim.exception.ClaimSuggestionMismatchException;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.common.observability.TracedEventPublisher;
import com.zslab.mall.inbox.stream.InboxSignalPublisher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 클레임 Application Service(Track 9 PR-B·D-89). 승인·거절·구매자 신청 취소·종결과 관리자 승인·거절 진입점을 담당한다. 트랜잭션 경계는 메서드 단위다(QB-1).
 *
 * <p><b>승인/거절(approve·reject)</b>: 본 PR은 Service 계층만 완성하며 endpoint는 Track 10 소관이다(D-88 Q2). E2E는 Service 직접 호출.
 *
 * <p>조회·요청·회수/검수는 {@link ClaimQueryService}·{@link ClaimRequestService}·{@link ClaimReturnService}로 분리했고 공용 잠금·조회 헬퍼는 {@link ClaimAccess}에 둔다(D-265).
 */
@Slf4j
@Service
@Transactional
public class ClaimService {

    private final ClaimRepository claimRepository;
    private final TracedEventPublisher eventPublisher;
    private final ClaimExchangeService claimExchangeService;
    private final EntityManager entityManager;
    private final AuditRecorder auditRecorder;
    private final InboxSignalPublisher inboxSignalPublisher;
    private final ClaimSuggestionService claimSuggestionService;
    private final ClaimAccess claimAccess;

    public ClaimService(
            ClaimRepository claimRepository,
            TracedEventPublisher eventPublisher,
            ClaimExchangeService claimExchangeService,
            EntityManager entityManager,
            AuditRecorder auditRecorder,
            InboxSignalPublisher inboxSignalPublisher,
            ClaimSuggestionService claimSuggestionService,
            ClaimAccess claimAccess) {
        this.claimRepository = claimRepository;
        this.eventPublisher = eventPublisher;
        this.claimExchangeService = claimExchangeService;
        this.entityManager = entityManager;
        this.auditRecorder = auditRecorder;
        this.inboxSignalPublisher = inboxSignalPublisher;
        this.claimSuggestionService = claimSuggestionService;
        this.claimAccess = claimAccess;
    }

    /**
     * Claim 승인 도메인 전이 primitive. save 직후 {@link ClaimApproved}를 발행한다(D-29).
     *
     * <p>외부 HTTP 진입점 직접 호출 금지. 외부 액터(Admin) 호출은 wrapper 메서드(approveByAdmin) 경유 의무(셀러 wrapper는 Track 92에서 제거).
     * 본 primitive는 권한 검증 미수행·도메인 상태 전이 단독 책임이다. 직접 호출은 도메인 내부 호출 또는 통합 테스트
     * 순수 상태 전이 검증에 한정한다.
     *
     * <p>D-92 횡단 원칙 정합: 도메인 상태 전이 메서드는 actor 식별자가 상태 자체에 포함되지 않는 한
     * actor 비의존 시그니처를 우선한다.
     *
     * <p>교환 차액 환불(D-115)은 D-177(같은 가격 옵션만 교환)로 폐기됐다. {@code refundAmount}는 호환 인자로만 남기며 값이 실려오면
     * 400으로 거절한다(결정 5). EXCHANGE 승인은 클레임 행을 잠가(동시 승인 직렬화·늦은 쪽 422) 교환 옵션을 재검증하고 재고를 예약한다
     * (결정 8·9·10·락 순서 Claim → OrderItem 읽기 → Inventory 최후).
     *
     * @throws ClaimNotFoundException     클레임이 없는 경우
     * @throws ClaimInvalidStateException REQUESTED가 아닌 경우(CLM-4)·교환 옵션 부적합(422)
     * @throws MalformedRequestException  refundAmount가 전달된 경우(400·D-115 폐기)
     * @throws com.zslab.mall.inventory.exception.InventoryInvariantViolationException 교환 옵션 재고 부족(422)
     */
    public void approve(Long claimId, LocalDateTime processedAt, Long refundAmount) {
        if (refundAmount != null) {
            throw new MalformedRequestException("refundAmount는 더 이상 지원하지 않습니다(교환 차액 환불 폐기·D-177).");
        }
        claimAccess.lockOrderOfClaim(claimId);
        Claim claim = claimAccess.findClaim(claimId);
        if (claim.getType() == ClaimType.EXCHANGE) {
            entityManager.refresh(claim, LockModeType.PESSIMISTIC_WRITE);
        }
        claim.approve(processedAt);
        if (claim.getType() == ClaimType.EXCHANGE) {
            claimExchangeService.reserveExchangeStock(claim, processedAt);
        }
        claimRepository.save(claim);
        eventPublisher.publishEvent(new ClaimApproved(
                claim.getId(), claim.getPublicId(), claim.getOrderItemId(),
                claim.getType(), claim.getStatus(), LocalDateTime.now()));
        inboxSignalPublisher.adminChanged();
    }

    /**
     * Claim 거부 도메인 전이 primitive. save 직후 {@link ClaimRejected}를 발행한다(D-29·CLM-2 이력 보존).
     *
     * <p>외부 HTTP 진입점 직접 호출 금지. 외부 액터(Admin) 호출은 wrapper 메서드(rejectByAdmin) 경유 의무(셀러 wrapper는 Track 92에서 제거).
     * 본 primitive는 권한 검증 미수행·도메인 상태 전이 단독 책임이다. 직접 호출은 도메인 내부 호출 또는 통합 테스트
     * 순수 상태 전이 검증에 한정한다.
     *
     * <p>D-92 횡단 원칙 정합: 도메인 상태 전이 메서드는 actor 식별자가 상태 자체에 포함되지 않는 한
     * actor 비의존 시그니처를 우선한다.
     *
     * <p>거부 사유 코드는 필수·메모는 선택이다(Track 80 D-169). 사유·유형 부적합(ALREADY_SHIPPED는 CANCEL 전용)·메모 길이는
     * {@link Claim#reject}가 검증한다(IllegalArgumentException → 400).
     *
     * @throws ClaimNotFoundException     클레임이 없는 경우
     * @throws ClaimInvalidStateException REQUESTED가 아닌 경우(CLM-4)
     * @throws IllegalArgumentException   거부 사유 누락·유형 부적합·메모 500자 초과
     */
    public void reject(Long claimId, ClaimRejectReasonCode reasonCode, String memo, LocalDateTime processedAt) {
        claimAccess.lockOrderOfClaim(claimId);
        applyReject(claimAccess.findClaim(claimId), reasonCode, memo, processedAt);
    }

    /**
     * 이미 로딩된 Claim에 거부 전이를 적용한다(Track 101-A 외부 검토 반영). 호출자가 <b>행 락을 잡고 읽은</b> 엔티티를
     * 그대로 넘길 수 있게 분리했다 — {@link #reject}를 쓰면 같은 트랜잭션에서 클레임을 두 번 읽게 되고, 락 조회가 앞섰더라도
     * 두 번째 읽기가 1차 캐시를 타 판정과 전이가 서로 다른 인스턴스를 보는 것처럼 읽힌다(의도 불명확).
     * 전이·저장·이벤트 발행 순서는 {@link #reject}와 동일하다.
     */
    private void applyReject(Claim claim, ClaimRejectReasonCode reasonCode, String memo, LocalDateTime processedAt) {
        claim.reject(reasonCode, memo, processedAt);
        claimRepository.save(claim);
        eventPublisher.publishEvent(new ClaimRejected(
                claim.getId(), claim.getPublicId(), claim.getOrderItemId(),
                claim.getType(), claim.getStatus(), claim.getRejectReasonCode(), LocalDateTime.now()));
        inboxSignalPublisher.adminChanged();
    }

    /**
     * 구매자 클레임 신청 취소(Track 101-A). 접수(REQUESTED) 상태의 본인 주문 클레임만 취소할 수 있다(소유 기준은 주문 구매자·Track 101-B) — 승인 뒤에는 환불·회수가
     * 이미 움직이기 시작하므로 운영자 판단이 필요하다(422).
     *
     * <p><b>기존 거부 흐름 재사용</b>: 상태 전이·품목 스냅샷 원복·주문 상태 재계산이 관리자 거부와 완전히 같은 일이라
     * {@link #reject}를 그대로 호출한다(사유 {@code BUYER_WITHDRAWN} = "구매자 철회"·기존 enum·DDL 무변경).
     * 따라서 {@code ClaimRejectedHandler}가 품목을 {@code previous_order_item_status}로 되돌리고
     * {@code OrderService.recalculateStatus}로 주문 상태를 다시 계산하는 경로를 그대로 탄다.
     *
     * <p>소유 위반·미존재는 404로 은닉한다(정보 노출 회피·Q8·구매자 조회 규약과 동일).
     *
     * <p><b>동시성(Track 101-A 외부 검토 반영)</b>: 클레임 행을 이 트랜잭션의 첫 읽기부터 잠근다. {@code @Version}만으로는
     * 늦은 쪽이 <b>커밋 시점에</b> 걸러질 뿐이라, 그 전에 두 요청이 모두 "취소 가능"으로 판정하고 각자 {@code ClaimRejected}를
     * 발행해 품목 원복·알림이 두 번 일어날 수 있다. 락으로 직렬화하면 늦은 쪽은 앞선 전이가 커밋된 뒤에 읽어 상태 가드에서 422로 걸린다.
     *
     * @throws ClaimNotFoundException     클레임이 없거나 주문의 구매자가 아닌 경우
     * @throws ClaimInvalidStateException REQUESTED가 아닌 경우(422)
     */
    public void cancelByBuyer(String claimPublicId, Long buyerId, LocalDateTime processedAt) {
        claimAccess.lockOrderOfClaimPublicId(claimPublicId);
        Claim claim = claimAccess.findClaimByPublicIdForUpdate(claimPublicId);
        claimAccess.verifyBuyerOwnership(claim, buyerId, claimPublicId);
        if (claim.getStatus() != ClaimStatus.REQUESTED) {
            throw new ClaimInvalidStateException(
                    "접수 상태의 요청만 취소할 수 있습니다. 이미 처리가 시작된 요청은 고객센터로 문의해 주세요: " + claim.getStatus());
        }
        // 잠긴 엔티티를 그대로 전이시킨다 — id로 다시 읽지 않는다(판정과 전이가 같은 인스턴스를 본다).
        applyReject(claim, ClaimRejectReasonCode.BUYER_WITHDRAWN, null, processedAt);
    }

    /**
     * Admin 액터의 Claim 승인 진입점(Track 10-B·D-93 Q3·Q5·전체 접근).
     *
     * <p>Admin은 전체 Claim 접근 권한을 가지므로 권한 검증 단락이 부재한다(D-93 Q3·stub 단계 한정). cross-tenant 검증
     * 없이 Claim 미존재만 404다(셀러 wrapper·authorizeSellerAccess는 Track 92에서 제거). {@link #approve}
     * primitive에 위임한다(클래스 단위 단일 트랜잭션).
     *
     * <p>D-92 횡단 원칙 재사용 1회차(D-93): 액터별 권한 차이는 wrapper 진입점에서 캡슐화하고 primitive는 actor
     * 비의존 시그니처를 유지한다.
     *
     * @throws ClaimNotFoundException     클레임이 없는 경우
     * @throws ClaimInvalidStateException 상태가 REQUESTED가 아닌 경우(CLM-4)
     */
    public void approveByAdmin(Long claimId, LocalDateTime processedAt, Long refundAmount, AuditContext auditContext) {
        claimAccess.lockOrderOfClaim(claimId);
        Claim claim = claimAccess.findClaim(claimId);
        approveWithRecord(claimId, claim.getStatus(), claimSuggestionService.suggest(claim), processedAt, refundAmount, auditContext);
    }

    /**
     * 일괄 승인 항목 1건(D-250). 이 트랜잭션 안에서 제안을 다시 계산해 승인 제안일 때만 {@link #approveByAdmin}과 같은 전이·기록을 한다 —
     * 화면이 본 제안과 지금 상태가 달라졌으면(재고 소진 등) 승인하지 않는다.
     *
     * @throws ClaimNotFoundException           클레임이 없는 경우
     * @throws ClaimInvalidStateException       REQUESTED가 아닌 경우·교환 옵션 부적합
     * @throws ClaimSuggestionMismatchException 지금 제안이 승인이 아닌 경우
     * @throws com.zslab.mall.inventory.exception.InventoryInvariantViolationException 교환 옵션 재고 부족
     */
    public void approveSuggestedByAdmin(String claimPublicId, LocalDateTime processedAt, AuditContext auditContext) {
        claimAccess.lockOrderOfClaimPublicId(claimPublicId);
        Claim claim = claimRepository.findByPublicId(claimPublicId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: " + claimPublicId));
        if (claim.getStatus() != ClaimStatus.REQUESTED) {
            throw new ClaimInvalidStateException("이미 처리된 클레임입니다: status=" + claim.getStatus());
        }
        ClaimSuggestionResult suggestion = claimSuggestionService.suggest(claim);
        if (suggestion.suggestion() != ClaimSuggestion.APPROVE) {
            throw new ClaimSuggestionMismatchException("승인 제안이 아닙니다: " + suggestion.rule().reason());
        }
        approveWithRecord(claim.getId(), claim.getStatus(), suggestion, processedAt, null, auditContext);
    }

    /** 승인 전이 + 제안 기록 + 감사. 제안은 전이 전 상태(교환 재고 예약 전)로 계산한 값이다. */
    private void approveWithRecord(Long claimId, ClaimStatus before, ClaimSuggestionResult suggestion, LocalDateTime processedAt,
            Long refundAmount, AuditContext auditContext) {
        approve(claimId, processedAt, refundAmount);
        claimSuggestionService.record(claimId, suggestion, ClaimDecision.APPROVE, auditContext.actorUserId(), processedAt);
        recordClaimAudit(auditContext, AuditLogAction.APPROVE, claimId,
                Map.of("status", before.name()),
                Map.of("status", claimAccess.findClaim(claimId).getStatus().name()));
    }

    /**
     * Admin 액터의 Claim 거부 진입점(Track 10-B·D-93 Q3·Q5·전체 접근).
     *
     * <p>Admin은 전체 Claim 접근 권한을 가지므로 권한 검증 단락이 부재한다(D-93 Q3·stub 단계 한정). Claim 미존재만
     * 404다. {@link #reject} primitive에 위임한다(클래스 단위 단일 트랜잭션).
     *
     * <p>D-92 횡단 원칙 재사용 1회차(D-93): 액터별 권한 차이는 wrapper 진입점에서 캡슐화하고 primitive는 actor
     * 비의존 시그니처를 유지한다.
     *
     * @throws ClaimNotFoundException     클레임이 없는 경우
     * @throws ClaimInvalidStateException 상태가 REQUESTED가 아닌 경우(CLM-4)
     */
    public void rejectByAdmin(Long claimId, ClaimRejectReasonCode reasonCode, String memo, LocalDateTime processedAt,
            AuditContext auditContext) {
        claimAccess.lockOrderOfClaim(claimId);
        Claim claim = claimAccess.findClaim(claimId);
        ClaimStatus before = claim.getStatus();
        ClaimSuggestionResult suggestion = claimSuggestionService.suggest(claim);
        reject(claimId, reasonCode, memo, processedAt);
        claimSuggestionService.record(claimId, suggestion, ClaimDecision.REJECT, auditContext.actorUserId(), processedAt);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", claimAccess.findClaim(claimId).getStatus().name());
        after.put("rejectReasonCode", reasonCode.name());
        if (memo != null && !memo.isBlank()) {
            after.put("rejectMemo", memo);
        }
        recordClaimAudit(auditContext, AuditLogAction.REJECT, claimId, Map.of("status", before.name()), after);
    }

    /**
     * 클레임을 종결한다(APPROVED → COMPLETED·CLM-4). 이미 COMPLETED면 멱등 no-op이다(CLM-1).
     *
     * <p>호출 맥락은 {@code ClaimRefundCompletedHandler}(Claim.type=CANCEL·AFTER_COMMIT)다. 처리 시각은 시스템 시각으로 채운다.
     * save 직후 {@link ClaimCompleted}를 발행한다(D-29 save→publish·D-90 Q4). 소비 핸들러 {@code ClaimCompletedHandler}가
     * OrderItem을 CANCEL_REQUESTED → CANCELLED로 종결한다(멱등 no-op 시 미발행).
     *
     * @throws ClaimNotFoundException     클레임이 없는 경우
     * @throws ClaimInvalidStateException APPROVED·COMPLETED가 아닌 상태에서 호출된 경우(CLM-1·CLM-4)
     */
    public void markCompleted(Long claimId) {
        claimAccess.lockOrderOfClaim(claimId);
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: claimId=" + claimId));
        if (claim.getStatus() == ClaimStatus.COMPLETED) {
            log.info("[Claim] markCompleted 멱등 NO-OP(이미 COMPLETED): claimId={}", claimId);
            return;
        }
        claim.markCompleted(LocalDateTime.now());
        claimRepository.save(claim);
        eventPublisher.publishEvent(new ClaimCompleted(
                claim.getId(), claim.getPublicId(), claim.getOrderItemId(),
                claim.getType(), claim.getStatus(), LocalDateTime.now()));
        inboxSignalPublisher.adminChanged();
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
