package com.zslab.mall.checkout.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.checkout.command.CheckoutCommand;
import com.zslab.mall.checkout.command.CheckoutItemCommand;
import com.zslab.mall.checkout.entity.OrderIdempotencyKey;
import com.zslab.mall.checkout.exception.IdempotencyKeyInProgressException;
import com.zslab.mall.checkout.repository.OrderIdempotencyKeyRepository;
import com.zslab.mall.inventory.entity.Inventory;
import com.zslab.mall.inventory.repository.InventoryRepository;
import com.zslab.mall.order.command.CreateOrderCommand;
import com.zslab.mall.order.command.ShippingAddressCommand;
import com.zslab.mall.order.controller.response.CheckoutResponse;
import com.zslab.mall.order.controller.response.CheckoutResponse.PaymentView;
import com.zslab.mall.order.controller.response.StatusView;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.exception.OrderNotPayableException;
import com.zslab.mall.order.exception.OrderNotPayableReason;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.order.service.OrderService;
import com.zslab.mall.payment.entity.Payment;
import com.zslab.mall.payment.enums.PaymentMethod;
import com.zslab.mall.payment.enums.PaymentStatus;
import com.zslab.mall.payment.gateway.PaymentGatewayException;
import com.zslab.mall.payment.service.PaymentInitiation;
import com.zslab.mall.payment.service.PaymentService;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductVariant;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.product.repository.ProductVariantRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link CheckoutService} 단위 검증(Mockito). 멱등성 분기(캐시·진행중 409·복구)·서버 가격 산정(D-64)·결제 시작 실패·재검증(D-60) 경로.
 *
 * <p>실 DB 흐름(쿼리 정합·INSERT 충돌·validate)은 CheckoutIntegrationTest가 담당하고, 본 테스트는 분기 로직에 집중한다.
 * Order/OrderItem은 실제 객체(+리플렉션 id), read-only 엔티티(Product·Variant·Inventory)는 mock으로 구성한다.
 */
@ExtendWith(MockitoExtension.class)
class CheckoutServiceTest {

    private static final Long BUYER_ID = 1L;
    private static final String PRODUCT_PID = "prd_TEST0000000000000000000AA";
    private static final String VARIANT_PID = "var_TEST0000000000000000000AA";

    @Mock private OrderService orderService;
    @Mock private PaymentService paymentService;
    @Mock private OrderRepository orderRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ProductVariantRepository productVariantRepository;
    @Mock private InventoryRepository inventoryRepository;
    @Mock private OrderIdempotencyKeyRepository idempotencyRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private CheckoutService checkoutService;

    @BeforeEach
    void setUp() {
        checkoutService = new CheckoutService(orderService, paymentService, orderRepository,
                productRepository, productVariantRepository, inventoryRepository, idempotencyRepository, objectMapper);
    }

    private CheckoutCommand command(String idempotencyKey) {
        return new CheckoutCommand(BUYER_ID, idempotencyKey,
                List.of(new CheckoutItemCommand(PRODUCT_PID, VARIANT_PID, 2)),
                new ShippingAddressCommand("홍길동", "010-0000-0000", "06236", "서울 1", null, null, null),
                PaymentMethod.CARD);
    }

    private Order order(Long id, String publicId, OrderItem... items) {
        Order order = Order.create(BUYER_ID, "20260627-TEST01", 0L, 0L);
        for (OrderItem item : items) {
            order.addItem(item);
        }
        ReflectionTestUtils.setField(order, "id", id);
        ReflectionTestUtils.setField(order, "publicId", publicId);
        return order;
    }

    private Payment paymentMock(String publicId) {
        Payment payment = org.mockito.Mockito.mock(Payment.class);
        when(payment.getPublicId()).thenReturn(publicId);
        when(payment.getStatus()).thenReturn(PaymentStatus.PENDING);
        when(payment.getExpiresAt()).thenReturn(LocalDateTime.now().plusMinutes(30));
        return payment;
    }

