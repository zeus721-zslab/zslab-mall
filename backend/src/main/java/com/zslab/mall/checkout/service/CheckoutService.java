package com.zslab.mall.checkout.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.checkout.command.CartCheckoutCommand;
import com.zslab.mall.checkout.command.CartCheckoutItemCommand;
import com.zslab.mall.checkout.command.CheckoutCommand;
import com.zslab.mall.checkout.command.CheckoutContext;
import com.zslab.mall.checkout.command.CheckoutItemCommand;
import com.zslab.mall.checkout.entity.OrderIdempotencyKey;
import com.zslab.mall.checkout.enums.IdempotencyStatus;
import com.zslab.mall.checkout.exception.CheckoutItemMismatchException;
import com.zslab.mall.checkout.exception.CheckoutItemNotFoundException;
import com.zslab.mall.checkout.exception.IdempotencyKeyInProgressException;
import com.zslab.mall.checkout.repository.OrderIdempotencyKeyRepository;
import com.zslab.mall.inventory.entity.Inventory;
import com.zslab.mall.inventory.repository.InventoryRepository;
import com.zslab.mall.order.command.CreateOrderCommand;
import com.zslab.mall.order.command.OrderItemCommand;
import com.zslab.mall.order.controller.response.CheckoutResponse;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.exception.OrderNotFoundException;
import com.zslab.mall.order.exception.OrderNotPayableException;
import com.zslab.mall.order.exception.OrderNotPayableReason;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.order.service.OrderService;
import com.zslab.mall.payment.enums.PaymentMethod;
import com.zslab.mall.payment.gateway.PaymentGatewayException;
import com.zslab.mall.payment.service.PaymentInitiation;
import com.zslab.mall.payment.service.PaymentService;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductVariant;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.product.repository.ProductVariantRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 체크아웃 오케스트레이션(D-58). 신규 주문(createOrder TX1 → initiate TX2)·재결제 진입점·멱등성(D-52) 조립을 담당한다.
 *
 * <p><b>트랜잭션 경계</b>: 본 서비스는 {@code @Transactional}을 두지 않는다. createOrder·initiate는 각자 자체 트랜잭션이며
 * (D-43·D-28), 멱등성 행 INSERT/UPDATE도 단계별 독립 커밋이다(D-52 1·3·5단계). 부분 실패 시 Order는 PENDING_PAYMENT로 유지된다.
 *
 * <p><b>재검증(D-60)</b>: D-63에 따라 상품 상태·재고 재검증은 <b>재결제 경로에만</b> 적용한다. 신규 주문 경로는 미적용(D-51).
 */
@Slf4j
@Service
public class CheckoutService {

    private static final String ORDER_LOCATION_PREFIX = "/api/v1/orders/";
    private static final String PAYMENT_LOCATION_PREFIX = "/api/v1/payments/";
    private static final String RETRY_PATH_SUFFIX = "/payments";

    private final OrderService orderService;
    private final PaymentService paymentService;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final InventoryRepository inventoryRepository;
    private final OrderIdempotencyKeyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public CheckoutService(
            OrderService orderService,
            PaymentService paymentService,
            OrderRepository orderRepository,
            ProductRepository productRepository,
            ProductVariantRepository productVariantRepository,
            InventoryRepository inventoryRepository,
            OrderIdempotencyKeyRepository idempotencyRepository,
            ObjectMapper objectMapper) {
        this.orderService = orderService;
        this.paymentService = paymentService;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.productVariantRepository = productVariantRepository;
        this.inventoryRepository = inventoryRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 신규 주문 + 첫 결제 시작(§5). 직접주문(Buy Now·public_id·{@link CheckoutCommand})·장바구니 결제(내부 id·
     * {@link CartCheckoutCommand})의 공통 진입(seam(i)·A-1). Idempotency-Key 전달 시 D-52 분기, 미전달 시 매 요청 신규 생성(§8).
     * 오케스트레이션(멱등·결제 initiate)은 100% 공유하며 품목 식별자 해소만 구현 타입별로 분기한다(B-1).
     */
    public CheckoutOutcome checkout(CheckoutContext command) {
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            Order order = createOrder(command);
            return completeWithInitiate(order, command, null);
        }
        return idempotentCheckout(command);
    }

