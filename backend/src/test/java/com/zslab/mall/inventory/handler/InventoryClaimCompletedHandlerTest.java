package com.zslab.mall.inventory.handler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.event.ClaimCompleted;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.inventory.enums.InventoryHistoryChangeType;
import com.zslab.mall.inventory.repository.InventoryHistoryRepository;
import com.zslab.mall.inventory.service.InventoryService;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.repository.OrderItemRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link InventoryClaimCompletedHandler} 단위 검증(Mockito·D-101 §3·§5·§6 갱신). CANCEL/RETURN → restoreStock·
 * EXCHANGE → exchange(동일 variant)·History 존재 시 멱등 skip을 커버한다.
 */
@ExtendWith(MockitoExtension.class)
class InventoryClaimCompletedHandlerTest {

    private static final Long CLAIM_ID = 700L;
    private static final Long ORDER_ITEM_ID = 501L;
    private static final Long PRODUCT_ID = 11L;
    private static final Long VARIANT_ID = 1L;
    private static final Long SELLER_ID = 21L;
    private static final long UNIT_PRICE = 1_000L;
    private static final int QTY = 2;

    @Mock
    private ClaimRepository claimRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private InventoryService inventoryService;
    @Mock
    private InventoryHistoryRepository inventoryHistoryRepository;
    @InjectMocks
    private InventoryClaimCompletedHandler handler;

    private ClaimCompleted event(ClaimType type) {
        return new ClaimCompleted(CLAIM_ID, "clm_ABC", ORDER_ITEM_ID, type, ClaimStatus.COMPLETED,
                LocalDateTime.of(2026, 7, 1, 10, 0));
    }

    private Claim claim(ClaimType type) {
        Claim claim = Claim.create(ORDER_ITEM_ID, type, "BUYER_CHANGED_MIND", "단위", SELLER_ID,
                LocalDateTime.of(2026, 7, 1, 9, 0), OrderItemStatus.DELIVERED);
        if (type == ClaimType.RETURN) {
            // Track 81-A: RETURN 종결 재고 복구는 검수 PASS·restock=true 전제 — 승인→회수→검수 PASS(restock true)를 도메인 메서드로 재현
            claim.approve(LocalDateTime.of(2026, 7, 1, 10, 0), null);
            claim.confirmPickup(LocalDateTime.of(2026, 7, 2, 9, 0));
            claim.passInspection(true, LocalDateTime.of(2026, 7, 2, 10, 0));
        }
        return claim;
    }

    private Claim returnClaimNoRestock() {
        Claim claim = Claim.create(ORDER_ITEM_ID, ClaimType.RETURN, "PRODUCT_DEFECT", "단위", SELLER_ID,
                LocalDateTime.of(2026, 7, 1, 9, 0), OrderItemStatus.DELIVERED);
        claim.approve(LocalDateTime.of(2026, 7, 1, 10, 0), null);
        claim.confirmPickup(LocalDateTime.of(2026, 7, 2, 9, 0));
        claim.passInspection(false, LocalDateTime.of(2026, 7, 2, 10, 0));
        return claim;
    }

    private OrderItem orderItem() {
        OrderItem item = OrderItem.create(PRODUCT_ID, VARIANT_ID, SELLER_ID, "테스트 상품", QTY, UNIT_PRICE, UNIT_PRICE * QTY);
        ReflectionTestUtils.setField(item, "id", ORDER_ITEM_ID);
        return item;
    }

    private void stubChain(ClaimType type) {
        when(inventoryHistoryRepository.existsByReferenceTypeAndReferenceId("claim", CLAIM_ID)).thenReturn(false);
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim(type)));
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem()));
    }

    @Test
    @DisplayName("CANCEL: restoreStock(variantId, qty, CANCEL, claim, claimId) 호출")
    void handle_cancel_restoresCancel() {
        stubChain(ClaimType.CANCEL);

        handler.handle(event(ClaimType.CANCEL));

        verify(inventoryService).restoreStock(VARIANT_ID, QTY, InventoryHistoryChangeType.CANCEL, "claim", CLAIM_ID);
    }

    @Test
    @DisplayName("RETURN·restock=false(불량 폐기): restoreStock 미호출·재고 불변(Track 81-A D-170)")
    void handle_return_noRestock_skips() {
        when(inventoryHistoryRepository.existsByReferenceTypeAndReferenceId("claim", CLAIM_ID)).thenReturn(false);
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(returnClaimNoRestock()));
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem()));

        handler.handle(event(ClaimType.RETURN));

        verify(inventoryService, org.mockito.Mockito.never()).restoreStock(any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("RETURN·restock=true: restoreStock(variantId, qty, RETURN, claim, claimId) 호출")
    void handle_return_restoresReturn() {
        stubChain(ClaimType.RETURN);

        handler.handle(event(ClaimType.RETURN));

        verify(inventoryService).restoreStock(VARIANT_ID, QTY, InventoryHistoryChangeType.RETURN, "claim", CLAIM_ID);
    }

    @Test
    @DisplayName("EXCHANGE: exchange(variantId, qty, variantId, qty, claimId) 호출(동일 variant)")
    void handle_exchange_callsExchange() {
        stubChain(ClaimType.EXCHANGE);

        handler.handle(event(ClaimType.EXCHANGE));

        verify(inventoryService).exchange(VARIANT_ID, QTY, VARIANT_ID, QTY, CLAIM_ID);
    }

    @Test
    @DisplayName("멱등 skip: History('claim', claimId) 존재 → Claim 재조회·복구·교환 모두 미호출(형제 핸들러 순서 독립)")
    void handle_historyExists_skips() {
        when(inventoryHistoryRepository.existsByReferenceTypeAndReferenceId("claim", CLAIM_ID)).thenReturn(true);

        handler.handle(event(ClaimType.CANCEL));

        verify(claimRepository, never()).findById(anyLong());
        verify(inventoryService, never()).restoreStock(anyLong(), anyInt(), any(), any(), anyLong());
        verify(inventoryService, never()).exchange(anyLong(), anyInt(), anyLong(), anyInt(), anyLong());
    }
}
