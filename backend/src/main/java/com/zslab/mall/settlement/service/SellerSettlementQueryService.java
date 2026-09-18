package com.zslab.mall.settlement.service;

import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.settlement.controller.response.SellerSettlementDetailResponse;
import com.zslab.mall.settlement.controller.response.SellerSettlementSummaryResponse;
import com.zslab.mall.settlement.controller.response.SettlementItemResponse;
import com.zslab.mall.settlement.entity.Settlement;
import com.zslab.mall.settlement.enums.SettlementItemType;
import com.zslab.mall.settlement.enums.SettlementStatus;
import com.zslab.mall.settlement.exception.SettlementNotFoundException;
import com.zslab.mall.settlement.repository.SettlementRepository;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 셀러 정산 조회(Track 85·셀러 공개). 본인(sellerId) 정산 중 CONFIRMED·PAID만 노출하며, PENDING·타 셀러·미존재는 모두
 * {@link SettlementNotFoundException}(404)으로 통일한다(존재 은닉·ClaimService 소유 검증 패턴). 계좌번호는 끝 4자리만.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SellerSettlementQueryService {

    static final Set<SettlementStatus> SELLER_VISIBLE_STATUSES = Set.of(SettlementStatus.CONFIRMED, SettlementStatus.PAID);

    private final SettlementRepository settlementRepository;
    private final AdminSettlementQueryService adminSettlementQueryService;

    /** 본인 정산 목록(CONFIRMED·PAID·최신 기간순). */
    public PagedResponse<SellerSettlementSummaryResponse> list(Long sellerId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), AdminSettlementQueryService.clampSize(size),
                Sort.by(Sort.Order.desc("periodStart"), Sort.Order.desc("id")));
        return PagedResponse.from(settlementRepository
                .findBySellerIdAndStatusIn(sellerId, SELLER_VISIBLE_STATUSES, pageable)
                .map(SellerSettlementSummaryResponse::from));
    }

    /**
     * 본인 정산 상세.
     *
     * @throws SettlementNotFoundException 미존재·타 셀러·PENDING(404 통일)
     */
    public SellerSettlementDetailResponse get(Long settlementId, Long sellerId) {
        Settlement settlement = requireVisible(settlementId, sellerId);
        List<Long> ids = List.of(settlementId);
        long saleCount = adminSettlementQueryService.countItems(ids, SettlementItemType.SALE).getOrDefault(settlementId, 0L);
        long refundCount = adminSettlementQueryService.countItems(ids, SettlementItemType.REFUND).getOrDefault(settlementId, 0L);
        return SellerSettlementDetailResponse.of(SellerSettlementSummaryResponse.from(settlement), saleCount, refundCount,
                adminSettlementQueryService.bankAccount(settlement));
    }

    /**
     * 본인 정산 품목 페이지.
     *
     * @throws SettlementNotFoundException 미존재·타 셀러·PENDING(404 통일)
     */
    public PagedResponse<SettlementItemResponse> listItems(Long settlementId, Long sellerId, SettlementItemType type,
            int page, int size) {
        requireVisible(settlementId, sellerId);
        return PagedResponse.from(adminSettlementQueryService.itemPage(settlementId, type, page, size)
                .map(SettlementItemResponse::from));
    }

    private Settlement requireVisible(Long settlementId, Long sellerId) {
        return settlementRepository.findByIdAndSellerIdAndStatusIn(settlementId, sellerId, SELLER_VISIBLE_STATUSES)
                .orElseThrow(() -> new SettlementNotFoundException(
                        "정산을 찾을 수 없습니다: settlementId=" + settlementId + " sellerId=" + sellerId));
    }
}
