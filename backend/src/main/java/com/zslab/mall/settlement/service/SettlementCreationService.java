package com.zslab.mall.settlement.service;

import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.repository.SellerGrossProjection;
import com.zslab.mall.refund.enums.RefundStatus;
import com.zslab.mall.refund.repository.RefundRepository;
import com.zslab.mall.refund.repository.SellerRefundProjection;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.repository.SellerBankAccountRepository;
import com.zslab.mall.seller.repository.SellerRepository;
import com.zslab.mall.settlement.entity.Settlement;
import com.zslab.mall.settlement.exception.SettlementAlreadyExistsException;
import com.zslab.mall.settlement.exception.SettlementPeriodInvalidException;
import com.zslab.mall.settlement.repository.SettlementRepository;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 월 정산 배치 Application Service(Track 48 P3·운영자 주도). 입력 year/month로 정산 기간을 산정하고, 해당 월의 seller별
 * 매출(CONFIRMED order_item)·환불(COMPLETED refund)을 집계·병합해 Settlement(PENDING)를 생성한다.
 *
 * <p><b>기간 경계</b>: periodStart=1일 00:00:00.000000, periodEnd=말일 23:59:59.999999. 집계 쿼리(P2)와 저장(confirmed_at)이
 * 모두 {@code LocalDateTime} 바인딩 경로를 거치므로 세션 타임존 오프셋이 양쪽에서 상쇄된다(P2 트랩 대응).
 *
 * <p><b>fee 계산</b>: {@code fee = gross × commissionRate / 10000}(basis-point·정수 나눗셈 버림). commissionRate는 정산 시점
 * seller 율을 Settlement에 스냅샷한다(사후 seller 율 변경과 무관한 재현성).
 *
 * <p><b>병합 정책</b>: gross·refund 두 집계의 seller_id 합집합을 순회한다. 매출만·환불만 있는 seller 모두 포함하며
 * (net 음수 허용), gross·refund 둘 다 0인 조합은 합집합 특성상 발생하지 않는다.
 *
 * <p><b>멱등·중복</b>: 생성 전 {@code existsBySellerIdAndPeriodStartAndPeriodEnd}로 seller별 skip(재실행 안전)한다.
 * 선확인 통과 후 UNIQUE 위반이 나는 동시 실행 레이스는 {@link SettlementAlreadyExistsException}(409)으로 배치를 롤백한다.
 *
 * <p><b>skip</b>: 주 정산계좌(is_primary)가 없는 seller는 Settlement.bankAccountId(NOT NULL)를 채울 수 없어 skip+WARN한다
 * (배치 무중단·해당 seller 매출은 로그로 가시화). Seller 행 자체가 없는 경우(FK상 비정상)도 skip+WARN.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class SettlementCreationService {

    private static final int BASIS_POINT_DENOMINATOR = 10_000;
    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 2100;

    private final OrderItemRepository orderItemRepository;
    private final RefundRepository refundRepository;
    private final SellerRepository sellerRepository;
    private final SellerBankAccountRepository sellerBankAccountRepository;
    private final SettlementRepository settlementRepository;

    /**
     * 지정 월의 seller별 Settlement를 생성한다.
     *
     * @param year  정산 연도(2000~2100)
     * @param month 정산 월(1~12)
     * @return 요청 기간과 생성된 Settlement 목록
     * @throws SettlementPeriodInvalidException year/month가 유효 범위 밖인 경우(400)
     * @throws SettlementAlreadyExistsException 선확인 통과 후 UNIQUE 위반(동시 실행 레이스)인 경우(409)
     */
    public SettlementBatchResult createMonthlySettlements(int year, int month) {
        if (month < 1 || month > 12 || year < MIN_YEAR || year > MAX_YEAR) {
            throw new SettlementPeriodInvalidException(
                    "정산 기간이 유효하지 않습니다. year=" + year + "(2000~2100)·month=" + month + "(1~12).");
        }
        YearMonth yearMonth = YearMonth.of(year, month);
        LocalDateTime periodStart = yearMonth.atDay(1).atStartOfDay();
        LocalDateTime periodEnd = yearMonth.atEndOfMonth().atTime(23, 59, 59, 999_999_000);

        Map<Long, Long> grossBySeller = orderItemRepository
                .aggregateGrossBySeller(OrderItemStatus.CONFIRMED, periodStart, periodEnd).stream()
                .collect(Collectors.toMap(SellerGrossProjection::getSellerId, SellerGrossProjection::getGrossAmount));
        Map<Long, Long> refundBySeller = refundRepository
                .aggregateRefundBySeller(RefundStatus.COMPLETED, periodStart, periodEnd).stream()
                .collect(Collectors.toMap(SellerRefundProjection::getSellerId, SellerRefundProjection::getRefundAmount));

        // 매출만·환불만 있는 seller 모두 포함하는 합집합. TreeSet으로 seller_id 순서를 결정적으로 고정한다.
        Set<Long> sellerIds = new TreeSet<>();
        sellerIds.addAll(grossBySeller.keySet());
        sellerIds.addAll(refundBySeller.keySet());

        List<Settlement> created = new ArrayList<>();
        for (Long sellerId : sellerIds) {
            long gross = grossBySeller.getOrDefault(sellerId, 0L);
            long refund = refundBySeller.getOrDefault(sellerId, 0L);

            Optional<Seller> seller = sellerRepository.findById(sellerId);
            if (seller.isEmpty()) {
                log.warn("[Settlement] seller 미존재로 정산 skip: sellerId={} gross={} refund={}", sellerId, gross, refund);
                continue;
            }
            List<Long> primaryAccountIds = sellerBankAccountRepository.findPrimaryBankAccountIds(sellerId);
            if (primaryAccountIds.isEmpty()) {
                log.warn("[Settlement] 주 정산계좌 부재로 정산 skip: sellerId={} gross={} refund={}", sellerId, gross, refund);
                continue;
            }
            if (settlementRepository.existsBySellerIdAndPeriodStartAndPeriodEnd(sellerId, periodStart, periodEnd)) {
                log.info("[Settlement] 이미 생성된 정산 skip(멱등): sellerId={} period={}~{}", sellerId, periodStart, periodEnd);
                continue;
            }

            int commissionRate = seller.get().getCommissionRate();
            long fee = gross * (long) commissionRate / BASIS_POINT_DENOMINATOR;
            Long bankAccountId = primaryAccountIds.get(0);

            Settlement settlement = Settlement.create(
                    sellerId, bankAccountId, periodStart, periodEnd, gross, fee, commissionRate, refund);
            try {
                created.add(settlementRepository.saveAndFlush(settlement));
            } catch (DataIntegrityViolationException exception) {
                // 선확인 통과 후 UNIQUE 위반 = 동시 배치 실행 레이스. 배치 롤백·409(멱등 재실행은 선확인이 흡수).
                throw new SettlementAlreadyExistsException(
                        "정산이 이미 존재합니다(동시 실행 레이스): sellerId=" + sellerId + " period=" + periodStart + "~" + periodEnd);
            }
            log.info("[Settlement] 정산 생성: sellerId={} gross={} fee={} refund={} net={}",
                    sellerId, gross, fee, refund, settlement.getNetAmount());
        }
        return new SettlementBatchResult(periodStart, periodEnd, created);
    }
}