    /**
     * 재결제(POST /api/v1/orders/{orderPublicId}/payments·§6). D-60 재검증(2종) 후 initiate. 멱등성 미적용(§8).
     */
    public CheckoutOutcome retryPayment(String orderPublicId, Long buyerId, PaymentMethod method) {
        // 재검증은 items가 필요하므로 먼저 로드(본인 검증 포함). initiate도 본인 검증을 수행하나 정보 노출 회피 위해 동일 404.
        Order order = orderRepository.findByPublicIdWithItems(orderPublicId)
                .orElseThrow(() -> new OrderNotFoundException("주문을 찾을 수 없습니다: " + orderPublicId));
        if (!order.getBuyerId().equals(buyerId)) {
            throw new OrderNotFoundException("주문을 찾을 수 없습니다: " + orderPublicId);
        }
        revalidatePayable(order);

        PaymentInitiation initiation = paymentService.initiate(orderPublicId, buyerId, method);
        CheckoutResponse response = CheckoutResponse.forRetry(initiation.payment(), initiation.redirectUrl());
        return CheckoutOutcome.created(response, PAYMENT_LOCATION_PREFIX + initiation.payment().getPublicId());
    }

    private CheckoutOutcome idempotentCheckout(CheckoutContext command) {
        Optional<OrderIdempotencyKey> existing =
                idempotencyRepository.findByBuyerIdAndIdempotencyKey(command.buyerId(), command.idempotencyKey());
        if (existing.isPresent()) {
            return handleExistingKey(existing.get(), command);
        }

        OrderIdempotencyKey mark;
        try {
            // D-52 1단계: 키 선점 INSERT. PK 충돌 = 동시 요청 경쟁.
            mark = idempotencyRepository.saveAndFlush(OrderIdempotencyKey.startInProgress(
                    command.buyerId(), command.idempotencyKey(), LocalDateTime.now()));
        } catch (DataIntegrityViolationException race) {
            log.warn("[Checkout] 멱등성 키 선점 경쟁 감지: buyerId={}", command.buyerId());
            OrderIdempotencyKey raced = idempotencyRepository
                    .findByBuyerIdAndIdempotencyKey(command.buyerId(), command.idempotencyKey())
                    .orElseThrow(() -> new IdempotencyKeyInProgressException("멱등성 키 처리 중입니다."));
            return handleExistingKey(raced, command);
        }

        Order order;
        try {
            order = createOrder(command);            // D-52 2단계: TX1
        } catch (CheckoutItemNotFoundException | CheckoutItemMismatchException | OrderNotPayableException fourXx) {
            // D-66: 클라이언트 교정 가능 4xx(품목 미해소·재고 부족 §10 α 포함) → IN_PROGRESS row 삭제하여 동일 키 재시도 허용
            idempotencyRepository.delete(mark);
            throw fourXx;
        }
        mark.linkOrder(order.getId());
        idempotencyRepository.saveAndFlush(mark);    // D-52 3단계: order_id 저장(별도 커밋)
        return completeWithInitiate(order, command, mark);
    }

    private CheckoutOutcome handleExistingKey(OrderIdempotencyKey existing, CheckoutContext command) {
        if (existing.getStatus() == IdempotencyStatus.COMPLETED) {
            return CheckoutOutcome.cached(deserialize(existing.getResponseBody()));   // §10·HTTP 200
        }
        if (existing.getOrderId() == null) {
            throw new IdempotencyKeyInProgressException("동일 요청이 처리 중입니다.");   // 409·order 미생성
        }
        // IN_PROGRESS + order_id != null → 기존 Order 복구·initiate 재호출만(Order 재생성 금지·D-52)
        Order order = orderRepository.findById(existing.getOrderId())
                .orElseThrow(() -> new IllegalStateException(
                        "멱등성 복구 실패·주문 미발견: orderId=" + existing.getOrderId()));
        return completeWithInitiate(order, command, existing);
    }

