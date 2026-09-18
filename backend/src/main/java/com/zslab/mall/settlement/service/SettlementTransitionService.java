package com.zslab.mall.settlement.service;

import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.common.observability.TracedEventPublisher;
import com.zslab.mall.seller.repository.SellerBankAccountRepository;
import com.zslab.mall.settlement.entity.Settlement;
import com.zslab.mall.settlement.enums.SettlementStatus;
import com.zslab.mall.settlement.event.SettlementConfirmed;
import com.zslab.mall.settlement.exception.SettlementBankAccountMissingException;
import com.zslab.mall.settlement.exception.SettlementInvalidStateException;
import com.zslab.mall.settlement.exception.SettlementNegativeNetException;
import com.zslab.mall.settlement.exception.SettlementNotFoundException;
import com.zslab.mall.settlement.repository.SettlementRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정산 상태 전이 Application Service(Track 49·운영자 주도·Track 85 보강). PENDING→CONFIRMED(정상처리·셀러 공개)→PAID(지급 완료 수동 마킹)
 * 순방향 전이만 처리한다. 권한은 SecurityConfig {@code /api/v1/admin/**}→{@code hasRole("ADMIN")}가 강제하므로
 * (state-machine.md §9·운영자 전용·판매자 자기전이 없음) 서비스는 소유권 대조 없이 전이 로직만 갖는다.
 *
 * <p><b>동시성(비관적 락)</b>: 전이 대상을 {@link SettlementRepository#findByIdForUpdate}(SELECT ... FOR UPDATE)로 조회해
 * 행 단위로 직렬화한다({@code InventoryService} D-101 house pattern 준용). 동시 전이 시 후행 트랜잭션은 락 해제 후
 * 갱신된 상태를 재조회하므로 멱등 no-op 또는 {@link SettlementStatus#canTransitionTo} 가드로 안전 종료한다.
 *
 * <p><b>멱등</b>: 이미 목표 상태이면 no-op으로 반환한다(재요청 안전·BuyerOrderConfirmService 패턴). 그 외 비합법 전이는
 * Aggregate mutator({@link Settlement#markConfirmed}·{@link Settlement#markPaid})가 {@link IllegalStateException}을 던지며,
 * 이를 {@link SettlementInvalidStateException}(422)으로 흡수한다 — 직접 IllegalStateException 매핑은 500 fallback으로 새므로 금지한다.
 *
 * <p><b>Track 85</b>: confirm은 커밋 후 {@link SettlementConfirmed} 이벤트로 셀러 SMS를 적재한다(AFTER_COMMIT 핸들러·발송 실패는
 * 전이에 무영향). pay는 net 음수(422)·주 정산계좌 부재(422)를 차단하고 지급 시점 주 계좌 id를 스냅샷한다(STL-3).
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class SettlementTransitionService {

    private final SettlementRepository settlementRepository;
    private final SellerBankAccountRepository sellerBankAccountRepository;
    private final AuditRecorder auditRecorder;
    private final TracedEventPublisher eventPublisher;

    /**
     * 정산을 정상처리한다(PENDING → CONFIRMED·셀러 공개). 이미 CONFIRMED이면 멱등 no-op(감사·이벤트 미발행). 실제 전이 시 status
     * 변경을 같은 트랜잭션에서 감사 로그로 적재하고(STL-4) {@link SettlementConfirmed}를 발행한다.
     *
     * @param settlementId 전이 대상 정산 id
     * @param auditContext 감사 행위자 컨텍스트(운영자)
     * @return 확정된(또는 이미 확정 상태인) Settlement
     * @throws SettlementNotFoundException     정산 미존재(404)
     * @throws SettlementInvalidStateException CONFIRMED 전이가 불가한 상태(예: PAID)인 경우(422)
     */
    public Settlement confirm(Long settlementId, AuditContext auditContext) {
        Settlement settlement = settlementRepository.findByIdForUpdate(settlementId)
                .orElseThrow(() -> new SettlementNotFoundException(
                        "정산을 찾을 수 없습니다: settlementId=" + settlementId));

        if (settlement.getStatus() == SettlementStatus.CONFIRMED) {
            log.info("[Settlement] 이미 CONFIRMED → 확정 건너뜀: settlementId={}", settlementId);
            return settlement;
        }

        SettlementStatus before = settlement.getStatus();
        try {
            settlement.markConfirmed();
        } catch (IllegalStateException exception) {
            throw new SettlementInvalidStateException("확정할 수 없는 정산 상태입니다: " + exception.getMessage());
        }
        auditRecorder.record(auditContext, AuditLogAction.UPDATE, PolymorphicTargetType.SETTLEMENT, settlement.getId(),
                Map.of("status", before.name()), Map.of("status", settlement.getStatus().name()));
        eventPublisher.publishEvent(new SettlementConfirmed(settlement.getId(), settlement.getSellerId(),
                settlement.getPeriodStart(), settlement.getPeriodEnd(), settlement.getNetAmount(),
                settlement.getScheduledPayDate(), LocalDateTime.now()));
        return settlement;
    }

    /**
     * 지급 완료를 마킹한다(CONFIRMED → PAID·운영자 수동). 이미 PAID이면 멱등 no-op. 전이 시각(paid_at)은 서비스가 now()로 채우고
     * 지급 계좌는 셀러의 현재 주 정산계좌를 스냅샷한다.
     *
     * @param settlementId 전이 대상 정산 id
     * @param auditContext 감사 행위자 컨텍스트(운영자)
     * @return 지급 완료된(또는 이미 지급 상태인) Settlement
     * @throws SettlementNotFoundException           정산 미존재(404)
     * @throws SettlementInvalidStateException       PAID 전이가 불가한 상태(예: PENDING)인 경우(422)
     * @throws SettlementNegativeNetException        net 음수(차감 이월 정책 미도입·422)
     * @throws SettlementBankAccountMissingException 주 정산계좌 부재(422)
     */
    public Settlement pay(Long settlementId, AuditContext auditContext) {
        Settlement settlement = settlementRepository.findByIdForUpdate(settlementId)
                .orElseThrow(() -> new SettlementNotFoundException(
                        "정산을 찾을 수 없습니다: settlementId=" + settlementId));

        if (settlement.getStatus() == SettlementStatus.PAID) {
            log.info("[Settlement] 이미 PAID → 지급 건너뜀: settlementId={}", settlementId);
            return settlement;
        }
        if (!settlement.getStatus().canTransitionTo(SettlementStatus.PAID)) {
            throw new SettlementInvalidStateException(
                    "지급 처리할 수 없는 정산 상태입니다: 불법 정산 상태 전이: " + settlement.getStatus() + " → " + SettlementStatus.PAID);
        }
        if (settlement.getNetAmount() < 0) {
            throw new SettlementNegativeNetException(
                    "정산액이 음수라 지급할 수 없습니다(차감 이월 필요): settlementId=" + settlementId
                            + " net=" + settlement.getNetAmount());
        }
        List<Long> primaryAccountIds = sellerBankAccountRepository.findPrimaryBankAccountIds(settlement.getSellerId());
        if (primaryAccountIds.isEmpty()) {
            throw new SettlementBankAccountMissingException(
                    "주 정산계좌가 없어 지급할 수 없습니다: settlementId=" + settlementId + " sellerId=" + settlement.getSellerId());
        }
        if (primaryAccountIds.size() > 1) {
            // SLR-3 위반 데이터(주 계좌 2건 이상)·id 최소 건으로 지급하되 운영자가 정리하도록 남긴다
            log.warn("주 정산계좌가 {}건입니다(SLR-3 위반·id 최소 {} 사용): sellerId={} settlementId={}",
                    primaryAccountIds.size(), primaryAccountIds.get(0), settlement.getSellerId(), settlementId);
        }

        SettlementStatus before = settlement.getStatus();
        try {
            settlement.markPaid(LocalDateTime.now(), primaryAccountIds.get(0));
        } catch (IllegalStateException exception) {
            throw new SettlementInvalidStateException("지급 처리할 수 없는 정산 상태입니다: " + exception.getMessage());
        }
        auditRecorder.record(auditContext, AuditLogAction.UPDATE, PolymorphicTargetType.SETTLEMENT, settlement.getId(),
                Map.of("status", before.name()),
                Map.of("status", settlement.getStatus().name(), "bankAccountId", settlement.getBankAccountId()));
        return settlement;
    }
}
