package com.zslab.mall.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.cart.controller.request.CartCheckoutRequest;
import com.zslab.mall.cart.entity.CartItem;
import com.zslab.mall.cart.exception.EmptyCartCheckoutException;
import com.zslab.mall.cart.repository.CartItemRepository;
import com.zslab.mall.checkout.command.CartCheckoutCommand;
import com.zslab.mall.checkout.command.CartCheckoutItemCommand;
import com.zslab.mall.checkout.entity.OrderIdempotencyKey;
import com.zslab.mall.checkout.repository.OrderIdempotencyKeyRepository;
import com.zslab.mall.checkout.service.CheckoutOutcome;
import com.zslab.mall.checkout.service.CheckoutService;
import com.zslab.mall.order.controller.request.ShippingAddressRequest;
import com.zslab.mall.order.controller.response.CheckoutResponse;
import com.zslab.mall.order.controller.response.CheckoutResponse.PaymentView;
import com.zslab.mall.order.controller.response.StatusView;
import com.zslab.mall.payment.enums.PaymentMethod;
import com.zslab.mall.payment.enums.PaymentStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link CartCheckoutService} 오케스트레이션 검증(Mockito·Track 41 β). selected 조회 → CartCheckoutCommand 조립 →
 * {@link CheckoutService#checkout} 위임·빈 카트 선가드(D3)를 커버한다. 내부 id 해소·멱등·결제는 CheckoutService 소관이라 mock한다.
 */
@ExtendWith(MockitoExtension.class)
class CartCheckoutServiceTest {

    private static final long BUYER_ID = 7701L;

    private static final String IDEMPOTENCY_KEY = "idem-key-1";

    @Mock
    private CartItemRepository cartItemRepository;
    @Mock
    private CheckoutService checkoutService;
    @Mock
    private OrderIdempotencyKeyRepository idempotencyRepository;

    @InjectMocks
    private CartCheckoutService cartCheckoutService;

    private CartCheckoutRequest request() {
        return new CartCheckoutRequest(
                new ShippingAddressRequest("홍길동", "010-1234-5678", "06236", "서울 강남대로 1", null, null, null),
                PaymentMethod.CARD);
    }

    private CheckoutOutcome outcome() {
        CheckoutResponse response = new CheckoutResponse(
                new PaymentView("pay_CART00000000000000000000AA", StatusView.of(PaymentStatus.PENDING), "https://pg/redirect", null), null);
        return CheckoutOutcome.created(response, "/api/v1/orders/ord_CART0000000000000000000AA");
    }

    private CheckoutOutcome cachedOutcome() {
        CheckoutResponse response = new CheckoutResponse(
                new PaymentView("pay_CART00000000000000000000AA", StatusView.of(PaymentStatus.PENDING), "https://pg/redirect", null), null);
        return CheckoutOutcome.cached(response);
    }

    @Test
    @DisplayName("selected 품목 → CartCheckoutCommand(variantId·quantity) 조립·checkout 위임·결과 반환")
    void checkout_selectedItems_assemblesCommandAndDelegates() {
        when(cartItemRepository.findByUserIdAndSelectedTrue(BUYER_ID)).thenReturn(List.of(
                CartItem.create(BUYER_ID, 201L, "var_chk01234567890123456789012", 2),
                CartItem.create(BUYER_ID, 202L, "var_chk11234567890123456789012", 3)));
        ArgumentCaptor<CartCheckoutCommand> captor = ArgumentCaptor.forClass(CartCheckoutCommand.class);
        when(checkoutService.checkout(captor.capture())).thenReturn(outcome());

        CheckoutOutcome result = cartCheckoutService.checkout(BUYER_ID, "idem-key-1", request());

        assertThat(result.location()).isEqualTo("/api/v1/orders/ord_CART0000000000000000000AA");
        CartCheckoutCommand command = captor.getValue();
        assertThat(command.buyerId()).isEqualTo(BUYER_ID);
        assertThat(command.idempotencyKey()).isEqualTo("idem-key-1");
        assertThat(command.method()).isEqualTo(PaymentMethod.CARD);
        assertThat(command.shipping().recipientName()).isEqualTo("홍길동");
        assertThat(command.items())
                .extracting(CartCheckoutItemCommand::variantId, CartCheckoutItemCommand::quantity)
                .containsExactly(tuple(201L, 2), tuple(202L, 3));
    }

    @Test
    @DisplayName("cached 재반환: selected 있음·checkoutService가 캐시 outcome 반환 → 그대로 위임·반환")
    void checkout_selectedItems_returnsCachedOutcome() {
        when(cartItemRepository.findByUserIdAndSelectedTrue(BUYER_ID)).thenReturn(List.of(
                CartItem.create(BUYER_ID, 201L, "var_chk01234567890123456789012", 2)));
        when(checkoutService.checkout(any())).thenReturn(cachedOutcome());

        CheckoutOutcome result = cartCheckoutService.checkout(BUYER_ID, IDEMPOTENCY_KEY, request());

        assertThat(result.cached()).isTrue();
        assertThat(result.location()).isNull();
        verify(checkoutService).checkout(any());
    }

    @Test
    @DisplayName("genuine empty(key 없음): selected 0개 → EmptyCartCheckoutException(422)·checkout 미호출")
    void checkout_noSelected_noKey_throwsEmpty() {
        when(cartItemRepository.findByUserIdAndSelectedTrue(BUYER_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> cartCheckoutService.checkout(BUYER_ID, null, request()))
                .isInstanceOf(EmptyCartCheckoutException.class);
        verify(checkoutService, never()).checkout(any());
    }

    @Test
    @DisplayName("genuine empty(멱등 레코드 없음): selected 0개·key 있으나 레코드 없음 → EmptyCartCheckoutException·미위임")
    void checkout_noSelected_keyWithoutRecord_throwsEmpty() {
        when(cartItemRepository.findByUserIdAndSelectedTrue(BUYER_ID)).thenReturn(List.of());
        when(idempotencyRepository.findByBuyerIdAndIdempotencyKey(eq(BUYER_ID), eq(IDEMPOTENCY_KEY)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartCheckoutService.checkout(BUYER_ID, IDEMPOTENCY_KEY, request()))
                .isInstanceOf(EmptyCartCheckoutException.class);
        verify(checkoutService, never()).checkout(any());
    }

    @Test
    @DisplayName("replay-after-consume: selected 0개이나 멱등 레코드 존재 → 예외 없음·빈 itemCommands로 checkout 위임")
    void checkout_noSelected_idempotentReplay_delegates() {
        when(cartItemRepository.findByUserIdAndSelectedTrue(BUYER_ID)).thenReturn(List.of());
        when(idempotencyRepository.findByBuyerIdAndIdempotencyKey(eq(BUYER_ID), eq(IDEMPOTENCY_KEY)))
                .thenReturn(Optional.of(mock(OrderIdempotencyKey.class)));
        ArgumentCaptor<CartCheckoutCommand> captor = ArgumentCaptor.forClass(CartCheckoutCommand.class);
        when(checkoutService.checkout(captor.capture())).thenReturn(cachedOutcome());

        CheckoutOutcome result = cartCheckoutService.checkout(BUYER_ID, IDEMPOTENCY_KEY, request());

        assertThat(result.cached()).isTrue();
        assertThat(captor.getValue().items()).isEmpty();
        assertThat(captor.getValue().idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
    }
}
