package com.zslab.mall.seller.service;

import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.enums.OrderStatus;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.seller.enums.SellerTerminationBlockCode;
import com.zslab.mall.seller.exception.SellerActivityInProgressException;
import com.zslab.mall.settlement.enums.SettlementStatus;
import com.zslab.mall.settlement.repository.SettlementRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자 종료(TERMINATED) 가드 — 판정 단일 지점(Track 89-D·D-187·{@code MemberActivityChecker} 셀러 판). 관리자 상세의
 * "종료 가능 여부 미리보기"와 실제 전이가 같은 {@link #evaluate}를 쓰므로 미리보기와 결과가 어긋나지 않는다.
 *
 * <ul>
 *   <li>G1 미지급 정산: {@code settlement.status IN (PENDING, CONFIRMED)}</li>
 *   <li>G2 진행 중 품목: 품목 상태 ∉ 종결 4종 ∧ 주문 상태 ∉ {PAYMENT_EXPIRED, CANCELLED} — 결제 만료 주문의 ORDERED 품목 트랩 제외</li>
 *   <li>G3 활성 클레임: {@code claim.status IN (REQUESTED, APPROVED)}</li>
 * </ul>
 *
 * <p><b>판정 시점(D-187 외부 검토 지적 1 확정)</b>: 가드는 <b>전이 요청 시점(가드 쿼리 실행 시점) 기준 판정</b>이며 그 이후의 유입(주문·클레임·
 * 정산 생성)까지 원자적으로 막지 않는다. 가드 3쿼리는 락 없는 읽기이고 주문·클레임·정산 생성 경로는 셀러 행 락을 공유하지 않는다. 셀러 종료는
 * 운영자가 정산 지급·주문 처리를 사전 조율한 뒤 수행하는 행위이므로, 가드는 "살아있는 셀러를 실수로 종료하는 것"을 막는 장치이지 밀리초 단위
 * 경쟁을 막는 장치가 아니다(주문 생성은 이미 셀러 ACTIVE를 검사해 경쟁 창이 검사~커밋 수십 ms). 원자 보장(셀러 락 공통 규약)은 D-187 §8 이월.
 */
@Component
public class SellerTerminationGuard {

    /** 미지급 = 지급(PAID) 전 상태. */
    private static final Set<SettlementStatus> UNPAID_SETTLEMENT_STATUSES =
            Set.of(SettlementStatus.PENDING, SettlementStatus.CONFIRMED);

    /** 품목 종결 집합({@code OrderItemStatus} 전이 매트릭스의 종결 상태 4종). */
    private static final Set<OrderItemStatus> TERMINAL_ITEM_STATUSES = Set.of(
            OrderItemStatus.CONFIRMED, OrderItemStatus.CANCELLED, OrderItemStatus.RETURNED, OrderItemStatus.EXCHANGED);

    /** 품목 상태와 무관하게 거래가 끝난 주문. PAYMENT_EXPIRED는 품목이 ORDERED로 남는 트랩(정찰 실측)이라 반드시 제외한다. */
    private static final Set<OrderStatus> CLOSED_ORDER_STATUSES = Set.of(OrderStatus.PAYMENT_EXPIRED, OrderStatus.CANCELLED);

    private final SettlementRepository settlementRepository;
    private final OrderItemRepository orderItemRepository;
    private final ClaimRepository claimRepository;

    public SellerTerminationGuard(
            SettlementRepository settlementRepository,
            OrderItemRepository orderItemRepository,
            ClaimRepository claimRepository) {
        this.settlementRepository = settlementRepository;
        this.orderItemRepository = orderItemRepository;
        this.claimRepository = claimRepository;
    }

    /** 차단 사유 목록(건수 &gt; 0인 가드만·G1→G2→G3 순). 비어 있으면 종료 가능. */
    @Transactional(readOnly = true)
    public List<SellerTerminationBlock> evaluate(Long sellerId) {
        List<SellerTerminationBlock> blocks = new ArrayList<>();
        long unpaidSettlements = settlementRepository.countBySellerIdAndStatusIn(sellerId, UNPAID_SETTLEMENT_STATUSES);
        if (unpaidSettlements > 0) {
            blocks.add(new SellerTerminationBlock(SellerTerminationBlockCode.UNPAID_SETTLEMENT, unpaidSettlements));
        }
        long inProgressItems = orderItemRepository.countInProgressBySellerId(
                sellerId, TERMINAL_ITEM_STATUSES, CLOSED_ORDER_STATUSES);
        if (inProgressItems > 0) {
            blocks.add(new SellerTerminationBlock(SellerTerminationBlockCode.ORDER_ITEM_IN_PROGRESS, inProgressItems));
        }
        long activeClaims = claimRepository.countActiveBySellerId(sellerId);
        if (activeClaims > 0) {
            blocks.add(new SellerTerminationBlock(SellerTerminationBlockCode.CLAIM_ACTIVE, activeClaims));
        }
        return blocks;
    }

    /**
     * 종료 가능하지 않으면 예외로 막는다.
     *
     * @throws SellerActivityInProgressException 차단 사유가 1건 이상일 때(409)
     */
    @Transactional(readOnly = true)
    public void requireTerminable(Long sellerId) {
        List<SellerTerminationBlock> blocks = evaluate(sellerId);
        if (!blocks.isEmpty()) {
            throw new SellerActivityInProgressException(
                    "종료할 수 없는 판매자입니다(" + describe(blocks) + "): sellerId=" + sellerId, blocks);
        }
    }

    /** 사람이 읽는 차단 요약(예: "UNPAID_SETTLEMENT 1건·ORDER_ITEM_IN_PROGRESS 6건"). */
    public static String describe(List<SellerTerminationBlock> blocks) {
        return blocks.stream()
                .map(block -> block.code() + " " + block.count() + "건")
                .collect(Collectors.joining("·"));
    }
}
