package com.zslab.mall.settlement.service;

import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.repository.SettlementSaleSourceProjection;
import com.zslab.mall.refund.enums.RefundStatus;
import com.zslab.mall.refund.repository.RefundRepository;
import com.zslab.mall.refund.repository.SettlementRefundSourceProjection;
import com.zslab.mall.seller.repository.SellerRepository;
import com.zslab.mall.settlement.entity.Settlement;
import com.zslab.mall.settlement.entity.SettlementItem;
import com.zslab.mall.settlement.enums.SettlementStatus;
import com.zslab.mall.settlement.exception.SettlementAlreadyExistsException;
import com.zslab.mall.settlement.exception.SettlementInvalidStateException;
import com.zslab.mall.settlement.exception.SettlementNotFoundException;
import com.zslab.mall.settlement.exception.SettlementPeriodInvalidException;
import com.zslab.mall.settlement.repository.SettlementCarryoverSourceProjection;
import com.zslab.mall.settlement.repository.SettlementItemRepository;
import com.zslab.mall.settlement.repository.SettlementRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 월 정산 생성·재생성 Application Service(Track 48 P3 → Track 85 품목 스냅샷 전환·운영자 주도). 입력 year/month로 정산 기간을 산정하고,
 * 기간 말까지 발생했고 아직 정산되지 않은 seller별 매출 품목(CONFIRMED order_item·confirmed_at 기준)·환불 품목(COMPLETED refund·
 * refunded_at 기준·D-168 가드)을 품목 단위로 조회해 {@code settlement_item} 스냅샷과 Settlement(PENDING) 헤더를 함께 생성한다
 * (Track 104-3b ⑦ — 기간 하한 없음·편입 여부는 settlement_item 전역 출처 키·열린 불일치 주문은 보류 ⑥).
 *
 * <p><b>금액(Track 85 확정)</b>: 품목 수수료 = floor(품목금액 × order_item.commission_rate / 10000)·정산 fee = 품목 수수료 합(합계 버림이
 * 아님). gross = SALE 금액 합·refund = REFUND 금액 합·carryover = CARRYOVER 금액 합(앞선 음수 정산 부족분·Track 104-3b ⑧)·
 * net = gross − fee − refund − carryover(환불 시 수수료 환급 없음·현행 정책 유지). 율은 주문
 * 시점 스냅샷만 쓴다(seller·category 현행율 미참조). 지급예정일 = 기간 말일 + {@code settlement.payout-offset-days}.
 *
 * <p><b>기간 경계</b>: periodStart=1일 00:00:00.000000(헤더 기간 표시·(셀러, 기간) 멱등 키), periodEnd=말일 23:59:59.999999(편입 상한).
 * 앞선 기간의 미편입 사실이 들어오면 품목 occurred_at은 원래 시각 그대로라 periodStart보다 이를 수 있다. 집계 쿼리와 저장(confirmed_at)이 모두
 * {@code LocalDateTime} 바인딩 경로를 거치므로 세션 타임존 오프셋이 양쪽에서 상쇄된다(P2 트랩 대응).
 *
 * <p><b>병합·skip</b>: 매출만·환불만 있는 seller 모두 포함(net 음수 허용·지급 차단은 전이 서비스). Seller 행 부재는 skip+WARN.
 * 주 정산계좌 부재 skip은 Track 85에서 제거했다(계좌는 생성 조건이 아니라 지급 조건·STL-3 스냅샷은 pay 시점).
 *
 * <p><b>멱등·중복</b>: 생성 전 {@code existsBySellerIdAndPeriodStartAndPeriodEnd}로 seller별 skip(재실행 안전). 선확인 통과 후 UNIQUE
 * 위반이 나는 동시 실행 레이스는 {@link SettlementAlreadyExistsException}(409)으로 배치를 롤백한다.
 *
 * <p><b>재생성</b>: PENDING 정산만 비관적 락 하에 품목·헤더를 삭제하고 같은 seller·같은 기간 말까지를 재집계한다(같은 트랜잭션에서 지운
 * 자기 품목은 미편입으로 보여 다시 들어온다). 재집계 대상이 없으면 삭제만 한다. 감사 DELETE(사유 포함)·CREATE를 적재한다.
 */
@Slf4j
@Service
@Transactional
public class SettlementCreationService {

    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 2100;

    private final OrderItemRepository orderItemRepository;
    private final RefundRepository refundRepository;
    private final SellerRepository sellerRepository;
    private final SettlementRepository settlementRepository;
    private final SettlementItemRepository settlementItemRepository;
    private final AuditRecorder auditRecorder;
    private final int payoutOffsetDays;