    /** 신규 주문 생성 시 Product/Variant 해소(가격·sellerId 서버 산정) 스텁. */
    private void stubProductResolution(long basePrice, long additionalPrice, long sellerId) {
        Product product = org.mockito.Mockito.mock(Product.class);
        when(product.getPublicId()).thenReturn(PRODUCT_PID);
        when(product.getId()).thenReturn(10L);
        when(product.getBasePrice()).thenReturn(basePrice);
        when(product.getSellerId()).thenReturn(sellerId);
        ProductVariant variant = org.mockito.Mockito.mock(ProductVariant.class);
        when(variant.getPublicId()).thenReturn(VARIANT_PID);
        when(variant.getId()).thenReturn(20L);
        when(variant.getProductId()).thenReturn(10L);
        when(variant.getAdditionalPrice()).thenReturn(additionalPrice);
        when(productRepository.findByPublicIdIn(any())).thenReturn(List.of(product));
        when(productVariantRepository.findByPublicIdIn(any())).thenReturn(List.of(variant));
    }

    @Test
    @DisplayName("checkout(키 없음): 서버 가격 산정 후 createOrder·initiate → 201·멱등성 미사용")
    void checkout_freshNoKey_pricesAndInitiates() {
        stubProductResolution(8_000L, 2_000L, 99L);
        ArgumentCaptor<CreateOrderCommand> captor = ArgumentCaptor.forClass(CreateOrderCommand.class);
        when(orderService.createOrder(captor.capture())).thenReturn(order(1L, "ord_NEW00000000000000000000AA"));
        Payment payment = paymentMock("pay_1");
        when(paymentService.initiate(eq("ord_NEW00000000000000000000AA"), eq(BUYER_ID), eq(PaymentMethod.CARD)))
                .thenReturn(new PaymentInitiation(payment, "https://pg/redirect"));

        CheckoutOutcome outcome = checkoutService.checkout(command(null));

        assertThat(outcome.cached()).isFalse();
        assertThat(outcome.location()).isEqualTo("/api/v1/orders/ord_NEW00000000000000000000AA");
        assertThat(outcome.response().payment().publicId()).isEqualTo("pay_1");
        // D-64 서버 산정: unit = base(8000) + additional(2000) = 10000·total = 10000×2 = 20000·seller = product.seller_id
        CreateOrderCommand created = captor.getValue();
        assertThat(created.items()).hasSize(1);
        assertThat(created.items().get(0).unitPrice()).isEqualTo(10_000L);
        assertThat(created.items().get(0).totalPrice()).isEqualTo(20_000L);
        assertThat(created.items().get(0).sellerId()).isEqualTo(99L);
        assertThat(created.discountAmount()).isZero();
        assertThat(created.shippingFee()).isZero();
    }

    @Test
    @DisplayName("checkout(COMPLETED 키): 캐시 응답 반환(200)·createOrder 미호출")
    void checkout_completedKey_returnsCache() throws Exception {
        CheckoutResponse cached = new CheckoutResponse(
                new PaymentView("pay_CACHED", StatusView.of(PaymentStatus.PENDING), "https://pg/redirect", null), null);
        OrderIdempotencyKey key = OrderIdempotencyKey.startInProgress(BUYER_ID, "K1", LocalDateTime.now());
        key.complete(objectMapper.writeValueAsString(cached), LocalDateTime.now());
        when(idempotencyRepository.findByBuyerIdAndIdempotencyKey(BUYER_ID, "K1")).thenReturn(Optional.of(key));

        CheckoutOutcome outcome = checkoutService.checkout(command("K1"));

        assertThat(outcome.cached()).isTrue();
        assertThat(outcome.response().payment().publicId()).isEqualTo("pay_CACHED");
        verify(orderService, never()).createOrder(any());
    }

