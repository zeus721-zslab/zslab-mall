package com.zslab.mall.settlement.service;

import com.zslab.mall.settlement.entity.Settlement;
import com.zslab.mall.settlement.enums.SettlementStatus;
import com.zslab.mall.settlement.exception.SettlementInvalidStateException;
import com.zslab.mall.settlement.exception.SettlementNotFoundException;
import com.zslab.mall.settlement.repository.SettlementRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정산 상태 전이 Application Service(Track 49·운영자 주도). PENDING→CONFIRMED(금액 확정)→PAID(지급 완료 수동 마킹)
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
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class SettlementTransitionService {

    private final SettlementRepository settlementRepository;

    /**
     * 정산 금액을 확정한다(PENDING → CONFIRMED). 이미 CONFIRMED이면 멱등 no-op.
     *
     * @param settlementId 전이 대상 정산 id
     * @return 확정된(또는 이미 확정 상태인) Settlement
     * @throws SettlementNotFoundException     정산 미존재(404)
     * @throws SettlementInvalidStateException CONFIRMED 전이가 불가한 상태(예: PAID)인 경우(422)
     */
    public Settlement confirm(Long settlementId) {
        Settlement settlement = settlementRepository.findByIdForUpdate(settlementId)
                .orElseThrow(() -> new SettlementNotFoundException(
                        "정산을 찾을 수 없습니다: settlementId=" + settlementId));

        if (settlement.getStatus() == SettlementStatus.CONFIRMED) {
            log.info("[Settlement] 이미 CONFIRMED → 확정 건너뜀: settlementId={}", settlementId);
            return settlement;
        }

        try {
            settlement.markConfirmed();
        } catch (IllegalStateException exception) {
            throw new SettlementInvalidStateException("확정할 수 없는 정산 상태입니다: " + exception.getMessage());
        }
        return settlement;
    }

    /**
     * 지급 완료를 마킹한다(CONFIRMED → PAID·운영자 수동). 이미 PAID이면 멱등 no-op. 전이 시각(paid_at)은 서비스가 now()로 채운다.
     *
     * @param settlementId 전이 대상 정산 id
     * @return 지급 완료된(또는 이미 지급 상태인) Settlement
     * @throws SettlementNotFoundException     정산 미존재(404)
     * @throws SettlementInvalidStateException PAID 전이가 불가한 상태(예: PENDING)인 경우(422)
     */
    public Settlement pay(Long settlementId) {
        Settlement settlement = settlementRepository.findByIdForUpdate(settlementId)
                .orElseThrow(() -> new SettlementNotFoundException(
                        "정산을 찾을 수 없습니다: settlementId=" + settlementId));

        if (settlement.getStatus() == SettlementStatus.PAID) {
            log.info("[Settlement] 이미 PAID → 지급 건너뜀: settlementId={}", settlementId);
            return settlement;
        }

        try {
            settlement.markPaid(LocalDateTime.now());
        } catch (IllegalStateException exception) {
            throw new SettlementInvalidStateException("지급 처리할 수 없는 정산 상태입니다: " + exception.getMessage());
        }
        return settlement;
    }
}
