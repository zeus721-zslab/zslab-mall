package com.zslab.mall.settlement.service;

import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.common.util.PhoneMasker;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.entity.SellerBankAccount;
import com.zslab.mall.seller.exception.SellerNotFoundException;
import com.zslab.mall.seller.repository.SellerBankAccountRepository;
import com.zslab.mall.seller.repository.SellerRepository;
import com.zslab.mall.settlement.controller.response.AdminSettlementDetailResponse;
import com.zslab.mall.settlement.controller.response.AdminSettlementListResponse;
import com.zslab.mall.settlement.controller.response.AdminSettlementSummaryResponse;
import com.zslab.mall.settlement.controller.response.SettlementBankAccountResponse;
import com.zslab.mall.settlement.controller.response.SettlementItemResponse;
import com.zslab.mall.settlement.controller.response.SettlementMonthlyTotals;
import com.zslab.mall.settlement.controller.response.SettlementSellerContactResponse;
import com.zslab.mall.settlement.controller.response.SettlementSellerRef;
import com.zslab.mall.settlement.entity.Settlement;
import com.zslab.mall.settlement.entity.SettlementItem;
import com.zslab.mall.settlement.enums.SettlementItemType;
import com.zslab.mall.settlement.enums.SettlementStatus;
import com.zslab.mall.settlement.exception.SettlementNotFoundException;
import com.zslab.mall.settlement.exception.SettlementPeriodInvalidException;
import com.zslab.mall.settlement.repository.SettlementItemCountProjection;
import com.zslab.mall.settlement.repository.SettlementItemRepository;
import com.zslab.mall.settlement.repository.SettlementRepository;
import com.zslab.mall.settlement.repository.SettlementSpecifications;
import com.zslab.mall.settlement.repository.SettlementStatusTotalProjection;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 정산 조회(Track 85·{@code AdminMemberQueryService} 패턴). 목록은 Specification 페이지 + 페이지 id 배치 enrich(셀러명·주 계좌
 * 유무·SALE 품목 건수)로 N+1을 피한다. 상세는 셀러 연락처(마스킹)·계좌(지급 스냅샷 우선·없으면 현재 주 계좌·끝 4자리)를 붙인다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdminSettlementQueryService {

    private static final int MAX_KEYWORD_LENGTH = 50;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 2100;
    private static final int EMAIL_VISIBLE_PREFIX = 2;

    private final SettlementRepository settlementRepository;
    private final SettlementItemRepository settlementItemRepository;
    private final SellerRepository sellerRepository;
    private final SellerBankAccountRepository sellerBankAccountRepository;

    /**
     * 월별 정산 목록(year/month 필수·status·keyword=셀러 상호 부분일치). 합계는 해당 월 전체(필터 무관).
     *
     * @throws SettlementPeriodInvalidException year/month 범위 밖(400)
     * @throws MalformedRequestException        keyword가 trim 후 {@value #MAX_KEYWORD_LENGTH}자 초과(400)
     */
    public AdminSettlementListResponse list(int year, int month, SettlementStatus status, String keyword, int page,
            int size) {
        if (month < 1 || month > 12 || year < MIN_YEAR || year > MAX_YEAR) {
            throw new SettlementPeriodInvalidException(
                    "정산 기간이 유효하지 않습니다. year=" + year + "(2000~2100)·month=" + month + "(1~12).");
        }
        YearMonth yearMonth = YearMonth.of(year, month);
        LocalDateTime periodStart = SettlementCreationService.periodStart(yearMonth);
        LocalDateTime periodEnd = SettlementCreationService.periodEnd(yearMonth);

        String normalizedKeyword = normalizeKeyword(keyword);
        List<Long> sellerIds = normalizedKeyword == null ? null
                : sellerRepository.findIdsByCompanyNameLike(toLikePattern(normalizedKeyword));
        Specification<Settlement> specification = Specification
                .where(SettlementSpecifications.period(periodStart, periodEnd))
                .and(SettlementSpecifications.status(status))
                .and(SettlementSpecifications.sellerIdIn(sellerIds));
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size),
                Sort.by(Sort.Order.asc("sellerId"), Sort.Order.asc("id")));
        Page<Settlement> settlementPage = settlementRepository.findAll(specification, pageable);

        List<AdminSettlementSummaryResponse> rows = enrich(settlementPage.getContent());
        Page<AdminSettlementSummaryResponse> rowPage = new PageImpl<>(rows, pageable, settlementPage.getTotalElements());
        return AdminSettlementListResponse.from(PagedResponse.from(rowPage), totals(periodStart, periodEnd));
    }

    /**
     * 정산 상세.
     *
     * @throws SettlementNotFoundException 미존재(404)
     */
    public AdminSettlementDetailResponse get(Long settlementId) {
        Settlement settlement = requireSettlement(settlementId);
        AdminSettlementSummaryResponse summary = enrich(List.of(settlement)).get(0);
        long refundItemCount = countItems(List.of(settlementId), SettlementItemType.REFUND).getOrDefault(settlementId, 0L);
        long carryoverItemCount = countItems(List.of(settlementId), SettlementItemType.CARRYOVER).getOrDefault(settlementId, 0L);
        Seller seller = sellerRepository.findById(settlement.getSellerId()).orElse(null);
        SettlementSellerContactResponse contact = seller == null ? null
                : new SettlementSellerContactResponse(maskEmail(seller.getContactEmail()),
                        PhoneMasker.mask(seller.getContactPhone()));
        return AdminSettlementDetailResponse.of(summary, refundItemCount, carryoverItemCount, contact, bankAccount(settlement));
    }

    /**
     * 정산 품목 페이지(type 선택·occurred_at 오름차순).
     *
     * @throws SettlementNotFoundException 미존재(404)
     */
    public PagedResponse<SettlementItemResponse> listItems(Long settlementId, SettlementItemType type, int page, int size) {
        requireSettlement(settlementId);
        return PagedResponse.from(itemPage(settlementId, type, page, size).map(SettlementItemResponse::from));
    }

    /**
     * 셀러 월별 정산 이력(최신 기간순).
     *
     * @throws SellerNotFoundException 셀러 미존재(404)
     */
    public PagedResponse<AdminSettlementSummaryResponse> listBySeller(String sellerPublicId, int page, int size) {
        Seller seller = sellerRepository.findByPublicId(sellerPublicId)
                .orElseThrow(() -> new SellerNotFoundException("판매자를 찾을 수 없습니다: publicId=" + sellerPublicId));
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size),
                Sort.by(Sort.Order.desc("periodStart"), Sort.Order.desc("id")));
        Page<Settlement> settlementPage = settlementRepository.findBySellerId(seller.getId(), pageable);
        List<AdminSettlementSummaryResponse> rows = enrich(settlementPage.getContent());
        return PagedResponse.from(new PageImpl<>(rows, pageable, settlementPage.getTotalElements()));
    }

    /** 품목 페이지 공용(관리자·셀러). 정렬은 occurred_at·id 오름차순 고정. */
    Page<SettlementItem> itemPage(Long settlementId, SettlementItemType type, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size),
                Sort.by(Sort.Order.asc("occurredAt"), Sort.Order.asc("id")));
        return type == null ? settlementItemRepository.findBySettlementId(settlementId, pageable)
                : settlementItemRepository.findBySettlementIdAndItemType(settlementId, type, pageable);
    }

    /** 계좌 응답: 지급 스냅샷(bank_account_id) 우선·없으면 현재 주 계좌·없으면 null(관리자·셀러 공용). */
    SettlementBankAccountResponse bankAccount(Settlement settlement) {
        if (settlement.getBankAccountId() != null) {
            SellerBankAccount snapshot = sellerBankAccountRepository.findById(settlement.getBankAccountId()).orElse(null);
            if (snapshot != null) {
                return SettlementBankAccountResponse.of(snapshot, true);
            }
        }
        List<SellerBankAccount> primary = sellerBankAccountRepository.findPrimaryBankAccounts(settlement.getSellerId());
        return primary.isEmpty() ? null : SettlementBankAccountResponse.of(primary.get(0), false);
    }

    Map<Long, Long> countItems(List<Long> settlementIds, SettlementItemType type) {
        if (settlementIds.isEmpty()) {
            return Map.of();
        }
        return settlementItemRepository.countByTypeGrouped(settlementIds, type).stream()
                .collect(Collectors.toMap(SettlementItemCountProjection::getSettlementId,
                        SettlementItemCountProjection::getItemCount));
    }

    private Settlement requireSettlement(Long settlementId) {
        return settlementRepository.findById(settlementId)
                .orElseThrow(() -> new SettlementNotFoundException("정산을 찾을 수 없습니다: settlementId=" + settlementId));
    }

    /** 페이지 id 배치 enrich: 셀러(publicId·상호)·주 계좌 유무·SALE 품목 건수 — 각 1쿼리. */
    private List<AdminSettlementSummaryResponse> enrich(List<Settlement> settlements) {
        if (settlements.isEmpty()) {
            return List.of();
        }
        List<Long> settlementIds = settlements.stream().map(Settlement::getId).toList();
        Set<Long> sellerIds = settlements.stream().map(Settlement::getSellerId).collect(Collectors.toSet());
        Map<Long, Seller> sellerById = sellerRepository.findByIdIn(sellerIds).stream()
                .collect(Collectors.toMap(Seller::getId, Function.identity()));
        Set<Long> sellersHavingPrimary = new HashSet<>(sellerBankAccountRepository.findSellerIdsHavingPrimary(sellerIds));
        Map<Long, Long> saleCountBySettlement = countItems(settlementIds, SettlementItemType.SALE);

        return settlements.stream()
                .map(settlement -> {
                    Seller seller = sellerById.get(settlement.getSellerId());
                    SettlementSellerRef ref = seller == null ? new SettlementSellerRef(null, null)
                            : new SettlementSellerRef(seller.getPublicId(), seller.getCompanyName());
                    return AdminSettlementSummaryResponse.of(settlement, ref,
                            sellersHavingPrimary.contains(settlement.getSellerId()),
                            saleCountBySettlement.getOrDefault(settlement.getId(), 0L));
                })
                .toList();
    }

    private SettlementMonthlyTotals totals(LocalDateTime periodStart, LocalDateTime periodEnd) {
        long gross = 0;
        long fee = 0;
        long refund = 0;
        long carryover = 0;
        long net = 0;
        long pending = 0;
        long confirmed = 0;
        long paid = 0;
        for (SettlementStatusTotalProjection row : settlementRepository.sumByStatusForPeriod(periodStart, periodEnd)) {
            gross += row.getGrossAmount();
            fee += row.getFeeAmount();
            refund += row.getRefundAmount();
            carryover += row.getCarryoverAmount();
            net += row.getNetAmount();
            switch (row.getStatus()) {
                case PENDING -> pending = row.getSettlementCount();
                case CONFIRMED -> confirmed = row.getSettlementCount();
                case PAID -> paid = row.getSettlementCount();
            }
        }
        return new SettlementMonthlyTotals(gross, fee, refund, carryover, net, pending, confirmed, paid);
    }

    private static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_KEYWORD_LENGTH) {
            throw new MalformedRequestException("keyword는 " + MAX_KEYWORD_LENGTH + "자 이하여야 합니다.");
        }
        return trimmed;
    }

    /** LIKE 와일드카드(%·_·\)를 이스케이프한 부분일치 패턴. */
    private static String toLikePattern(String keyword) {
        String escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    /** 이메일 마스킹: 로컬파트 앞 2자만 노출("ab***@domain")·짧으면 전부 마스킹. */
    static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String visible = local.length() > EMAIL_VISIBLE_PREFIX ? local.substring(0, EMAIL_VISIBLE_PREFIX) : "";
        return visible + "***" + email.substring(at);
    }

    static int clampSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