    @Test
    @DisplayName("checkout(IN_PROGRESS·order 미생성): 409 IdempotencyKeyInProgressException")
    void checkout_inProgressNullOrder_throws409() {
        OrderIdempotencyKey key = OrderIdempotencyKey.startInProgress(BUYER_ID, "K2", LocalDateTime.now());
        when(idempotencyRepository.findByBuyerIdAndIdempotencyKey(BUYER_ID, "K2")).thenReturn(Optional.of(key));

        assertThatThrownBy(() -> checkoutService.checkout(command("K2")))
                .isInstanceOf(IdempotencyKeyInProgressException.class);
        verify(orderService, never()).createOrder(any());
    }

    @Test
    @DisplayName("checkout(IN_PROGRESS·order_id 존재): 기존 Order 복구·initiate 재호출(Order 재생성 금지·D-52)")
    void checkout_inProgressWithOrder_recovers() {
        OrderIdempotencyKey key = OrderIdempotencyKey.startInProgress(BUYER_ID, "K3", LocalDateTime.now());
        key.linkOrder(7L);
        when(idempotencyRepository.findByBuyerIdAndIdempotencyKey(BUYER_ID, "K3")).thenReturn(Optional.of(key));
        when(orderRepository.findById(7L)).thenReturn(Optional.of(order(7L, "ord_RECOVER0000000000000000AA")));
        Payment payment = paymentMock("pay_R");
        when(paymentService.initiate(eq("ord_RECOVER0000000000000000AA"), eq(BUYER_ID), eq(PaymentMethod.CARD)))
                .thenReturn(new PaymentInitiation(payment, "https://pg/redirect"));

        CheckoutOutcome outcome = checkoutService.checkout(command("K3"));

        assertThat(outcome.cached()).isFalse();
        assertThat(outcome.response().payment().publicId()).isEqualTo("pay_R");
        verify(orderService, never()).createOrder(any());
    }

    @Test
    @DisplayName("checkout: PG 결제 시작 실패 → INITIATE_FAILED 응답·next.retryPaymentUrl 제공(§7)")
    void checkout_initiateFails_returnsInitiateFailed() {
        stubProductResolution(10_000L, 0L, 99L);
        when(orderService.createOrder(any())).thenReturn(order(1L, "ord_FAIL0000000000000000000AA"));
        when(paymentService.initiate(anyString(), any(), any()))
                .thenThrow(new PaymentGatewayException("pat_x", "PG_DOWN", "redirect 발급 실패"));

        CheckoutOutcome outcome = checkoutService.checkout(command(null));

        assertThat(outcome.response().payment().status().code()).isEqualTo("INITIATE_FAILED");
        assertThat(outcome.response().payment().publicId()).isNull();
        assertThat(outcome.response().next().retryPaymentUrl())
                .isEqualTo("/api/v1/orders/ord_FAIL0000000000000000000AA/payments");
    }

    @Test
    @DisplayName("retry: 상품 판매중지 → 422 ORDER_NOT_PAYABLE(PRODUCT_NOT_ON_SALE)")
    void retry_productNotOnSale_throws422() {
        OrderItem item = OrderItem.create(10L, 20L, 99L, 2, 5_000L, 10_000L);
        when(orderRepository.findByPublicIdWithItems("ord_1")).thenReturn(Optional.of(order(1L, "ord_1", item)));
        Product product = org.mockito.Mockito.mock(Product.class);
        when(product.getId()).thenReturn(10L);
        when(product.getStatus()).thenReturn(ProductStatus.HIDDEN);
        when(productRepository.findByIdIn(any())).thenReturn(List.of(product));
        when(productVariantRepository.findByIdIn(any())).thenReturn(List.of());
        when(inventoryRepository.findByVariantIdIn(any())).thenReturn(List.of());

        assertThatThrownBy(() -> checkoutService.retryPayment("ord_1", BUYER_ID, PaymentMethod.CARD))
                .isInstanceOf(OrderNotPayableException.class)
                .extracting(ex -> ((OrderNotPayableException) ex).getReason())
                .isEqualTo(OrderNotPayableReason.PRODUCT_NOT_ON_SALE);
        verify(paymentService, never()).initiate(anyString(), any(), any());
    }