    public SettlementCreationService(OrderItemRepository orderItemRepository, RefundRepository refundRepository,
            SellerRepository sellerRepository, SettlementRepository settlementRepository,
            SettlementItemRepository settlementItemRepository, AuditRecorder auditRecorder,
            @Value("${settlement.payout-offset-days:20}") int payoutOffsetDays) {
        this.orderItemRepository = orderItemRepository;
        this.refundRepository = refundRepository;
        this.sellerRepository = sellerRepository;
        this.settlementRepository = settlementRepository;
        this.settlementItemRepository = settlementItemRepository;
        this.auditRecorder = auditRecorder;
        this.payoutOffsetDays = payoutOffsetDays;
    }

    /** seller 1건의 편입 대상 소스(매출·환불·음수 정산 이월). */
    private record SellerSources(List<SettlementSaleSourceProjection> sales,
            List<SettlementRefundSourceProjection> refunds, List<SettlementCarryoverSourceProjection> carryovers) {
    }

    /**
     * 지정 월의 seller별 Settlement(+품목 스냅샷)를 생성한다.
     *
     * @param year         정산 연도(2000~2100)
     * @param month        정산 월(1~12)
     * @param auditContext 감사 행위자 컨텍스트(운영자)·건별 CREATE 적재
     * @return 요청 기간과 생성된 Settlement 목록
     * @throws SettlementPeriodInvalidException year/month가 유효 범위 밖이거나 기간이 아직 마감되지 않은(말일 ≥ 오늘) 경우(400)
     * @throws SettlementAlreadyExistsException 선확인 통과 후 UNIQUE 위반(동시 실행 레이스)인 경우(409)
     */
    // REPEATABLE READ(Track 104-1 D-215): 한 정산 안의 매출(SALE)·환불(REFUND) 조회가 같은 스냅샷(첫 조회 시점)을 봐야 한다 — 앱 전역은
    // READ COMMITTED라 두 조회 사이에 커밋된 환불이 환불 쪽에만 섞인다. 호출자(관리자 API·월 스케줄러)는 트랜잭션이 없어 여기가 최외곽이다.
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public SettlementBatchResult createMonthlySettlements(int year, int month, AuditContext auditContext) {
        if (month < 1 || month > 12 || year < MIN_YEAR || year > MAX_YEAR) {
            throw new SettlementPeriodInvalidException(
                    "정산 기간이 유효하지 않습니다. year=" + year + "(2000~2100)·month=" + month + "(1~12).");
        }
        YearMonth yearMonth = YearMonth.of(year, month);
        // 검수 2단계(D-168 보충): 마감 전(진행 중·미래) 기간은 집계가 확정되지 않았으므로 생성을 거부한다.
        // 기준일은 JVM 기본 시간대(운영·로컬 모두 Asia/Seoul)의 오늘이며, 말일이 오늘 이전이어야 마감된 기간이다.
        if (!yearMonth.atEndOfMonth().isBefore(LocalDate.now())) {
            throw new SettlementPeriodInvalidException(
                    "마감되지 않은 정산 기간입니다(진행 중·미래): " + yearMonth + " — 말일이 지난 뒤 생성할 수 있습니다.");
        }
        LocalDateTime periodStart = periodStart(yearMonth);
        LocalDateTime periodEnd = periodEnd(yearMonth);

        Map<Long, SellerSources> sourcesBySeller = collectSources(periodEnd, null);

        List<Settlement> created = new ArrayList<>();
        for (Map.Entry<Long, SellerSources> entry : sourcesBySeller.entrySet()) {
            Long sellerId = entry.getKey();
            if (!sellerRepository.existsById(sellerId)) {
                log.warn("[Settlement] seller 미존재로 정산 skip: sellerId={} sales={} refunds={} carryovers={}",
                        sellerId, entry.getValue().sales().size(), entry.getValue().refunds().size(),
                        entry.getValue().carryovers().size());
                continue;
            }
            if (settlementRepository.existsBySellerIdAndPeriodStartAndPeriodEnd(sellerId, periodStart, periodEnd)) {
                log.info("[Settlement] 이미 생성된 정산 skip(멱등): sellerId={} period={}~{}", sellerId, periodStart, periodEnd);
                continue;
            }
            created.add(createOne(sellerId, periodStart, periodEnd, entry.getValue(), auditContext));
        }
        return new SettlementBatchResult(periodStart, periodEnd, created);
    }

