package com.zslab.mall.cart.service;

import com.zslab.mall.cart.controller.request.CartItemAddRequest;
import com.zslab.mall.cart.controller.response.CartItemAddResponse;
import com.zslab.mall.cart.controller.response.CartItemView;
import com.zslab.mall.cart.controller.response.CartResponse;
import com.zslab.mall.cart.entity.CartItem;
import com.zslab.mall.cart.exception.CartItemNotFoundException;
import com.zslab.mall.cart.repository.CartItemRepository;
import com.zslab.mall.inventory.entity.Inventory;
import com.zslab.mall.inventory.repository.InventoryRepository;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductVariant;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.product.enums.ProductVariantStatus;
import com.zslab.mall.product.exception.ProductVariantNotFoundException;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.product.repository.ProductVariantRepository;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.seller.repository.SellerRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final SellerRepository sellerRepository;

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

    /**
     * 로그인 buyer의 장바구니를 조회한다(Track 45). 담김 품목을 variant→product→seller·재고로 enrich해 단가·품절·구매가능을
     * variant 단위로 계산한다(카탈로그 정책과 계산 대상이 달라 재구현·enrich Repository만 재사용). dangling(담긴 후 variant
     * soft-delete로 findByIdIn 누락·비-SALE·판매자 비-ACTIVE)은 삭제하지 않고 {@code purchasable=false}로 표기 유지한다.
     */
    @Transactional(readOnly = true)
    public CartResponse getCart(Long userId) {
        List<CartItem> items = cartItemRepository.findByUserId(userId);
        if (items.isEmpty()) {
            return new CartResponse(List.of());
        }

        List<Long> variantIds = items.stream().map(CartItem::getVariantId).distinct().toList();
        Map<Long, ProductVariant> variantById = productVariantRepository.findByIdIn(variantIds).stream()
                .collect(Collectors.toMap(ProductVariant::getId, Function.identity()));
        List<Long> productIds = variantById.values().stream().map(ProductVariant::getProductId).distinct().toList();
        Map<Long, Product> productById = productIds.isEmpty()
                ? Map.of()
                : productRepository.findByIdIn(productIds).stream()
                        .collect(Collectors.toMap(Product::getId, Function.identity()));
        List<Long> sellerIds = productById.values().stream().map(Product::getSellerId).distinct().toList();
        Map<Long, Seller> sellerById = sellerIds.isEmpty()
                ? Map.of()
                : sellerRepository.findByIdIn(sellerIds).stream()
                        .collect(Collectors.toMap(Seller::getId, Function.identity()));
        Map<Long, Inventory> inventoryByVariant = inventoryRepository.findByVariantIdIn(variantIds).stream()
                .collect(Collectors.toMap(Inventory::getVariantId, Function.identity()));

        List<CartItemView> views = items.stream()
                .map(item -> toView(item, variantById, productById, sellerById, inventoryByVariant))
                .toList();
        return new CartResponse(views);
    }

    /**
     * 장바구니 품목을 buyer 스코프로 물리삭제한다(Track 45). deleteByUserIdAndVariantIdIn 재사용(단건도 배열 1개)이며,
     * userId 스코프라 타 buyer 항목은 삭제되지 않는다(소유권 자동). 대상 부재 시 0행 삭제로 무해하다.
     */
    public void removeItems(Long userId, List<Long> variantIds) {
        long deleted = cartItemRepository.deleteByUserIdAndVariantIdIn(userId, variantIds);
        log.info("[Cart] 장바구니 삭제 userId={} variant_count={} deleted={}", userId, variantIds.size(), deleted);
    }

    /**
     * 장바구니 품목 수량을 절대값으로 변경한다(Track 45). userId 스코프 단건 조회로 소유권을 보장하며, 대상 미담김 시 404다.
     * quantity 하한(≥1·CRT-2)은 엔티티 {@link CartItem#changeQuantity}가 재검증한다(dirty checking flush).
     *
     * @throws CartItemNotFoundException 대상 variant가 buyer 장바구니에 없을 때(404)
     */
    public void changeQuantity(Long userId, Long variantId, int quantity) {
        CartItem cartItem = cartItemRepository.findByUserIdAndVariantId(userId, variantId)
                .orElseThrow(() -> new CartItemNotFoundException(
                        "장바구니에 해당 상품이 담겨 있지 않습니다: variantId=" + variantId));
        cartItem.changeQuantity(quantity);
    }

    /**
     * 장바구니 품목 단건의 결제 선택 상태를 토글한다(Track 45). userId 스코프 단건 조회로 소유권을 보장하며, 대상 미담김 시 404다.
     *
     * @throws CartItemNotFoundException 대상 variant가 buyer 장바구니에 없을 때(404)
     */
    public void setSelected(Long userId, Long variantId, boolean selected) {
        CartItem cartItem = cartItemRepository.findByUserIdAndVariantId(userId, variantId)
                .orElseThrow(() -> new CartItemNotFoundException(
                        "장바구니에 해당 상품이 담겨 있지 않습니다: variantId=" + variantId));
        applySelected(cartItem, selected);
    }

    /** 장바구니 전 품목의 결제 선택 상태를 일괄 토글한다(Track 45). 담김 없으면 무작업. */
    public void setSelectedAll(Long userId, boolean selected) {
        for (CartItem cartItem : cartItemRepository.findByUserId(userId)) {
            applySelected(cartItem, selected);
        }
    }

    private void applySelected(CartItem cartItem, boolean selected) {
        if (selected) {
            cartItem.select();
        } else {
            cartItem.deselect();
        }
    }

    /**
     * 담김 품목 1건을 enrich 뷰로 변환한다. 단가·품절·구매가능은 variant 단위 실측 공식으로 계산하며, enrich 누락(dangling)은
     * null/0/purchasable=false로 표기한다. 품절 = isSoldoutManual OR available==0(Inventory 단독 불가·variant 결합).
     */
    private CartItemView toView(
            CartItem item,
            Map<Long, ProductVariant> variantById,
            Map<Long, Product> productById,
            Map<Long, Seller> sellerById,
            Map<Long, Inventory> inventoryByVariant) {
        ProductVariant variant = variantById.get(item.getVariantId());
        Product product = variant != null ? productById.get(variant.getProductId()) : null;
        Seller seller = product != null ? sellerById.get(product.getSellerId()) : null;
        Inventory inventory = inventoryByVariant.get(item.getVariantId());

        // dangling(variant soft-delete)이면 inventory 행(soft-delete 없음)이 남아 있어도 재고를 0으로 표기한다(구매불가 정합).
        int available = (variant != null && inventory != null) ? inventory.getQuantityAvailable() : 0;
        String productName = product != null ? product.getName() : null;
        String sellerName = seller != null ? seller.getCompanyName() : null;
        String thumbnailUrl = product != null ? product.getThumbnailUrl() : null;
        long displayPrice = (product != null && variant != null)
                ? product.getBasePrice() + variant.getAdditionalPrice()
                : 0L;

        boolean purchasable = variant != null && product != null && seller != null
                && !variant.isSoldoutManual() && available > 0
                && variant.getStatus() == ProductVariantStatus.SALE
                && product.getStatus() == ProductStatus.SALE
                && seller.getStatus() == SellerStatus.ACTIVE;

        return new CartItemView(
                item.getVariantId(), item.getQuantity(), item.getSelected(),
                productName, sellerName, displayPrice, available, purchasable, thumbnailUrl);
    }
}
