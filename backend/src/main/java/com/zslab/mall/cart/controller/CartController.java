package com.zslab.mall.cart.controller;

import com.zslab.mall.cart.controller.request.CartItemAddRequest;
import com.zslab.mall.cart.controller.request.CartItemDeleteRequest;
import com.zslab.mall.cart.controller.request.CartItemQuantityUpdateRequest;
import com.zslab.mall.cart.controller.request.CartItemSelectRequest;
import com.zslab.mall.cart.controller.request.CartSelectAllRequest;
import com.zslab.mall.cart.controller.response.CartItemAddResponse;
import com.zslab.mall.cart.controller.response.CartResponse;
import com.zslab.mall.cart.service.CartService;
import com.zslab.mall.common.auth.BuyerActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Buyer 장바구니 REST 컨트롤러(Track 40·buyer 주도). 담기 1 endpoint를 노출한다. 클래스 base path 없이 메서드 절대경로를
 * 부여한다(ProductRegistrationController 선례). 인가는 SecurityConfig {@code /api/v1/cart/**}→{@code hasRole("BUYER")}가
 * 강제하며(orders/claims 대칭), userId는 {@link BuyerActorResolver}가 SecurityContext에서 해소한다(미인증 401·비-BUYER 403·필터 계층).
 *
 * <p>HTTP 책임만 가진다(D-40): 액터 해소·요청 검증(@Valid)·Service 위임·201 변환. variant 존재검증·수량 누적·409 변환은
 * {@link CartService} 책임이다. cart_item은 public_id·단건 GET 부재라 Location 없이 201+body만 반환한다(ProductRegistrationController 대칭).
 *
 * <p>Track 45에서 조회(GET)·삭제(DELETE)·수량변경/selected 토글(PATCH)을 추가한다. 대상 식별키는 variantId이며(내부 PK 미노출·
 * UK(user_id, variant_id) buyer당 유일), 소유권은 userId 스코프 조회로 자동 성립한다. 인가는 기존 {@code /api/v1/cart/**} 매처가 커버한다.
 */
@RestController
public class CartController {

    private final CartService cartService;
    private final BuyerActorResolver buyerActorResolver;

    public CartController(CartService cartService, BuyerActorResolver buyerActorResolver) {
        this.cartService = cartService;
        this.buyerActorResolver = buyerActorResolver;
    }

    /**
     * 장바구니 담기(Track 40). 성공 201 + 담김 상태. 미인증 401·비-BUYER 403(SecurityConfig 필터)·Bean Validation 위반 400·
     * variant 미존재 404·동시삽입 충돌 409({@link CartService}·GlobalExceptionHandler).
     */
    @PostMapping("/api/v1/cart/items")
    public ResponseEntity<CartItemAddResponse> addItem(
            @Valid @RequestBody CartItemAddRequest request, HttpServletRequest httpRequest) {
        Long userId = buyerActorResolver.resolve(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(cartService.addItem(userId, request));
    }

    /** 장바구니 조회(Track 45). 담김 품목 전량 enrich 반환(페이징 없음). 200. */
    @GetMapping("/api/v1/cart")
    public ResponseEntity<CartResponse> getCart(HttpServletRequest httpRequest) {
        Long userId = buyerActorResolver.resolve(httpRequest);
        return ResponseEntity.ok(cartService.getCart(userId));
    }

    /** 장바구니 삭제(Track 45·단건도 배열 1개). buyer 스코프 물리삭제. 200. */
    @DeleteMapping("/api/v1/cart/items")
    public ResponseEntity<Void> removeItems(
            @Valid @RequestBody CartItemDeleteRequest request, HttpServletRequest httpRequest) {
        Long userId = buyerActorResolver.resolve(httpRequest);
        cartService.removeItems(userId, request.variantIds());
        return ResponseEntity.ok().build();
    }

    /** 장바구니 수량 변경(Track 45·절대값). 대상 미담김 404·@Min(1) 위반 400. 200. */
    @PatchMapping("/api/v1/cart/items/quantity")
    public ResponseEntity<Void> changeQuantity(
            @Valid @RequestBody CartItemQuantityUpdateRequest request, HttpServletRequest httpRequest) {
        Long userId = buyerActorResolver.resolve(httpRequest);
        cartService.changeQuantity(userId, request.variantId(), request.quantity());
        return ResponseEntity.ok().build();
    }

    /** 장바구니 단건 selected 토글(Track 45). 대상 미담김 404. 200. */
    @PatchMapping("/api/v1/cart/items/selected")
    public ResponseEntity<Void> setSelected(
            @Valid @RequestBody CartItemSelectRequest request, HttpServletRequest httpRequest) {
        Long userId = buyerActorResolver.resolve(httpRequest);
        cartService.setSelected(userId, request.variantId(), request.selected());
        return ResponseEntity.ok().build();
    }

    /** 장바구니 전체 selected 토글(Track 45). 200. */
    @PatchMapping("/api/v1/cart/items/selected/all")
    public ResponseEntity<Void> setSelectedAll(
            @Valid @RequestBody CartSelectAllRequest request, HttpServletRequest httpRequest) {
        Long userId = buyerActorResolver.resolve(httpRequest);
        cartService.setSelectedAll(userId, request.selected());
        return ResponseEntity.ok().build();
    }
}