    @Test
    @DisplayName("retry: 재고 부족 → 422 ORDER_NOT_PAYABLE(OUT_OF_STOCK)")
    void retry_outOfStock_throws422() {
        OrderItem item = OrderItem.create(10L, 20L, 99L, 2, 5_000L, 10_000L);
        when(orderRepository.findByPublicIdWithItems("ord_1")).thenReturn(Optional.of(order(1L, "ord_1", item)));
        Product product = org.mockito.Mockito.mock(Product.class);
        when(product.getId()).thenReturn(10L);
        when(product.getStatus()).thenReturn(ProductStatus.SALE);
        ProductVariant variant = org.mockito.Mockito.mock(ProductVariant.class);
        when(variant.getId()).thenReturn(20L);
        when(variant.isSoldoutManual()).thenReturn(false);
        Inventory inventory = org.mockito.Mockito.mock(Inventory.class);
        when(inventory.getVariantId()).thenReturn(20L);
        when(inventory.getQuantityAvailable()).thenReturn(1);   // qty 2 요청 > 가용 1
        when(productRepository.findByIdIn(any())).thenReturn(List.of(product));
        when(productVariantRepository.findByIdIn(any())).thenReturn(List.of(variant));
        when(inventoryRepository.findByVariantIdIn(any())).thenReturn(List.of(inventory));

        assertThatThrownBy(() -> checkoutService.retryPayment("ord_1", BUYER_ID, PaymentMethod.CARD))
                .isInstanceOf(OrderNotPayableException.class)
                .extracting(ex -> ((OrderNotPayableException) ex).getReason())
                .isEqualTo(OrderNotPayableReason.OUT_OF_STOCK);
    }

    @Test
    @DisplayName("retry: 재검증 통과 → initiate 재호출·forRetry·Location=payment")
    void retry_happy_initiatesAndReturnsPaymentLocation() {
        OrderItem item = OrderItem.create(10L, 20L, 99L, 2, 5_000L, 10_000L);
        when(orderRepository.findByPublicIdWithItems("ord_1")).thenReturn(Optional.of(order(1L, "ord_1", item)));
        Product product = org.mockito.Mockito.mock(Product.class);
        when(product.getId()).thenReturn(10L);
        when(product.getStatus()).thenReturn(ProductStatus.SALE);
        ProductVariant variant = org.mockito.Mockito.mock(ProductVariant.class);
        when(variant.getId()).thenReturn(20L);
        when(variant.isSoldoutManual()).thenReturn(false);
        Inventory inventory = org.mockito.Mockito.mock(Inventory.class);
        when(inventory.getVariantId()).thenReturn(20L);
        when(inventory.getQuantityAvailable()).thenReturn(100);
        when(productRepository.findByIdIn(any())).thenReturn(List.of(product));
        when(productVariantRepository.findByIdIn(any())).thenReturn(List.of(variant));
        when(inventoryRepository.findByVariantIdIn(any())).thenReturn(List.of(inventory));
        Payment retryPayment = paymentMock("pay_RETRY");
        when(paymentService.initiate(eq("ord_1"), eq(BUYER_ID), eq(PaymentMethod.CARD)))
                .thenReturn(new PaymentInitiation(retryPayment, "https://pg/redirect"));

        CheckoutOutcome outcome = checkoutService.retryPayment("ord_1", BUYER_ID, PaymentMethod.CARD);

        assertThat(outcome.cached()).isFalse();
        assertThat(outcome.location()).isEqualTo("/api/v1/payments/pay_RETRY");
        assertThat(outcome.response().payment().publicId()).isEqualTo("pay_RETRY");
    }
}