    /**
     * PENDING 정산을 삭제하고 같은 seller·기간을 재집계한다(검수 문제 대응·Track 85).
     *
     * @param settlementId 대상 정산 id
     * @param reason       재생성 사유(감사 DELETE diff에 기록)
     * @param auditContext 감사 행위자 컨텍스트(운영자)
     * @return 삭제된 정산 요약과 재생성된 Settlement(대상 품목이 없으면 null)
     * @throws SettlementNotFoundException     정산 미존재(404)
     * @throws SettlementInvalidStateException PENDING이 아닌 경우(422)
     */
    // REPEATABLE READ(Track 104-1 D-215): createMonthlySettlements와 같은 이유(재집계의 SALE·REFUND 한 스냅샷). 앞선 정산 행 락 조회는
    // 잠금 읽기라 스냅샷을 만들지 않고, 스냅샷은 재집계의 첫 매출 조회 시점에 잡힌다. 호출자(관리자 API)는 트랜잭션이 없어 여기가 최외곽이다.
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public SettlementRegenerateResult regenerate(Long settlementId, String reason, AuditContext auditContext) {
        Settlement existing = settlementRepository.findByIdForUpdate(settlementId)
                .orElseThrow(() -> new SettlementNotFoundException("정산을 찾을 수 없습니다: settlementId=" + settlementId));
        if (existing.getStatus() != SettlementStatus.PENDING) {
            throw new SettlementInvalidStateException(
                    "재생성은 PENDING 정산만 가능합니다: settlementId=" + settlementId + " status=" + existing.getStatus());
        }
        Long sellerId = existing.getSellerId();
        LocalDateTime periodStart = existing.getPeriodStart();
        LocalDateTime periodEnd = existing.getPeriodEnd();

        Map<String, Object> deletedSnapshot = amountSnapshot(existing);
        deletedSnapshot.put("reason", reason);
        settlementItemRepository.deleteBySettlementId(settlementId);
        settlementRepository.delete(existing);
        settlementRepository.flush();
        auditRecorder.record(auditContext, AuditLogAction.DELETE, PolymorphicTargetType.SETTLEMENT, settlementId,
                deletedSnapshot, Map.of());
        log.info("[Settlement] 재생성 — 기존 정산 삭제: settlementId={} sellerId={} reason={}", settlementId, sellerId, reason);

        SellerSources sources = collectSources(periodEnd, sellerId).get(sellerId);
        if (sources == null) {
            log.info("[Settlement] 재생성 — 재집계 대상 없음(삭제만): sellerId={} period={}~{}", sellerId, periodStart, periodEnd);
            return new SettlementRegenerateResult(settlementId, null);
        }
        Settlement regenerated = createOne(sellerId, periodStart, periodEnd, sources, auditContext);
        return new SettlementRegenerateResult(settlementId, regenerated);
    }

    /**
     * 기간 말까지 발생했고 아직 어느 정산에도 편입되지 않은 매출·환불·음수 정산 이월 소스를 seller별로 묶는다(seller_id 오름차순·결정적·
     * Track 104-3b ⑦⑧). 열린 불일치가 있는 주문은 제외된다(⑥). 이월만 있는 셀러도 대상이다. sellerId가 null이면 전 셀러.
     */
    private Map<Long, SellerSources> collectSources(LocalDateTime periodEnd, Long sellerId) {
        List<SettlementSaleSourceProjection> sales = orderItemRepository.findSettlementSaleSources(
                OrderItemStatus.CONFIRMED, periodEnd, sellerId);
        List<SettlementRefundSourceProjection> refunds = refundRepository.findSettlementRefundSources(
                RefundStatus.COMPLETED, periodEnd, sellerId);
        List<SettlementCarryoverSourceProjection> carryovers = settlementRepository.findCarryoverSources(periodEnd, sellerId);

        Set<Long> sellerIds = new TreeSet<>();
        sales.forEach(sale -> sellerIds.add(sale.getSellerId()));
        refunds.forEach(refund -> sellerIds.add(refund.getSellerId()));
        carryovers.forEach(carryover -> sellerIds.add(carryover.getSellerId()));

        Map<Long, SellerSources> bySeller = new LinkedHashMap<>();
        for (Long id : sellerIds) {
            bySeller.put(id, new SellerSources(
                    sales.stream().filter(sale -> sale.getSellerId().equals(id)).toList(),
                    refunds.stream().filter(refund -> refund.getSellerId().equals(id)).toList(),
                    carryovers.stream().filter(carryover -> carryover.getSellerId().equals(id)).toList()));
        }
        return bySeller;
    }

