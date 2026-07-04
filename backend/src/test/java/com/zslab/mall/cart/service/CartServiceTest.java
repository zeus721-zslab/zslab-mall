package com.zslab.mall.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zslab.mall.cart.controller.request.CartItemAddRequest;
import com.zslab.mall.cart.controller.response.CartItemAddResponse;
import com.zslab.mall.cart.entity.CartItem;
import com.zslab.mall.cart.repository.CartItemRepository;
import com.zslab.mall.product.exception.ProductVariantNotFoundException;
import com.zslab.mall.product.repository.ProductVariantRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * {@link CartService} 오케스트레이션 검증(Mockito). variant 404·수량 누적(save 없음)·신규 저장·동시삽입 409(R2).
 *
 * <p>동시삽입 race(uk_cart_item_user_variant)는 순차로 재현 불가하므로, mock repository의 saveAndFlush가
 * {@link DataIntegrityViolationException}을 던지도록 강제해 catch→{@link OptimisticLockingFailureException}(409) rethrow
 * house 패턴 경로를 명시 커버한다(실 경합 재현은 불요·Track 40 STEP9 보강).
 */
@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    private static final long USER_ID = 8801L;
    private static final long VARIANT_ID = 8802L;
    private static final long MISSING_VARIANT_ID = 8899L;

    @Mock
    private CartItemRepository cartItemRepository;
    @Mock
    private ProductVariantRepository productVariantRepository;

    @InjectMocks
    private CartService cartService;

    @Test
    @DisplayName("variant 미존재: existsById false → ProductVariantNotFoundException·cartItemRepository 미접근")
    void addItem_variantNotFound_throws404() {
        when(productVariantRepository.existsById(MISSING_VARIANT_ID)).thenReturn(false);

        assertThatThrownBy(() -> cartService.addItem(USER_ID, new CartItemAddRequest(MISSING_VARIANT_ID, 1)))
                .isInstanceOf(ProductVariantNotFoundException.class);

        verifyNoInteractions(cartItemRepository);
    }

    @Test
    @DisplayName("동일 variant 재담기: 기존 present → addQuantity 누적·saveAndFlush 미호출(M1α)")
    void addItem_existing_accumulatesWithoutInsert() {
        CartItem existing = CartItem.create(USER_ID, VARIANT_ID, 2);
        when(productVariantRepository.existsById(VARIANT_ID)).thenReturn(true);
        when(cartItemRepository.findByUserIdAndVariantId(USER_ID, VARIANT_ID)).thenReturn(Optional.of(existing));

        CartItemAddResponse response = cartService.addItem(USER_ID, new CartItemAddRequest(VARIANT_ID, 3));

        assertThat(response.quantity()).isEqualTo(5); // 2 + 3
        assertThat(response.selected()).isTrue();
        verify(cartItemRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("신규 담기: 기존 empty → saveAndFlush로 신규 저장·quantity/selected 반영")
    void addItem_new_savesViaSaveAndFlush() {
        when(productVariantRepository.existsById(VARIANT_ID)).thenReturn(true);
        when(cartItemRepository.findByUserIdAndVariantId(USER_ID, VARIANT_ID)).thenReturn(Optional.empty());
        when(cartItemRepository.saveAndFlush(any(CartItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CartItemAddResponse response = cartService.addItem(USER_ID, new CartItemAddRequest(VARIANT_ID, 4));

        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.variantId()).isEqualTo(VARIANT_ID);
        assertThat(response.quantity()).isEqualTo(4);
        assertThat(response.selected()).isTrue();
        verify(cartItemRepository).saveAndFlush(any(CartItem.class));
    }

    @Test
    @DisplayName("동시삽입 race: saveAndFlush가 DataIntegrityViolation → OptimisticLockingFailureException(409) rethrow")
    void addItem_concurrentInsert_throwsOptimisticLockingFailure() {
        when(productVariantRepository.existsById(VARIANT_ID)).thenReturn(true);
        when(cartItemRepository.findByUserIdAndVariantId(USER_ID, VARIANT_ID)).thenReturn(Optional.empty());
        when(cartItemRepository.saveAndFlush(any(CartItem.class)))
                .thenThrow(new DataIntegrityViolationException("uk_cart_item_user_variant"));

        assertThatThrownBy(() -> cartService.addItem(USER_ID, new CartItemAddRequest(VARIANT_ID, 1)))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }
}
