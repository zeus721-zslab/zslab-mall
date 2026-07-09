package com.zslab.mall.cart.service;

import com.zslab.mall.cart.controller.request.CartCheckoutRequest;
import com.zslab.mall.cart.entity.CartItem;
import com.zslab.mall.cart.exception.EmptyCartCheckoutException;
import com.zslab.mall.cart.repository.CartItemRepository;
import com.zslab.mall.checkout.command.CartCheckoutCommand;
import com.zslab.mall.checkout.command.CartCheckoutItemCommand;
import com.zslab.mall.checkout.repository.OrderIdempotencyKeyRepository;
import com.zslab.mall.checkout.service.CheckoutOutcome;
import com.zslab.mall.checkout.service.CheckoutService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 장바구니 결제 오케스트레이션(Track 41 β·D2). 로그인 buyer의 장바구니 selected=true 품목을 조회해 {@link CartCheckoutCommand}로
 * 조립한 뒤 {@link CheckoutService#checkout}에 위임한다. CheckoutService·OrderService는 수정하지 않으며(cart 미의존 유지·결합 회피),
 * 품목 식별자 해소(내부 id)·멱등(D-52)·결제 initiate는 전적으로 CheckoutService가 담당한다.
 *
 * <p><b>트랜잭션 경계</b>: {@code @Transactional}을 두지 않는다(CheckoutService 정합). CheckoutService가 createOrder(TX1)·
 * initiate(TX2)·멱등성 행을 단계별 독립 커밋으로 관리하므로(D-52), 본 서비스를 외곽 트랜잭션으로 감싸면 그 다단 커밋 설계를
 * 깨뜨린다. selected 조회는 자체 read로 스냅샷을 취하며, 재고 재검증·해소는 CheckoutService가 수행한다.
 *
 * <p><b>빈 주문 선가드(D3)·멱등 인지(Track 66)</b>: selected 품목이 0개면 원칙적으로 {@link EmptyCartCheckoutException}(422)으로
 * 즉시 차단해 OrderService ORD-1(빈 주문 불가) 도달 전 명확한 사유를 반환한다. 단, 결제 완료 시 CartPaymentCompletedHandler
 * (PaymentCompleted 소비·D-75·D-126·Track 67)가 cart를 비우므로, 소진 후 동일 Idempotency-Key 재요청은 selected가 비어 있어도 정상 replay다.
 * 따라서 selected가 비었더라도 해당 (buyerId, key)의 멱등 레코드가 존재하면 throw하지 않고 빈 itemCommands로 위임한다
 * — CheckoutService가 캐시 응답/복구를 items 접근 없이 short-circuit 처리해 멱등 계약(replay→cached 200)을 보존한다.
 */
@Service
@RequiredArgsConstructor
public class CartCheckoutService {

    private final CartItemRepository cartItemRepository;
    private final CheckoutService checkoutService;
    private final OrderIdempotencyKeyRepository idempotencyRepository;

    /**
     * 장바구니 selected 품목으로 신규 주문 + 첫 결제를 시작한다.
     *
     * @param buyerId        인증 컨텍스트에서 해소된 buyer 식별자(Controller 주입)
     * @param idempotencyKey Idempotency-Key 헤더(null 허용·CheckoutService가 D-52 분기)
     * @param request        배송지·결제수단(품목은 서버가 selected 조회)
     * @return CheckoutService 위임 결과(신규 201/멱등 캐시 200)
     * @throws EmptyCartCheckoutException selected 품목이 0개이고 멱등 replay도 아닐 때(422)
     */
    public CheckoutOutcome checkout(Long buyerId, String idempotencyKey, CartCheckoutRequest request) {
        List<CartItem> selectedItems = cartItemRepository.findByUserIdAndSelectedTrue(buyerId);
        if (selectedItems.isEmpty() && !isIdempotentReplay(buyerId, idempotencyKey)) {
            throw new EmptyCartCheckoutException("장바구니에 선택된 품목이 없습니다. 결제할 품목을 선택해 주세요.");
        }
        List<CartCheckoutItemCommand> itemCommands = selectedItems.stream()
                .map(item -> new CartCheckoutItemCommand(item.getVariantId(), item.getQuantity()))
                .toList();
        CartCheckoutCommand command = new CartCheckoutCommand(
                buyerId, idempotencyKey, itemCommands, request.shippingAddress().toCommand(), request.method());
        return checkoutService.checkout(command);
    }

    /**
     * 빈 selected가 멱등 replay(주문 성공 후 cart 소진 완료)인지 판정한다. key가 없거나(매 요청 신규·§8) 멱등 레코드가
     * 없으면 진짜 빈 카트이므로 false. CheckoutService의 {@code findByBuyerIdAndIdempotencyKey}를 read 재사용한다.
     */
    private boolean isIdempotentReplay(Long buyerId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return false;
        }
        return idempotencyRepository.findByBuyerIdAndIdempotencyKey(buyerId, idempotencyKey).isPresent();
    }
}