    /** seller 1건의 헤더·품목 스냅샷을 저장하고 CREATE 감사를 적재한다. UNIQUE 위반은 409. */
    private Settlement createOne(Long sellerId, LocalDateTime periodStart, LocalDateTime periodEnd,
            SellerSources sources, AuditContext auditContext) {
        long gross = 0;
        long fee = 0;
        long refund = 0;
        for (SettlementSaleSourceProjection sale : sources.sales()) {
            gross += sale.getAmount();
            fee += SettlementItem.calculateFee(sale.getAmount(), sale.getCommissionRate());
        }
        for (SettlementRefundSourceProjection refundSource : sources.refunds()) {
            refund += refundSource.getAmount();
        }
        long carryover = 0;
        for (SettlementCarryoverSourceProjection carryoverSource : sources.carryovers()) {
            carryover += carryoverAmountOf(carryoverSource);
        }
        LocalDate scheduledPayDate = periodEnd.toLocalDate().plusDays(payoutOffsetDays);

        Settlement settlement = Settlement.create(sellerId, periodStart, periodEnd, gross, fee, refund, carryover,
                scheduledPayDate);
        Settlement saved;
        List<SettlementItem> items;
        try {
            saved = settlementRepository.saveAndFlush(settlement);
            items = buildItems(saved.getId(), sources);
            settlementItemRepository.saveAllAndFlush(items);
        } catch (DataIntegrityViolationException exception) {
            // 선확인 통과 후 UNIQUE 위반 = 동시 실행 레이스 — 헤더 (셀러, 기간) 또는 품목 (유형, 출처) 전역 키(같은 사실을 다른 기간 생성·재생성이
            // 먼저 편입). 배치 롤백·409(멱등 재실행은 선확인·미편입 조회가 흡수).
            throw new SettlementAlreadyExistsException("정산 또는 정산 품목이 이미 존재합니다(동시 실행 레이스): sellerId=" + sellerId
                    + " period=" + periodStart + "~" + periodEnd);
        }

        auditRecorder.record(auditContext, AuditLogAction.CREATE, PolymorphicTargetType.SETTLEMENT, saved.getId(),
                Map.of(), amountSnapshot(saved));
        log.info("[Settlement] 정산 생성: settlementId={} sellerId={} gross={} fee={} refund={} carryover={} net={} items={} "
                + "scheduledPayDate={}", saved.getId(), sellerId, gross, fee, refund, carryover, saved.getNetAmount(), items.size(),
                scheduledPayDate);
        return saved;
    }

    /** 소스 품목을 settlement_item 스냅샷으로 만든다. */
    private static List<SettlementItem> buildItems(Long settlementId, SellerSources sources) {
        List<SettlementItem> items = new ArrayList<>();
        for (SettlementSaleSourceProjection sale : sources.sales()) {
            items.add(SettlementItem.sale(settlementId, sale.getOrderItemId(), sale.getOrderPublicId(),
                    sale.getProductName(), sale.getOptionLabel(), sale.getQuantity(), sale.getAmount(),
                    sale.getCommissionRate(), sale.getConfirmedAt()));
        }
        for (SettlementRefundSourceProjection refundSource : sources.refunds()) {
            items.add(SettlementItem.refund(settlementId, refundSource.getOrderItemId(), refundSource.getRefundId(),
                    refundSource.getOrderPublicId(), refundSource.getProductName(), refundSource.getOptionLabel(),
                    refundSource.getQuantity(), refundSource.getAmount(), refundSource.getCommissionRate(),
                    refundSource.getRefundedAt()));
        }
        for (SettlementCarryoverSourceProjection carryoverSource : sources.carryovers()) {
            items.add(SettlementItem.carryover(settlementId, carryoverSource.getSettlementId(),
                    carryoverAmountOf(carryoverSource), carryoverSource.getPeriodEnd()));
        }
        return items;
    }

    /** 이월 금액 = 원 정산 순지급액의 부족분(−net·양수). 조회가 net &lt; 0만 가져온다. */
    private static long carryoverAmountOf(SettlementCarryoverSourceProjection carryoverSource) {
        return -carryoverSource.getNetAmount();
    }

    /** 감사 diff용 금액·기간 필드맵. */
    private static Map<String, Object> amountSnapshot(Settlement settlement) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sellerId", settlement.getSellerId());
        snapshot.put("periodStart", settlement.getPeriodStart().toString());
        snapshot.put("periodEnd", settlement.getPeriodEnd().toString());
        snapshot.put("grossAmount", settlement.getGrossAmount());
        snapshot.put("feeAmount", settlement.getFeeAmount());
        snapshot.put("refundAmount", settlement.getRefundAmount());
        snapshot.put("carryoverAmount", settlement.getCarryoverAmount());
        snapshot.put("netAmount", settlement.getNetAmount());
        return snapshot;
    }

    static LocalDateTime periodStart(YearMonth yearMonth) {
        return yearMonth.atDay(1).atStartOfDay();
    }

    static LocalDateTime periodEnd(YearMonth yearMonth) {
        return yearMonth.atEndOfMonth().atTime(23, 59, 59, 999_999_000);
    }
}