    /** D-64·D-65: 품목 식별자 해소 → 서버 가격/sellerId 산정 → OrderService.createOrder(TX1). 해소만 타입별 분기(B-1). */
    private Order createOrder(CheckoutContext command) {
        List<OrderItemCommand> resolvedItems = resolveItems(command);

        // D-101 §10 α: 트랜잭션 진입 전 사전 재고 검증(TOCTOU 완화·Inventory.reserve INV-1 2차 방어와 이중화). 부족 시 즉시 422.
        revalidateInventory(resolvedItems);

        // D-61: Track 4 시점 discount·shipping = 0
        CreateOrderCommand createCommand = new CreateOrderCommand(
                command.buyerId(), resolvedItems, command.shipping(), 0L, 0L);
        return orderService.createOrder(createCommand);
    }

    /**
     * 품목 식별자 해소 분기(B-1). 직접주문({@link CheckoutCommand})=public_id·장바구니 결제({@link CartCheckoutCommand})=내부 id.
     * 이후 로직(재고검증·주문생성·결제)은 산출된 resolvedItems로 100% 공유한다.
     */
    private List<OrderItemCommand> resolveItems(CheckoutContext command) {
        if (command instanceof CheckoutCommand directOrder) {
            return resolveByPublicId(directOrder.items());
        }
        if (command instanceof CartCheckoutCommand cartCheckout) {
            return resolveByInternalId(cartCheckout.items());
        }
        throw new IllegalArgumentException(
                "지원하지 않는 CheckoutContext 구현: " + command.getClass().getName());
    }

    /** 직접주문(public_id) 해소: findByPublicIdIn → item별 (Product·ProductVariant) 확보·소속 검증 후 조립(D-64·기존 동작 보존). */
    private List<OrderItemCommand> resolveByPublicId(List<CheckoutItemCommand> itemCommands) {
        List<String> productPublicIds = itemCommands.stream()
                .map(CheckoutItemCommand::productPublicId).distinct().toList();
        List<String> variantPublicIds = itemCommands.stream()
                .map(CheckoutItemCommand::variantPublicId).distinct().toList();
        Map<String, Product> productByPublicId = productRepository.findByPublicIdIn(productPublicIds).stream()
                .collect(Collectors.toMap(Product::getPublicId, Function.identity()));
        Map<String, ProductVariant> variantByPublicId = productVariantRepository.findByPublicIdIn(variantPublicIds).stream()
                .collect(Collectors.toMap(ProductVariant::getPublicId, Function.identity()));

        List<OrderItemCommand> resolvedItems = new ArrayList<>();
        for (CheckoutItemCommand item : itemCommands) {
            Product product = productByPublicId.get(item.productPublicId());
            if (product == null) {
                throw new CheckoutItemNotFoundException("상품을 찾을 수 없습니다: " + item.productPublicId());
            }
            ProductVariant variant = variantByPublicId.get(item.variantPublicId());
            if (variant == null) {
                throw new CheckoutItemNotFoundException("상품 변형을 찾을 수 없습니다: " + item.variantPublicId());
            }
            if (!variant.getProductId().equals(product.getId())) {
                throw new CheckoutItemMismatchException("변형이 상품에 속하지 않습니다: product="
                        + item.productPublicId() + ", variant=" + item.variantPublicId());
            }
            resolvedItems.add(toOrderItemCommand(product, variant, item.quantity()));
        }
        return resolvedItems;
    }

