package com.zslab.mall.cart.service;

import com.zslab.mall.cart.controller.request.CartItemAddRequest;
import com.zslab.mall.cart.controller.response.CartItemAddResponse;
import com.zslab.mall.cart.entity.CartItem;
import com.zslab.mall.cart.repository.CartItemRepository;
import com.zslab.mall.product.exception.ProductVariantNotFoundException;
import com.zslab.mall.product.repository.ProductVariantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장바구니 담기 Application Service(Track 40·buyer 주도). variant 존재검증 후 (userId, variantId) 단위로 담고,
 * 동일 variant 재담기는 수량을 누적한다(M1α). 트랜잭션 경계는 메서드 단위다.
 *
 * <p><b>수량 누적·동시삽입(R2)</b>: 순차 재담기(상시·더블클릭 포함)는 pre-check {@code findByUserIdAndVariantId}→
 * {@code addQuantity}로 누적한다(M1α 충족). 신규 담기 중 uk_cart_item_user_variant 동시삽입 충돌은 {@code saveAndFlush}가
 * 던지는 {@link DataIntegrityViolationException}을 catch→409({@link OptimisticLockingFailureException}) rethrow로 원자
 * 롤백한다(SellerProvisioningService·ProductRegistrationService house 패턴 정합). catch 후 세션을 재사용해 재조회·누적하는
 * 방식은 flush 실패가 트랜잭션을 rollback-only로 만들어 commit 시 UnexpectedRollbackException이 발생하는 트랩이라 쓰지 않는다.
 * 409를 받은 클라이언트가 재시도하면 pre-check가 잡아 누적으로 수렴한다.
 *
 * <p>variant 존재는 {@code existsById}로만 확인하고(미사용 로드 회피·ProductRegistration 선례), 담기 시점에 재고를
 * 연계하지 않는다(재고 확인·예약은 구매 시점 SoT·Track 40 정찰 §7).
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class CartService {

    private final CartItemRepository cartItemRepository;
    private final ProductVariantRepository productVariantRepository;

    /**
     * 장바구니에 상품 변형을 담는다. 동일 (userId, variantId)가 이미 있으면 수량을 누적(M1α)하고, 없으면 신규 생성한다.
     *
     * @param userId 인증 컨텍스트에서 해소된 buyer 식별자(Controller 주입)
     * @param request 담기 요청(variantId·quantity)
     * @return 담긴 최종 상태(userId·variantId·quantity·selected)
     * @throws ProductVariantNotFoundException variantId에 해당하는 ProductVariant가 없을 때(404·M2α)
     * @throws OptimisticLockingFailureException 신규 담기 중 동시삽입 충돌 시(409·클라 재시도 시 누적 수렴)
     */
    public CartItemAddResponse addItem(Long userId, CartItemAddRequest request) {
        // 1. variant 존재검증(M2α·404). id 확인만 필요하므로 엔티티 로드 없이 existsById(ProductRegistration 선례).
        if (!productVariantRepository.existsById(request.variantId())) {
            throw new ProductVariantNotFoundException(
                    "장바구니 담기 대상 상품 변형이 존재하지 않습니다: variantId=" + request.variantId());
        }

        // 2. M1α 수량 누적: 기존 담김 있으면 누적(동일 TX·dirty checking), 없으면 신규 생성.
        CartItem cartItem = cartItemRepository.findByUserIdAndVariantId(userId, request.variantId())
                .map(existing -> {
                    existing.addQuantity(request.quantity());
                    return existing;
                })
                .orElseGet(() -> insertNew(userId, request));

        return new CartItemAddResponse(
                cartItem.getUserId(), cartItem.getVariantId(), cartItem.getQuantity(), cartItem.getSelected());
    }

    /**
     * 신규 CartItem을 생성·저장한다. uk_cart_item_user_variant 동시삽입 충돌은 catch→409 rethrow로 원자 롤백한다
     * (house 패턴·catch 후 세션 재사용 금지). 던진 예외가 @Transactional 경계를 넘어 롤백한다.
     *
     * @throws OptimisticLockingFailureException 동시삽입 충돌 시(409)
     */
    private CartItem insertNew(Long userId, CartItemAddRequest request) {
        try {
            // saveAndFlush로 uk_cart_item_user_variant 위반을 트랜잭션 내에서 즉시 표면화한다(house 패턴).
            return cartItemRepository.saveAndFlush(
                    CartItem.create(userId, request.variantId(), request.quantity()));
        } catch (DataIntegrityViolationException exception) {
            log.warn("[Cart] 동시 담기 충돌(409·uk_cart_item_user_variant) userId={} variantId={}: {}",
                    userId, request.variantId(), exception.getMostSpecificCause().getMessage());
            throw new OptimisticLockingFailureException(
                    "동시 담기 충돌이 발생했습니다. 다시 시도해 주세요.", exception);
        }
    }
}
