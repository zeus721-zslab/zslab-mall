package com.zslab.mall.order.service;

import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.delivery.service.ReturnWindowPolicy;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.repository.OrderItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자동 구매확정 단건 Application Service(Track 81-B D-171·R7). 원 주문 발송 배송완료 후 {@link ReturnWindowPolicy#WINDOW_DAYS}일이 지난
 * DELIVERED 품목을 {@link BuyerOrderConfirmService#confirmItem}(수동 확정과 같은 코어)으로 CONFIRMED 전이한다.
 *
 * <p><b>확정 경합(반품 요청과의 순서)</b>: 품목 행을 {@code refresh(PESSIMISTIC_WRITE)}로 잠근 뒤 (1) DELIVERED (2) 활성 클레임 없음
 * (3) 원 발송 기준 기한 경과를 재확인한다. 반품 요청({@code ClaimService.createClaim})도 같은 행을 잠그고 DELIVERED를 검증하므로 둘 중 먼저
 * 락을 얻은 쪽만 성공한다 — 뒤에 온 자동 확정은 RETURN_REQUESTED를 보고 skip, 뒤에 온 반품 요청은 CONFIRMED를 보고 422.
 *
 * <p><b>단건 트랜잭션 경계</b>: {@link #confirmOne}은 품목 1건당 독립 {@code @Transactional}이다(OrderAutoCancelService 패턴). 배치 조회~
 * 확정 사이의 상태 변화는 재확인에서 흡수한다(skip·debug).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderAutoConfirmService {

    private final OrderItemRepository orderItemRepository;
    private final ClaimRepository claimRepository;
    private final ReturnWindowPolicy returnWindowPolicy;
    private final BuyerOrderConfirmService buyerOrderConfirmService;
    private final EntityManager entityManager;

    /**
     * 품목 1건을 자동 구매확정한다. 행 락 후 3중 재확인에 하나라도 어긋나면 무처리(false)다.
     *
     * @param orderItemId 확정 대상 주문 품목 id
     * @param now         판정 기준 시각
     * @return 전이가 실제로 수행됐으면 true, 재확인 불일치로 skip이면 false
     */
    @Transactional
    public boolean confirmOne(Long orderItemId, LocalDateTime now) {
        Optional<OrderItem> found = orderItemRepository.findById(orderItemId);
        if (found.isEmpty()) {
            log.debug("[OrderAutoConfirm] skip: 품목 없음 orderItemId={}", orderItemId);
            return false;
        }
        OrderItem orderItem = found.get();
        entityManager.refresh(orderItem, LockModeType.PESSIMISTIC_WRITE);

        if (orderItem.getItemStatus() != OrderItemStatus.DELIVERED) {
            log.debug("[OrderAutoConfirm] skip: DELIVERED 아님 orderItemId={} status={}", orderItemId, orderItem.getItemStatus());
            return false;
        }
        if (claimRepository.existsActiveByOrderItemId(orderItemId)) {
            log.debug("[OrderAutoConfirm] skip: 활성 클레임 존재 orderItemId={}", orderItemId);
            return false;
        }
        Optional<LocalDateTime> deliveredAt = returnWindowPolicy.originalDeliveredAt(orderItemId);
        if (deliveredAt.isEmpty() || ReturnWindowPolicy.isWithinWindow(deliveredAt.get(), now)) {
            log.debug("[OrderAutoConfirm] skip: 원 발송 기한 미경과 orderItemId={} deliveredAt={}", orderItemId, deliveredAt.orElse(null));
            return false;
        }

        Long orderId = orderItemRepository.findOrderIdById(orderItemId)
                .orElseThrow(() -> new IllegalStateException("품목의 주문 미존재: orderItemId=" + orderItemId));
        buyerOrderConfirmService.confirmItem(orderItem, orderId);
        log.info("[OrderAutoConfirm] 자동 구매확정 완료 orderItemId={} deliveredAt={}", orderItemId, deliveredAt.get());
        return true;
    }
}