    /**
     * 장바구니 결제(내부 id) 해소(Track 41 β·B-1). CartItem이 보유한 variantId만으로 ProductVariant→Product를 {@code findByIdIn}으로
     * 해소한다(revalidatePayable와 동형 로더). product는 variant.getProductId()에서 도출하므로 소속 불일치(mismatch)는 구조상
     * 발생하지 않는다. 변형·상품 미해소(미존재·soft-delete)는 직접주문 경로와 동일하게 {@link CheckoutItemNotFoundException}
     * (404·클라 교정 가능)으로 처리한다.
     */
    private List<OrderItemCommand> resolveByInternalId(List<CartCheckoutItemCommand> itemCommands) {
        List<Long> variantIds = itemCommands.stream()
                .map(CartCheckoutItemCommand::variantId).distinct().toList();
        Map<Long, ProductVariant> variantById = productVariantRepository.findByIdIn(variantIds).stream()
                .collect(Collectors.toMap(ProductVariant::getId, Function.identity()));
        List<Long> productIds = variantById.values().stream()
                .map(ProductVariant::getProductId).distinct().toList();
        Map<Long, Product> productById = productRepository.findByIdIn(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        List<OrderItemCommand> resolvedItems = new ArrayList<>();
        for (CartCheckoutItemCommand item : itemCommands) {
            ProductVariant variant = variantById.get(item.variantId());
            if (variant == null) {
                throw new CheckoutItemNotFoundException("상품 변형을 찾을 수 없습니다: variantId=" + item.variantId());
            }
            Product product = productById.get(variant.getProductId());
            if (product == null) {
                // 변형은 있으나 상품이 미해소(soft-delete 등) — 직접주문 경로의 상품 미존재와 동일 취급(404·클라 교정).
                throw new CheckoutItemNotFoundException("상품을 찾을 수 없습니다: productId=" + variant.getProductId());
            }
            resolvedItems.add(toOrderItemCommand(product, variant, item.quantity()));
        }
        return resolvedItems;
    }

    /** D-64 서버 산정(양 해소 경로 공용 tail): unit_price = base_price + additional_price·total = unit × qty·seller = product.seller_id. */
    private OrderItemCommand toOrderItemCommand(Product product, ProductVariant variant, int quantity) {
        long unitPrice = product.getBasePrice() + variant.getAdditionalPrice();
        long totalPrice = unitPrice * quantity;
        return new OrderItemCommand(
                product.getId(), variant.getId(), product.getSellerId(),
                quantity, unitPrice, totalPrice);
    }

    /**
     * 신규 주문 사전 재고 검증(D-101 §10 α·§4 갱신 read-only 2 예외). variant별 요청 수량 합계가 가용 재고를 초과하면
     * 트랜잭션 진입 전 즉시 OUT_OF_STOCK(422)로 차단한다. read-only {@code findByVariantIdIn}만 사용하며 예약은 하지 않는다
     * (실 예약은 E1 OrderPlaced 핸들러의 {@code InventoryService.reserve}가 비관락으로 수행·2차 방어).
     */
    private void revalidateInventory(List<OrderItemCommand> items) {
        List<Long> variantIds = items.stream().map(OrderItemCommand::variantId).distinct().toList();
        Map<Long, Inventory> inventoryByVariantId = inventoryRepository.findByVariantIdIn(variantIds).stream()
                .collect(Collectors.toMap(Inventory::getVariantId, Function.identity()));
        Map<Long, Integer> requestedByVariantId = items.stream()
                .collect(Collectors.groupingBy(OrderItemCommand::variantId, Collectors.summingInt(OrderItemCommand::quantity)));
        for (Map.Entry<Long, Integer> requested : requestedByVariantId.entrySet()) {
            Inventory inventory = inventoryByVariantId.get(requested.getKey());
            if (inventory == null || inventory.getQuantityAvailable() < requested.getValue()) {
                throw new OrderNotPayableException(OrderNotPayableReason.OUT_OF_STOCK,
                        "재고 부족: variantId=" + requested.getKey() + ", 요청=" + requested.getValue());
            }
        }
    }

    /** 신규/복구 공통: initiate(TX2) → 성공 forNewOrder·PG 실패 INITIATE_FAILED. 멱등성 행 있으면 2xx 응답 캐싱(§10). */
    private CheckoutOutcome completeWithInitiate(Order order, CheckoutContext command, OrderIdempotencyKey markOrNull) {
        CheckoutResponse response;
        try {
            PaymentInitiation initiation =
                    paymentService.initiate(order.getPublicId(), command.buyerId(), command.method());
            response = CheckoutResponse.forNewOrder(initiation.payment(), initiation.redirectUrl());
        } catch (PaymentGatewayException gatewayFailure) {
            // §5 부분 실패: Order 유지(PENDING_PAYMENT)·Payment row 미저장(initiate TX 롤백). §18 운영 로그 5필드.
            log.warn("[Checkout] 결제 시작 실패(INITIATE_FAILED) — orderPublicId={}, attemptKey={}, buyerId={}, failureCode={}, occurredAt={}",
                    order.getPublicId(), gatewayFailure.getAttemptKey(), command.buyerId(),
                    gatewayFailure.getFailureCode(), LocalDateTime.now());
            response = CheckoutResponse.forNewOrderInitiateFailed(
                    ORDER_LOCATION_PREFIX + order.getPublicId() + RETRY_PATH_SUFFIX);
        }

        if (markOrNull != null) {
            // §10·D-52 5단계: 2xx 성공 응답만 캐싱(신규/실패 모두 201). 직렬화 후 COMPLETED.
            markOrNull.complete(serialize(response), LocalDateTime.now());
            idempotencyRepository.saveAndFlush(markOrNull);
        }
        return CheckoutOutcome.created(response, ORDER_LOCATION_PREFIX + order.getPublicId());
    }

    /** D-60 재검증 2종(재결제 한정·D-63). 상품 판매중지 → PRODUCT_NOT_ON_SALE·수동품절/재고부족 → OUT_OF_STOCK(422). */
    private void revalidatePayable(Order order) {
        List<OrderItem> items = order.getItems();
        List<Long> productIds = items.stream().map(OrderItem::getProductId).distinct().toList();
        List<Long> variantIds = items.stream().map(OrderItem::getVariantId).distinct().toList();
        Map<Long, Product> productById = productRepository.findByIdIn(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, ProductVariant> variantById = productVariantRepository.findByIdIn(variantIds).stream()
                .collect(Collectors.toMap(ProductVariant::getId, Function.identity()));
        Map<Long, Inventory> inventoryByVariantId = inventoryRepository.findByVariantIdIn(variantIds).stream()
                .collect(Collectors.toMap(Inventory::getVariantId, Function.identity()));

        for (OrderItem item : items) {
            Product product = productById.get(item.getProductId());
            if (product == null || product.getStatus() != ProductStatus.SALE) {
                throw new OrderNotPayableException(OrderNotPayableReason.PRODUCT_NOT_ON_SALE,
                        "판매 중이 아닌 상품: productId=" + item.getProductId());
            }
            ProductVariant variant = variantById.get(item.getVariantId());
            Inventory inventory = inventoryByVariantId.get(item.getVariantId());
            boolean soldOutManual = variant != null && variant.isSoldoutManual();
            boolean lackStock = inventory == null || inventory.getQuantityAvailable() < item.getQuantity();
            if (soldOutManual || lackStock) {
                throw new OrderNotPayableException(OrderNotPayableReason.OUT_OF_STOCK,
                        "재고 부족 또는 품절: variantId=" + item.getVariantId());
            }
        }
    }

    private String serialize(CheckoutResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("멱등성 응답 직렬화 실패", e);
        }
    }

    private CheckoutResponse deserialize(String responseBody) {
        try {
            return objectMapper.readValue(responseBody, CheckoutResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("멱등성 응답 역직렬화 실패", e);
        }
    }
}
