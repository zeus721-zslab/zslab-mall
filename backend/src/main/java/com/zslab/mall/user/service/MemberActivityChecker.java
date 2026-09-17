package com.zslab.mall.user.service;

import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.order.enums.OrderStatus;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.user.exception.MemberActivityInProgressException;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 탈퇴 가드 — 진행 중 주문·클레임 판정 단일 지점(Track 84). 셀프 탈퇴({@link UserService#withdraw})와 관리자 탈퇴가 함께 쓴다.
 *
 * <ul>
 *   <li>진행 중 주문: {@code order.buyer_id = :id AND status NOT IN 종결 집합}</li>
 *   <li>진행 중 클레임: claim → order_item → order.buyer_id 경로·status IN (REQUESTED, APPROVED)
 *       ({@code ClaimRepository.existsActiveByOrderItemId}와 동일 기준)</li>
 * </ul>
 */
@Component
public class MemberActivityChecker {

    /**
     * 주문 종결 집합. {@code AdminOrderQueryService.TERMINAL_STATUSES}(CANCELLED·PAYMENT_EXPIRED)에 구매확정 CONFIRMED와, 일부 취소 +
     * 나머지 CONFIRMED-like일 때만 산출되는 PARTIAL_CANCEL({@code OrderStatusResolver} 규칙 [6])을 더한 것이다. 기존 private 상수는
     * 관리자 액션 판정용이라 건드리지 않고 여기서 별도 정의한다.
     */
    private static final Set<OrderStatus> TERMINAL_ORDER_STATUSES = Set.of(
            OrderStatus.CANCELLED, OrderStatus.PAYMENT_EXPIRED, OrderStatus.CONFIRMED, OrderStatus.PARTIAL_CANCEL);

    private final OrderRepository orderRepository;
    private final ClaimRepository claimRepository;

    public MemberActivityChecker(OrderRepository orderRepository, ClaimRepository claimRepository) {
        this.orderRepository = orderRepository;
        this.claimRepository = claimRepository;
    }

    /**
     * 진행 중 주문·클레임이 있으면 탈퇴를 막는다.
     *
     * @throws MemberActivityInProgressException 진행 중 주문 또는 활성 클레임이 있는 경우(409)
     */
    @Transactional(readOnly = true)
    public void requireNoActivityInProgress(Long userId) {
        if (orderRepository.existsByBuyerIdAndStatusNotIn(userId, TERMINAL_ORDER_STATUSES)) {
            throw new MemberActivityInProgressException("진행 중인 주문이 있어 탈퇴할 수 없습니다: userId=" + userId);
        }
        if (claimRepository.existsActiveByBuyerId(userId)) {
            throw new MemberActivityInProgressException("진행 중인 클레임이 있어 탈퇴할 수 없습니다: userId=" + userId);
        }
    }
}
