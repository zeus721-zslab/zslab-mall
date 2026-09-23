package com.zslab.mall.user.service;

import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.enums.OrderStatus;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.user.exception.MemberActivityInProgressException;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 탈퇴 가드 — 진행 중 주문·클레임 판정 단일 지점(Track 84). 셀프 탈퇴({@link UserService#withdraw})와 관리자 탈퇴가 함께 쓴다.
 *
 * <ul>
 *   <li>진행 중 주문(결제 전): {@code order.buyer_id = :id AND status = PENDING_PAYMENT}</li>
 *   <li>진행 중 주문(결제 후): 구매자 주문의 품목 중 종결 4종·결제 전(ORDERED)이 아닌 품목 존재(Track 104-4·P4)</li>
 *   <li>진행 중 클레임: claim → order_item → order.buyer_id 경로·status IN (REQUESTED, APPROVED)
 *       ({@code ClaimRepository.existsActiveByOrderItemId}와 동일 기준)</li>
 * </ul>
 */
@Component
public class MemberActivityChecker {

    /**
     * 진행 중이 아닌 품목 상태. 종결 4종(CONFIRMED·CANCELLED·RETURNED·EXCHANGED)은 {@code OrderStatusResolver} 규칙 [5]~[7]의 입력과
     * 같아 옛 주문 종결 집합(CANCELLED·PARTIAL_CANCEL·CONFIRMED)과 같은 판정이다. ORDERED는 결제 전 주문(PENDING_PAYMENT·
     * PAYMENT_EXPIRED)에만 있어 품목으로 두 상태를 가를 수 없으므로 제외하고, 결제 전 단계는 주문 상태로 따로 본다.
     */
    private static final Set<OrderItemStatus> NOT_IN_PROGRESS_ITEM_STATUSES = Set.of(
            OrderItemStatus.ORDERED, OrderItemStatus.CONFIRMED, OrderItemStatus.CANCELLED,
            OrderItemStatus.RETURNED, OrderItemStatus.EXCHANGED);

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ClaimRepository claimRepository;

    public MemberActivityChecker(
            OrderRepository orderRepository, OrderItemRepository orderItemRepository, ClaimRepository claimRepository) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.claimRepository = claimRepository;
    }

    /**
     * 진행 중 주문·클레임이 있으면 탈퇴를 막는다.
     *
     * @throws MemberActivityInProgressException 진행 중 주문 또는 활성 클레임이 있는 경우(409)
     */
    @Transactional(readOnly = true)
    public void requireNoActivityInProgress(Long userId) {
        // 결제 전 단계 — Order.status가 원천 상태
        boolean unpaidOrderInProgress = orderRepository.existsByBuyerIdAndStatus(userId, OrderStatus.PENDING_PAYMENT);
        if (unpaidOrderInProgress
                || orderItemRepository.existsByOrderBuyerIdAndItemStatusNotIn(userId, NOT_IN_PROGRESS_ITEM_STATUSES)) {
            throw new MemberActivityInProgressException("진행 중인 주문이 있어 탈퇴할 수 없습니다: userId=" + userId);
        }
        if (claimRepository.existsActiveByBuyerId(userId)) {
            throw new MemberActivityInProgressException("진행 중인 클레임이 있어 탈퇴할 수 없습니다: userId=" + userId);
        }
    }
}
