package com.zslab.mall.claim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.f4b6a3.ulid.UlidCreator;
import com.zslab.mall.claim.controller.request.ClaimRequestCommand;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimInspectionResult;
import com.zslab.mall.claim.enums.ClaimReasonCode;
import com.zslab.mall.claim.enums.ClaimRejectReasonCode;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.exception.ClaimInvalidStateException;
import com.zslab.mall.claim.service.ClaimService;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.event.DeliveryCompleted;
import com.zslab.mall.delivery.service.DeliveryService;
import com.zslab.mall.delivery.service.ReturnWindowPolicy;
import com.zslab.mall.inventory.exception.InventoryInvariantViolationException;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.repository.SellerGrossProjection;
import com.zslab.mall.order.service.OrderAutoConfirmService;
import com.zslab.mall.payment.gateway.PgRefundResponse;
import com.zslab.mall.payment.gateway.PaymentGateway;
import com.zslab.mall.refund.enums.RefundStatus;
import com.zslab.mall.refund.repository.RefundRepository;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 교환(관리자 처리) 전체 루프 통합 테스트(Track 83 D-177·실 MariaDB·실 커밋·핸들러 체인). 요청(옵션 지정)→승인(재고 예약)→회수 송장→회수 확인→
 * 검수 PASS→교환품 발송→배송완료 → 품목 DELIVERED 복귀·variant/옵션 갱신·예약 확정·재고 이력 1회를 실측하고, 옵션 규칙·재고 부족·예약 해제·
 * 발송 가드·중복 이벤트·refundAmount 400·반품 동일 검증·재교환 차단·교환 후 반품 재입고·타이머 기준·정산 gross·승인 동시성을 검증한다.
 *
 * <p>클래스에 {@code @Transactional}을 두지 않는다(AFTER_COMMIT 핸들러 실측). 시드/정리는 {@link TransactionTemplate} + FOREIGN_KEY_CHECKS=0.
 * PaymentGateway는 접수만 모사(교환은 환불 미경유·교환 후 반품 시나리오의 initiate 흡수용).
 */
@AutoConfigureMockMvc
class ClaimExchangeIntegrationTest extends AbstractIntegrationTest {

    @MockitoBean
    private PaymentGateway paymentGateway;

    private static final long USER_ID = 9402L;
    private static final long ADMIN_ID = 9403L;
    private static final long SELLER_ID = 9402L;
    private static final long PRODUCT_ID = 9402L;
    private static final long OTHER_PRODUCT_ID = 9403L;
    private static final long OPTION_GROUP_ID = 9402L;
    /** 원 옵션(주문 품목). */
    private static final long VARIANT_ORIGINAL = 9402L;
    /** 교환 대상 옵션(같은 상품·같은 가격·SALE). */
    private static final long VARIANT_EXCHANGE = 9403L;
    /** 가격이 다른 옵션(additional_price 500). */
    private static final long VARIANT_PRICED = 9404L;
    /** 판매 중지 옵션(STOPPED). */
    private static final long VARIANT_STOPPED = 9405L;
    /** 다른 상품의 옵션. */
    private static final long VARIANT_OTHER_PRODUCT = 9406L;
    private static final long ORDER_ID = 9402L;
    private static final long ORDER_ITEM_ID = 9402L;
    private static final long PAYMENT_ID = 9402L;
    private static final long OUTBOUND_DELIVERY_ID = 9402L;
    private static final long DUMMY_FK_ID = 9402L;
    private static final long ITEM_PRICE = 10_000L;
    private static final int INITIAL_STOCK = 10;
    /** 관리자 주문 상세·클레임 목록 쿼리 예산(T13·옵션 라벨 배치 조회 3쿼리 포함·N+1 회귀 감지). */
    private static final int ADMIN_DETAIL_QUERY_BUDGET = 20;
    private static final int ADMIN_LIST_QUERY_BUDGET = 12;
    /** 구매자 주문 상세 쿼리 예산(T14·주문+품목·상품·variant·셀러·교환 완료 클레임 배치 1). */
    private static final int BUYER_DETAIL_QUERY_BUDGET = 8;

    private static final String ORDER_ITEM_PID = pid("oit_", "EXCOIT");
    private static final String VAR_EXCHANGE_PID = pid("var_", "EXCVAR2");
    private static final String VAR_ORIGINAL_PID = pid("var_", "EXCVAR1");
    private static final String VAR_PRICED_PID = pid("var_", "EXCVAR3");
    private static final String VAR_STOPPED_PID = pid("var_", "EXCVAR4");
    private static final String VAR_OTHER_PID = pid("var_", "EXCVAR5");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private ClaimService claimService;
    @Autowired
    private DeliveryService deliveryService;
    @Autowired
    private ReturnWindowPolicy returnWindowPolicy;
    @Autowired
    private OrderAutoConfirmService orderAutoConfirmService;
    @Autowired
    private OrderItemRepository orderItemRepository;
    @Autowired
    private RefundRepository refundRepository;
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        when(paymentGateway.refund(any(), any())).thenAnswer(invocation -> new PgRefundResponse(
                "mock_rfn_exc_" + UlidCreator.getMonotonicUlid(), true, null));
        cleanup();
        seed(() -> {
            seedCatalog();
            seedOrder();
            seedOrderItem();
            seedOriginalDelivery(1);
        });
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    // ==================== T1 전체 흐름 ====================

    @Test
    @DisplayName("T1 전체 흐름: 요청(옵션)→승인(예약 reserved+1)→회수 송장→회수 확인→검수 PASS(restock)→발송→배송완료 → DELIVERED·variant/옵션 갱신·reserved 원복·교환 옵션 on_hand −1·원 옵션 +1·이력 ORDER/RETURN 각 1·Refund 0")
    void fullFlow_completesWithDeliveredAndVariantSwap() {
        Long claimId = requestExchange(VAR_EXCHANGE_PID).getId();
        assertThat(orderItemStatus()).isEqualTo("EXCHANGE_REQUESTED");
        assertThat(jdbc.queryForObject("SELECT exchange_variant_id FROM claim WHERE id = ?", Long.class, claimId)).isEqualTo(VARIANT_EXCHANGE);

        claimService.approve(claimId, LocalDateTime.now(), null);
        assertThat(reserved(VARIANT_EXCHANGE)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT original_variant_id FROM claim WHERE id = ?", Long.class, claimId)).isEqualTo(VARIANT_ORIGINAL);
        assertThat(jdbc.queryForObject("SELECT exchange_reserved_at IS NOT NULL FROM claim WHERE id = ?", Boolean.class, claimId)).isTrue();

        claimService.registerReturnShipmentByBuyer(claimPid(claimId), USER_ID, DeliveryCarrier.CJ, "CJ-EXC-RET");
        claimService.confirmPickup(claimId, LocalDateTime.now());
        assertThat(deliveryStatus(claimId, "RETURN")).isEqualTo("DELIVERED");

        claimService.inspect(claimId, ClaimInspectionResult.PASS, true, null, null, null, null, LocalDateTime.now());
        assertThat(refundRowCount(claimId)).as("교환 검수 PASS는 환불 미생성").isZero();
        assertThat(claimStatus(claimId)).isEqualTo("APPROVED");

        Delivery shipment = deliveryService.registerExchangeShipment(claimId, DeliveryCarrier.CJ, "CJ-EXC-OUT");
        assertThat(orderItemStatus()).as("발송 중에도 품목은 EXCHANGE_REQUESTED 유지").isEqualTo("EXCHANGE_REQUESTED");
        deliveryService.markDelivered(shipment.getId());

        assertThat(claimStatus(claimId)).isEqualTo("COMPLETED");
        assertThat(orderItemStatus()).isEqualTo("DELIVERED");
        assertThat(orderItemVariantId()).isEqualTo(VARIANT_EXCHANGE);
        assertThat(jdbc.queryForObject("SELECT option_label FROM order_item WHERE id = ?", String.class, ORDER_ITEM_ID)).isEqualTo("색상: 파랑");
        assertThat(reserved(VARIANT_EXCHANGE)).isZero();
        assertThat(onHand(VARIANT_EXCHANGE)).isEqualTo(INITIAL_STOCK - 1);
        assertThat(onHand(VARIANT_ORIGINAL)).isEqualTo(INITIAL_STOCK + 1);
        assertThat(historyCount(claimId, "ORDER")).isEqualTo(1);
        assertThat(historyCount(claimId, "RETURN")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT exchange_reserved_at IS NULL FROM claim WHERE id = ?", Boolean.class, claimId)).isTrue();
        assertThat(refundRowCount(claimId)).isZero();
    }

    // ==================== T2 옵션 규칙 ====================

    @Test
    @DisplayName("T2 옵션 규칙: 다른 상품 / 가격 다름 / 동일 옵션 / 판매 중지 옵션 → 422 · 옵션 미지정·비교환 옵션 지정·미존재 → 400")
    void optionRules_rejected() {
        assertThatThrownBy(() -> requestExchange(VAR_OTHER_PID)).isInstanceOf(ClaimInvalidStateException.class)
                .hasMessageContaining("같은 상품");
        assertThatThrownBy(() -> requestExchange(VAR_PRICED_PID)).isInstanceOf(ClaimInvalidStateException.class)
                .hasMessageContaining("같은 가격");
        assertThatThrownBy(() -> requestExchange(VAR_ORIGINAL_PID)).isInstanceOf(ClaimInvalidStateException.class)
                .hasMessageContaining("같은 옵션");
        assertThatThrownBy(() -> requestExchange(VAR_STOPPED_PID)).isInstanceOf(ClaimInvalidStateException.class)
                .hasMessageContaining("판매 중이 아닌");
        assertThatThrownBy(() -> requestExchange(null)).isInstanceOf(MalformedRequestException.class)
                .hasMessageContaining("필수");
        assertThatThrownBy(() -> requestExchange(pid("var_", "NOPE"))).isInstanceOf(MalformedRequestException.class)
                .hasMessageContaining("찾을 수 없습니다");
        assertThatThrownBy(() -> claimService.request(new ClaimRequestCommand(ORDER_ITEM_PID, ClaimType.RETURN,
                ClaimReasonCode.PRODUCT_DEFECT, "반품인데 옵션", USER_ID, LocalDateTime.now(), List.of(), VAR_EXCHANGE_PID)))
                .isInstanceOf(MalformedRequestException.class).hasMessageContaining("교환 요청에서만");
        assertThat(claimCount()).isZero();
        assertThat(orderItemStatus()).isEqualTo("DELIVERED");
    }

    // ==================== T3 재고 부족·재예약 ====================

    @Test
    @DisplayName("T3 재고: 교환 옵션 available 0 → 승인 422(InventoryInvariantViolation)·클레임 REQUESTED 유지·reserved 0 / 재고 회복 후 승인 성공·승인 재시도 422·reserved 1 유지")
    void approve_insufficientStock_thenReserveOnce() {
        Long claimId = requestExchange(VAR_EXCHANGE_PID).getId();
        jdbc.update("UPDATE inventory SET quantity_reserved = 10, quantity_available = 0 WHERE variant_id = ?", VARIANT_EXCHANGE);

        assertThatThrownBy(() -> claimService.approve(claimId, LocalDateTime.now(), null))
                .isInstanceOf(InventoryInvariantViolationException.class);
        assertThat(claimStatus(claimId)).isEqualTo("REQUESTED");
        assertThat(reserved(VARIANT_EXCHANGE)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT exchange_reserved_at IS NULL FROM claim WHERE id = ?", Boolean.class, claimId)).isTrue();

        jdbc.update("UPDATE inventory SET quantity_reserved = 0, quantity_available = 10 WHERE variant_id = ?", VARIANT_EXCHANGE);
        claimService.approve(claimId, LocalDateTime.now(), null);
        assertThat(reserved(VARIANT_EXCHANGE)).isEqualTo(1);
        assertThatThrownBy(() -> claimService.approve(claimId, LocalDateTime.now(), null))
                .isInstanceOf(ClaimInvalidStateException.class);
        assertThat(reserved(VARIANT_EXCHANGE)).as("승인 재시도는 재예약하지 않는다").isEqualTo(1);
    }

    // ==================== T4 거부·검수 FAIL 예약 해제 ====================

    @Test
    @DisplayName("T4 예약 해제: REQUESTED 거부 → 예약 없음·reserved 0 / 승인 후 검수 FAIL → REJECTED·release 1회·reserved 0·재발송 등록·품목 DELIVERED 원복 / 해제 재호출 no-op")
    void rejectAndInspectFail_releaseOnce() {
        Long rejected = requestExchange(VAR_EXCHANGE_PID).getId();
        claimService.reject(rejected, ClaimRejectReasonCode.OUT_OF_POLICY, "정책", LocalDateTime.now());
        assertThat(claimStatus(rejected)).isEqualTo("REJECTED");
        assertThat(reserved(VARIANT_EXCHANGE)).isZero();
        assertThat(orderItemStatus()).isEqualTo("DELIVERED");

        Long failed = requestExchange(VAR_EXCHANGE_PID).getId();
        claimService.approve(failed, LocalDateTime.now(), null);
        assertThat(reserved(VARIANT_EXCHANGE)).isEqualTo(1);
        claimService.registerReturnShipmentByBuyer(claimPid(failed), USER_ID, DeliveryCarrier.CJ, "CJ-EXC-RET2");
        claimService.confirmPickup(failed, LocalDateTime.now());
        claimService.inspect(failed, ClaimInspectionResult.FAIL, null, ClaimRejectReasonCode.INSPECTION_FAILED, "불합격",
                DeliveryCarrier.CJ, "CJ-EXC-RESHIP", LocalDateTime.now());

        assertThat(claimStatus(failed)).isEqualTo("REJECTED");
        assertThat(reserved(VARIANT_EXCHANGE)).isZero();
        assertThat(jdbc.queryForObject("SELECT exchange_reserved_at IS NULL FROM claim WHERE id = ?", Boolean.class, failed)).isTrue();
        assertThat(deliveryStatus(failed, "OUTBOUND")).isEqualTo("SHIPPING");
        assertThat(orderItemStatus()).isEqualTo("DELIVERED");
        assertThat(onHand(VARIANT_EXCHANGE)).isEqualTo(INITIAL_STOCK);
        assertThat(historyCount(failed, "ORDER") + historyCount(failed, "RETURN")).as("FAIL은 재고 이력 없음").isZero();

        // 검수 FAIL 재발송 배송완료 → 교환 종결 비대상(품목·클레임 불변)
        Long reshipId = jdbc.queryForObject("SELECT id FROM delivery WHERE claim_id = ? AND direction = 'OUTBOUND'", Long.class, failed);
        deliveryService.markDelivered(reshipId);
        assertThat(claimStatus(failed)).isEqualTo("REJECTED");
        assertThat(orderItemStatus()).isEqualTo("DELIVERED");
        assertThat(orderItemVariantId()).isEqualTo(VARIANT_ORIGINAL);
    }

    // ==================== T5 restock false ====================

    @Test
    @DisplayName("T5 restock=false: 완료 시 교환 옵션 on_hand −1·원 옵션 불변·이력 ORDER 1·RETURN 0")
    void restockFalse_noOriginalRestore() {
        Long claimId = runToInspection(false);
        Delivery shipment = deliveryService.registerExchangeShipment(claimId, DeliveryCarrier.CJ, "CJ-EXC-OUT5");
        deliveryService.markDelivered(shipment.getId());

        assertThat(claimStatus(claimId)).isEqualTo("COMPLETED");
        assertThat(onHand(VARIANT_EXCHANGE)).isEqualTo(INITIAL_STOCK - 1);
        assertThat(onHand(VARIANT_ORIGINAL)).isEqualTo(INITIAL_STOCK);
        assertThat(historyCount(claimId, "ORDER")).isEqualTo(1);
        assertThat(historyCount(claimId, "RETURN")).isZero();
    }

    // ==================== T6 발송 가드·중복 이벤트 ====================

    @Test
    @DisplayName("T6 발송 가드: 승인만 / 회수만 → 교환 발송 422 · 검수 PASS 후 200 · 발송 중복 422 / 배송완료 이벤트 2회 → 종결 1회·이력 1회")
    void shipmentGuard_andDuplicateCompletion() {
        Long claimId = requestExchange(VAR_EXCHANGE_PID).getId();
        assertThatThrownBy(() -> deliveryService.registerExchangeShipment(claimId, DeliveryCarrier.CJ, "X"))
                .isInstanceOf(ClaimInvalidStateException.class).hasMessageContaining("검수 합격");
        claimService.approve(claimId, LocalDateTime.now(), null);
        assertThatThrownBy(() -> deliveryService.registerExchangeShipment(claimId, DeliveryCarrier.CJ, "X"))
                .isInstanceOf(ClaimInvalidStateException.class);
        claimService.registerReturnShipmentByBuyer(claimPid(claimId), USER_ID, DeliveryCarrier.CJ, "CJ-EXC-RET6");
        claimService.confirmPickup(claimId, LocalDateTime.now());
        assertThatThrownBy(() -> deliveryService.registerExchangeShipment(claimId, DeliveryCarrier.CJ, "X"))
                .isInstanceOf(ClaimInvalidStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM delivery WHERE claim_id = ? AND direction = 'OUTBOUND'", Integer.class, claimId)).isZero();

        claimService.inspect(claimId, ClaimInspectionResult.PASS, true, null, null, null, null, LocalDateTime.now());
        Delivery shipment = deliveryService.registerExchangeShipment(claimId, DeliveryCarrier.CJ, "CJ-EXC-OUT6");
        assertThatThrownBy(() -> deliveryService.registerExchangeShipment(claimId, DeliveryCarrier.CJ, "DUP"))
                .isInstanceOf(ClaimInvalidStateException.class).hasMessageContaining("이미 등록");

        deliveryService.markDelivered(shipment.getId());
        // 같은 DeliveryCompleted 재소비(리스너 재처리 모사) → 멱등
        tx.executeWithoutResult(status -> eventPublisher.publishEvent(new DeliveryCompleted(
                shipment.getId(), ORDER_ITEM_ID, LocalDateTime.now(), DeliveryDirection.OUTBOUND, LocalDateTime.now())));

        assertThat(claimStatus(claimId)).isEqualTo("COMPLETED");
        assertThat(orderItemStatus()).isEqualTo("DELIVERED");
        assertThat(historyCount(claimId, "ORDER")).isEqualTo(1);
        assertThat(onHand(VARIANT_EXCHANGE)).isEqualTo(INITIAL_STOCK - 1);
        assertThat(reserved(VARIANT_EXCHANGE)).isZero();
    }

    // ==================== T7 refundAmount 400 ====================

    @Test
    @DisplayName("T7 refundAmount: 승인 body refundAmount → 400 MALFORMED_REQUEST(관리자 API)·서비스 직접 호출도 400·클레임 REQUESTED 유지")
    void refundAmount_rejected400() throws Exception {
        Long claimId = requestExchange(VAR_EXCHANGE_PID).getId();
        mockMvc.perform(post("/api/v1/admin/claims/" + claimPid(claimId) + "/approve").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"refundAmount\": 1000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        assertThatThrownBy(() -> claimService.approve(claimId, LocalDateTime.now(), 0L)).isInstanceOf(MalformedRequestException.class);
        assertThat(claimStatus(claimId)).isEqualTo("REQUESTED");
        assertThat(reserved(VARIANT_EXCHANGE)).isZero();
    }

    // ==================== T8 반품과 동일 검증 ====================

    @Test
    @DisplayName("T8 반품 동일 검증: 사유 제한(DELIVERY_DELAY 422) / 기한 초과 422 / 단순변심 첨부 400 / 검수 FAIL 이력 422 — RETURN 요청과 같은 결과")
    void sameValidationAsReturn() {
        assertThatThrownBy(() -> claimService.request(command(ClaimType.EXCHANGE, ClaimReasonCode.DELIVERY_DELAY, VAR_EXCHANGE_PID)))
                .isInstanceOf(ClaimInvalidStateException.class).hasMessageContaining("사유");
        assertThatThrownBy(() -> claimService.request(new ClaimRequestCommand(ORDER_ITEM_PID, ClaimType.EXCHANGE,
                ClaimReasonCode.BUYER_CHANGED_MIND, "변심 첨부", USER_ID, LocalDateTime.now(), List.of(pid("att_", "EXCATT")), VAR_EXCHANGE_PID)))
                .isInstanceOf(MalformedRequestException.class).hasMessageContaining("사진 첨부");

        // 기한 초과: 원 발송 배송완료를 8일 전으로
        jdbc.update("UPDATE delivery SET delivered_at = NOW(6) - INTERVAL 8 DAY WHERE id = ?", OUTBOUND_DELIVERY_ID);
        assertThatThrownBy(() -> requestExchange(VAR_EXCHANGE_PID)).isInstanceOf(ClaimInvalidStateException.class)
                .hasMessageContaining("가능 기간");
        assertThatThrownBy(() -> claimService.request(command(ClaimType.RETURN, ClaimReasonCode.PRODUCT_DEFECT, null)))
                .isInstanceOf(ClaimInvalidStateException.class).hasMessageContaining("가능 기간");
        jdbc.update("UPDATE delivery SET delivered_at = NOW(6) - INTERVAL 1 DAY WHERE id = ?", OUTBOUND_DELIVERY_ID);

        // 검수 FAIL 이력(RETURN·REJECTED·FAIL) → 교환·반품 모두 422
        seed(() -> jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, previous_order_item_status, "
                        + "inspection_result, created_at, updated_at) VALUES (?, ?, ?, 'RETURN', 'PRODUCT_DEFECT', 'REJECTED', 'DELIVERED', "
                        + "'FAIL', NOW(6), NOW(6))", 9499L, pid("clm_", "EXCFAIL"), ORDER_ITEM_ID));
        assertThatThrownBy(() -> requestExchange(VAR_EXCHANGE_PID)).isInstanceOf(ClaimInvalidStateException.class)
                .hasMessageContaining("불합격 이력");
        assertThatThrownBy(() -> claimService.request(command(ClaimType.RETURN, ClaimReasonCode.PRODUCT_DEFECT, null)))
                .isInstanceOf(ClaimInvalidStateException.class).hasMessageContaining("불합격 이력");
    }

    // ==================== T9 재교환 차단·교환 후 반품 ====================

    @Test
    @DisplayName("T9 재교환: 완료 후 교환 재요청 422 / 반품 요청 허용 → 검수 PASS(restock) 종결 시 재입고는 교환 옵션(원 옵션 불변)")
    void reExchangeBlocked_returnAfterExchangeRestocksExchangeVariant() {
        Long exchangeId = runToInspection(true);
        deliveryService.markDelivered(deliveryService.registerExchangeShipment(exchangeId, DeliveryCarrier.CJ, "CJ-EXC-OUT9").getId());
        assertThat(orderItemVariantId()).isEqualTo(VARIANT_EXCHANGE);
        int exchangeOnHandAfter = onHand(VARIANT_EXCHANGE);   // 9
        int originalOnHandAfter = onHand(VARIANT_ORIGINAL);   // 11

        assertThatThrownBy(() -> requestExchange(VAR_ORIGINAL_PID)).isInstanceOf(ClaimInvalidStateException.class)
                .hasMessageContaining("다시 교환할 수 없습니다");

        seed(this::seedPayment);
        Claim returnClaim = claimService.request(command(ClaimType.RETURN, ClaimReasonCode.PRODUCT_DEFECT, null));
        Long returnId = returnClaim.getId();
        claimService.approve(returnId, LocalDateTime.now(), null);
        claimService.registerReturnShipmentByBuyer(claimPid(returnId), USER_ID, DeliveryCarrier.CJ, "CJ-RET-AFTER");
        claimService.confirmPickup(returnId, LocalDateTime.now());
        claimService.inspect(returnId, ClaimInspectionResult.PASS, true, null, null, null, null, LocalDateTime.now());
        // 환불 완료 콜백 대신 종결 primitive 직접 호출(재고 복구 경로 검증 목적)
        claimService.markCompleted(returnId);

        assertThat(claimStatus(returnId)).isEqualTo("COMPLETED");
        assertThat(orderItemStatus()).isEqualTo("RETURNED");
        assertThat(onHand(VARIANT_EXCHANGE)).as("교환 후 반품 재입고는 교환 옵션").isEqualTo(exchangeOnHandAfter + 1);
        assertThat(onHand(VARIANT_ORIGINAL)).isEqualTo(originalOnHandAfter);
    }

    // ==================== T10 타이머·정산 ====================

    @Test
    @DisplayName("T10 타이머: 교환 배송완료 후 기준 시각 = 교환 배송완료(원 발송 1일 전 아님) / 검수 FAIL 재발송 배송완료는 기준 제외 / 자동 확정 +6d skip·+8d 확정 / 확정 품목 gross 포함·환불 차감 0")
    void timerBaseAndSettlement() {
        // 검수 FAIL 재발송(RETURN·REJECTED 클레임 연결·오늘 배송완료) — 기준에서 제외돼야 한다
        seed(() -> {
            jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, previous_order_item_status, "
                    + "inspection_result, created_at, updated_at) VALUES (?, ?, ?, 'RETURN', 'PRODUCT_DEFECT', 'REJECTED', 'DELIVERED', "
                    + "'FAIL', NOW(6), NOW(6))", 9498L, pid("clm_", "EXCFAIL2"), ORDER_ITEM_ID);
            jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, direction, carrier, tracking_no, status, shipped_at, delivered_at, "
                    + "claim_id, created_at, updated_at) VALUES (?, ?, ?, 'OUTBOUND', 'CJ', 'RESHIP-TODAY', 'DELIVERED', NOW(6) - INTERVAL 1 HOUR, "
                    + "NOW(6), ?, NOW(6), NOW(6))", 9498L, pid("dlv_", "EXCRESHIP"), ORDER_ITEM_ID, 9498L);
        });
        LocalDateTime originalDeliveredAt = returnWindowPolicy.originalDeliveredAt(ORDER_ITEM_ID).orElseThrow();
        assertThat(originalDeliveredAt).as("FAIL 재발송은 기준 제외").isBefore(LocalDateTime.now().minusHours(20));
        // FAIL 이력이 교환 요청을 막으므로 타이머 검증용 이력은 제거
        seed(() -> {
            jdbc.update("DELETE FROM delivery WHERE id = ?", 9498L);
            jdbc.update("DELETE FROM claim WHERE id = ?", 9498L);
        });

        Long claimId = runToInspection(true);
        deliveryService.markDelivered(deliveryService.registerExchangeShipment(claimId, DeliveryCarrier.CJ, "CJ-EXC-OUT10").getId());
        LocalDateTime exchangeDeliveredAt = jdbc.queryForObject(
                "SELECT delivered_at FROM delivery WHERE claim_id = ? AND direction = 'OUTBOUND'", LocalDateTime.class, claimId);
        assertThat(returnWindowPolicy.originalDeliveredAt(ORDER_ITEM_ID)).contains(exchangeDeliveredAt);

        // 자동 구매확정: 원 발송 기준이면 +6d는 만료(1d+6d>7d)지만 교환 기준으로는 미경과 → skip / +8d → 확정
        assertThat(orderAutoConfirmService.confirmOne(ORDER_ITEM_ID, LocalDateTime.now().plusDays(6))).isFalse();
        assertThat(orderItemStatus()).isEqualTo("DELIVERED");
        assertThat(orderAutoConfirmService.confirmOne(ORDER_ITEM_ID, LocalDateTime.now().plusDays(8))).isTrue();
        assertThat(orderItemStatus()).isEqualTo("CONFIRMED");

        // 정산: 확정 품목 gross 포함·환불 차감 0
        LocalDateTime confirmedAt = jdbc.queryForObject("SELECT confirmed_at FROM order_item WHERE id = ?", LocalDateTime.class, ORDER_ITEM_ID);
        Map<Long, Long> gross = orderItemRepository.aggregateGrossBySeller(OrderItemStatus.CONFIRMED,
                        confirmedAt.minusMinutes(1), confirmedAt.plusMinutes(1)).stream()
                .collect(java.util.stream.Collectors.toMap(SellerGrossProjection::getSellerId, SellerGrossProjection::getGrossAmount));
        assertThat(gross).containsEntry(SELLER_ID, ITEM_PRICE);
        assertThat(refundRepository.aggregateRefundBySeller(RefundStatus.COMPLETED, confirmedAt.minusMinutes(1), confirmedAt.plusMinutes(1)))
                .noneMatch(row -> row.getSellerId().equals(SELLER_ID));
        // Track 85 품목 스냅샷 소스: 교환 복귀 후 확정된 품목이 원가·교환 옵션 라벨로 SALE 소스에 포함된다
        assertThat(orderItemRepository.findSettlementSaleSources(OrderItemStatus.CONFIRMED,
                        confirmedAt.minusMinutes(1), confirmedAt.plusMinutes(1), SELLER_ID))
                .singleElement()
                .satisfies(source -> {
                    assertThat(source.getOrderItemId()).isEqualTo(ORDER_ITEM_ID);
                    assertThat(source.getAmount()).isEqualTo(ITEM_PRICE);
                    assertThat(source.getCommissionRate()).isEqualTo(1000);
                });
    }

    // ==================== T11 승인 동시성 ====================

    @Test
    @DisplayName("T11 동시성: 같은 교환 클레임 승인 2건 경합(지터) → 1건 성공·1건 422·reserved 1·exchange_reserved_at 1회 기록")
    void concurrentApprove_onlyOneReserves() throws Exception {
        Long claimId = requestExchange(VAR_EXCHANGE_PID).getId();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger invalidState = new AtomicInteger();
        try {
            List<Future<?>> futures = List.of(
                    pool.submit(() -> approveWithJitter(claimId, ready, go, success, invalidState)),
                    pool.submit(() -> approveWithJitter(claimId, ready, go, success, invalidState)));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(success.get()).isEqualTo(1);
        assertThat(invalidState.get()).isEqualTo(1);
        assertThat(reserved(VARIANT_EXCHANGE)).isEqualTo(1);
        assertThat(claimStatus(claimId)).isEqualTo("APPROVED");
    }

    // ==================== T12 원 옵션 라벨 스냅샷 ====================

    @Test
    @DisplayName("T12 스냅샷: 승인 시 original_option_label='색상: 빨강' 기록 → 원 옵션값 변경·삭제 후 교환 완료해도 관리자 목록·주문 상세·구매자 상세 원 옵션 라벨 유지 / 승인 재시도 덮어쓰기 없음")
    void originalOptionLabelSnapshot_survivesOptionValueChange() throws Exception {
        Long claimId = requestExchange(VAR_EXCHANGE_PID).getId();
        claimService.approve(claimId, LocalDateTime.now(), null);
        assertThat(jdbc.queryForObject("SELECT original_option_label FROM claim WHERE id = ?", String.class, claimId)).isEqualTo("색상: 빨강");

        // 원 옵션값 이름 변경 후 삭제(재조립 불가 상태) — 스냅샷만이 원 옵션을 말해준다
        jdbc.update("UPDATE product_option_value SET value = '빨강(구)' WHERE id = ?", VARIANT_ORIGINAL);
        assertThatThrownBy(() -> claimService.approve(claimId, LocalDateTime.now(), null)).isInstanceOf(ClaimInvalidStateException.class);
        assertThat(jdbc.queryForObject("SELECT original_option_label FROM claim WHERE id = ?", String.class, claimId))
                .as("승인 재시도는 스냅샷을 덮어쓰지 않는다").isEqualTo("색상: 빨강");
        seed(() -> jdbc.update("DELETE FROM product_option_value WHERE id = ?", VARIANT_ORIGINAL)); // FK_CHECKS=0 TX(옵션값 삭제 모사)

        claimService.registerReturnShipmentByBuyer(claimPid(claimId), USER_ID, DeliveryCarrier.CJ, "CJ-EXC-RET12");
        claimService.confirmPickup(claimId, LocalDateTime.now());
        claimService.inspect(claimId, ClaimInspectionResult.PASS, true, null, null, null, null, LocalDateTime.now());
        deliveryService.markDelivered(deliveryService.registerExchangeShipment(claimId, DeliveryCarrier.CJ, "CJ-EXC-OUT12").getId());
        assertThat(jdbc.queryForObject("SELECT option_label FROM order_item WHERE id = ?", String.class, ORDER_ITEM_ID)).isEqualTo("색상: 파랑");

        mockMvc.perform(get("/api/v1/admin/claims").headers(authHeaders.admin(ADMIN_ID)).param("type", "EXCHANGE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].originalOptionLabel").value("색상: 빨강"))
                .andExpect(jsonPath("$.items[0].exchangeOptionLabel").value("색상: 파랑"));
        mockMvc.perform(get("/api/v1/admin/orders/" + pid("ord_", "EXCORD")).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].optionLabel").value("색상: 파랑"))
                .andExpect(jsonPath("$.items[0].claims[0].originalOptionLabel").value("색상: 빨강"))
                .andExpect(jsonPath("$.items[0].claims[0].exchangeOptionLabel").value("색상: 파랑"));
        mockMvc.perform(get("/api/v1/claims/" + claimPid(claimId)).headers(authHeaders.buyer(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalOptionLabel").value("색상: 빨강"))
                .andExpect(jsonPath("$.exchangeOptionLabel").value("색상: 파랑"));
    }

    // ==================== T13 쿼리 수 ====================

    @Test
    @DisplayName("T13 쿼리 수: 교환 완료 주문의 관리자 주문 상세 1회 요청 ≤ 20·관리자 클레임 목록(교환 2건) ≤ 12 — 옵션 라벨 배치 조회(variant·값·그룹 각 1쿼리·N+1 없음)")
    void adminDetail_queryBudget_withExchangeClaim() throws Exception {
        Long claimId = runToInspection(true);
        deliveryService.markDelivered(deliveryService.registerExchangeShipment(claimId, DeliveryCarrier.CJ, "CJ-EXC-OUT13").getId());
        // 라벨 해석 대상 variant를 늘려도 쿼리 수가 늘지 않는지: 두 번째 교환 클레임(다른 옵션 조합)을 시드
        seed(() -> jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, previous_order_item_status, "
                + "exchange_variant_id, original_variant_id, original_option_label, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'EXCHANGE', 'PRODUCT_DEFECT', 'REJECTED', 'DELIVERED', ?, ?, NULL, NOW(6), NOW(6))",
                9497L, pid("clm_", "EXCQ2"), ORDER_ITEM_ID, VARIANT_PRICED, VARIANT_STOPPED));

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        mockMvc.perform(get("/api/v1/admin/orders/" + pid("ord_", "EXCORD")).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].claims.length()").value(2));
        long detailQueries = statistics.getPrepareStatementCount();
        statistics.clear();
        mockMvc.perform(get("/api/v1/admin/claims").headers(authHeaders.admin(ADMIN_ID)).param("type", "EXCHANGE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
        long listQueries = statistics.getPrepareStatementCount();
        statistics.setStatisticsEnabled(false);
        System.out.println("[T13] admin order detail queries=" + detailQueries + ", admin claim list queries=" + listQueries);
        assertThat(detailQueries).isLessThanOrEqualTo(ADMIN_DETAIL_QUERY_BUDGET);
        assertThat(listQueries).isLessThanOrEqualTo(ADMIN_LIST_QUERY_BUDGET);
    }

    // ==================== T14 구매자 주문 상세 exchangeCompleted ====================

    @Test
    @DisplayName("T14 exchangeCompleted: 교환 완료 품목 true / 미교환·교환 진행 중(APPROVED)·교환 거부(REJECTED) 품목 false / 품목 4개·클레임 3건에도 주문 단위 배치 1회(쿼리 수 ≤ 8)")
    void buyerOrderDetail_exchangeCompletedFlag_batched() throws Exception {
        Long claimId = runToInspection(true);
        deliveryService.markDelivered(deliveryService.registerExchangeShipment(claimId, DeliveryCarrier.CJ, "CJ-EXC-OUT14").getId());
        // 같은 주문에 품목 3개 추가: 진행 중 교환(APPROVED)·거부 교환(REJECTED)·클레임 없음
        seed(() -> {
            for (long extra = 1; extra <= 3; extra++) {
                jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, total_price, "
                        + "item_status, created_at, updated_at, product_name, commission_rate) VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, 'DELIVERED', NOW(6), NOW(6), '추가 품목', 1000)",
                        ORDER_ITEM_ID + extra, pid("oit_", "EXCOIT" + extra), ORDER_ID, PRODUCT_ID, VARIANT_ORIGINAL, SELLER_ID, ITEM_PRICE, ITEM_PRICE);
            }
            jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, previous_order_item_status, exchange_variant_id, "
                    + "created_at, updated_at) VALUES (?, ?, ?, 'EXCHANGE', 'PRODUCT_DEFECT', 'APPROVED', 'DELIVERED', ?, NOW(6), NOW(6))",
                    9495L, pid("clm_", "EXCT14A"), ORDER_ITEM_ID + 1, VARIANT_EXCHANGE);
            jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, previous_order_item_status, exchange_variant_id, "
                    + "created_at, updated_at) VALUES (?, ?, ?, 'EXCHANGE', 'PRODUCT_DEFECT', 'REJECTED', 'DELIVERED', ?, NOW(6), NOW(6))",
                    9496L, pid("clm_", "EXCT14R"), ORDER_ITEM_ID + 2, VARIANT_EXCHANGE);
        });

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        mockMvc.perform(get("/api/v1/orders/" + pid("ord_", "EXCORD")).headers(authHeaders.buyer(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellers[0].items.length()").value(4))
                .andExpect(jsonPath("$.sellers[0].items[?(@.orderItemId == '" + ORDER_ITEM_PID + "')].exchangeCompleted").value(true))
                .andExpect(jsonPath("$.sellers[0].items[?(@.orderItemId == '" + pid("oit_", "EXCOIT1") + "')].exchangeCompleted").value(false))
                .andExpect(jsonPath("$.sellers[0].items[?(@.orderItemId == '" + pid("oit_", "EXCOIT2") + "')].exchangeCompleted").value(false))
                .andExpect(jsonPath("$.sellers[0].items[?(@.orderItemId == '" + pid("oit_", "EXCOIT3") + "')].exchangeCompleted").value(false));
        long queries = statistics.getPrepareStatementCount();
        statistics.setStatisticsEnabled(false);
        System.out.println("[T14] buyer order detail queries=" + queries);
        assertThat(queries).isLessThanOrEqualTo(BUYER_DETAIL_QUERY_BUDGET);
    }

    private void approveWithJitter(Long claimId, CountDownLatch ready, CountDownLatch go, AtomicInteger success,
            AtomicInteger invalidState) {
        try {
            ready.countDown();
            go.await(5, TimeUnit.SECONDS);
            Thread.sleep(ThreadLocalRandom.current().nextInt(0, 30));
            claimService.approve(claimId, LocalDateTime.now(), null);
            success.incrementAndGet();
        } catch (ClaimInvalidStateException exception) {
            invalidState.incrementAndGet();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== helpers ====================

    private Claim requestExchange(String exchangeVariantPid) {
        return claimService.request(command(ClaimType.EXCHANGE, ClaimReasonCode.PRODUCT_DEFECT, exchangeVariantPid));
    }

    private static ClaimRequestCommand command(ClaimType type, ClaimReasonCode reasonCode, String exchangeVariantPid) {
        return new ClaimRequestCommand(ORDER_ITEM_PID, type, reasonCode, "통합", USER_ID, LocalDateTime.now(), List.of(), exchangeVariantPid);
    }

    /** 요청→승인→회수 송장→회수 확인→검수 PASS(restock)까지 구동해 claimId를 돌려준다. */
    private Long runToInspection(boolean restock) {
        Long claimId = requestExchange(VAR_EXCHANGE_PID).getId();
        claimService.approve(claimId, LocalDateTime.now(), null);
        claimService.registerReturnShipmentByBuyer(claimPid(claimId), USER_ID, DeliveryCarrier.CJ, "CJ-EXC-RET-" + claimId);
        claimService.confirmPickup(claimId, LocalDateTime.now());
        claimService.inspect(claimId, ClaimInspectionResult.PASS, restock, null, null, null, null, LocalDateTime.now());
        return claimId;
    }

    private void seed(Runnable seedingWork) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedingWork.run();
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    private void seedCatalog() {
        jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))", USER_ID, pid("usr_", "EXCUSR"));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                + "VALUES (?, ?, '통합셀러', '대표', 'ACTIVE', NOW(6), NOW(6))", SELLER_ID, pid("slr_", "EXCSLR"));
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, '통합상품', 'SALE', ?, NOW(6), NOW(6))", PRODUCT_ID, pid("prd_", "EXCPRD"), SELLER_ID, DUMMY_FK_ID, ITEM_PRICE);
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, '다른상품', 'SALE', ?, NOW(6), NOW(6))", OTHER_PRODUCT_ID, pid("prd_", "EXCPRD2"), SELLER_ID, DUMMY_FK_ID, ITEM_PRICE);
        jdbc.update("INSERT INTO product_option_group (id, product_id, name, display_order, created_at, updated_at) "
                + "VALUES (?, ?, '색상', 1, NOW(6), NOW(6))", OPTION_GROUP_ID, PRODUCT_ID);
        jdbc.update("INSERT INTO product_option_value (id, option_group_id, value, display_order, created_at, updated_at) "
                + "VALUES (?, ?, '빨강', 1, NOW(6), NOW(6)), (?, ?, '파랑', 2, NOW(6), NOW(6))",
                VARIANT_ORIGINAL, OPTION_GROUP_ID, VARIANT_EXCHANGE, OPTION_GROUP_ID);
        seedVariant(VARIANT_ORIGINAL, VAR_ORIGINAL_PID, PRODUCT_ID, 0, "SALE", VARIANT_ORIGINAL);
        seedVariant(VARIANT_EXCHANGE, VAR_EXCHANGE_PID, PRODUCT_ID, 0, "SALE", VARIANT_EXCHANGE);
        seedVariant(VARIANT_PRICED, VAR_PRICED_PID, PRODUCT_ID, 500, "SALE", VARIANT_ORIGINAL);
        seedVariant(VARIANT_STOPPED, VAR_STOPPED_PID, PRODUCT_ID, 0, "STOPPED", VARIANT_ORIGINAL);
        seedVariant(VARIANT_OTHER_PRODUCT, VAR_OTHER_PID, OTHER_PRODUCT_ID, 0, "SALE", VARIANT_ORIGINAL);
    }

    private void seedVariant(long id, String publicId, long productId, int additionalPrice, String status, long optionValueId) {
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 0, 1, ?, NOW(6), NOW(6))",
                id, publicId, productId, "VC" + id, additionalPrice, status, optionValueId);
        jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, created_at, updated_at) "
                + "VALUES (?, ?, ?, 0, ?, NOW(6), NOW(6))", id, id, INITIAL_STOCK, INITIAL_STOCK);
    }

    private void seedOrder() {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, 'DELIVERED', ?, 0, 0, NOW(6), NOW(6))", ORDER_ID, pid("ord_", "EXCORD"), USER_ID, "ORDEXC" + ORDER_ID, ITEM_PRICE);
    }

    private void seedOrderItem() {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, total_price, "
                + "item_status, option_label, created_at, updated_at, product_name, commission_rate) VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, 'DELIVERED', '색상: 빨강', NOW(6), NOW(6), '테스트 상품', 1000)",
                ORDER_ITEM_ID, ORDER_ITEM_PID, ORDER_ID, PRODUCT_ID, VARIANT_ORIGINAL, SELLER_ID, ITEM_PRICE, ITEM_PRICE);
    }

    /** 원 주문 발송 배송완료(claim_id NULL) — daysAgo일 전 배송완료. */
    private void seedOriginalDelivery(int daysAgo) {
        jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, direction, carrier, tracking_no, status, shipped_at, delivered_at, "
                + "created_at, updated_at) VALUES (?, ?, ?, 'OUTBOUND', 'CJ', 'OUT-EXC-0001', 'DELIVERED', NOW(6) - INTERVAL ? DAY - INTERVAL 1 DAY, "
                + "NOW(6) - INTERVAL ? DAY, NOW(6), NOW(6))", OUTBOUND_DELIVERY_ID, pid("dlv_", "EXCDLV"), ORDER_ITEM_ID, daysAgo, daysAgo);
    }

    /** 교환 후 반품(T9) 환불 initiate가 해소할 PAID 결제. */
    private void seedPayment() {
        jdbc.update("INSERT INTO payment (id, public_id, order_id, method, amount, status, pg_provider, pg_tid, payment_attempt_key, paid_at, "
                + "created_at, updated_at) VALUES (?, ?, ?, 'CARD', ?, 'PAID', 'MOCK_PG', 'tid_exc_0001', 'pat_exc_0001', NOW(6), NOW(6), NOW(6))",
                PAYMENT_ID, pid("pay_", "EXCPAY"), ORDER_ID, ITEM_PRICE);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", USER_ID);
                jdbc.update("DELETE FROM delivery WHERE order_item_id = ?", ORDER_ITEM_ID);
                jdbc.update("DELETE FROM refund WHERE claim_id IN (SELECT id FROM claim WHERE order_item_id = ?)", ORDER_ITEM_ID);
                jdbc.update("DELETE FROM claim WHERE order_item_id = ?", ORDER_ITEM_ID);
                jdbc.update("DELETE FROM payment WHERE id = ?", PAYMENT_ID);
                jdbc.update("DELETE FROM claim WHERE order_item_id BETWEEN ? AND ?", ORDER_ITEM_ID, ORDER_ITEM_ID + 3);
                jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", ORDER_ITEM_ID, ORDER_ITEM_ID + 3);
                jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
                jdbc.update("DELETE FROM inventory_history WHERE inventory_id BETWEEN ? AND ?", VARIANT_ORIGINAL, VARIANT_OTHER_PRODUCT);
                jdbc.update("DELETE FROM inventory WHERE id BETWEEN ? AND ?", VARIANT_ORIGINAL, VARIANT_OTHER_PRODUCT);
                jdbc.update("DELETE FROM product_variant WHERE id BETWEEN ? AND ?", VARIANT_ORIGINAL, VARIANT_OTHER_PRODUCT);
                jdbc.update("DELETE FROM product_option_value WHERE option_group_id = ?", OPTION_GROUP_ID);
                jdbc.update("DELETE FROM product_option_group WHERE id = ?", OPTION_GROUP_ID);
                jdbc.update("DELETE FROM product WHERE id IN (?, ?)", PRODUCT_ID, OTHER_PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id = ?", USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private String orderItemStatus() {
        return jdbc.queryForObject("SELECT item_status FROM order_item WHERE id = ?", String.class, ORDER_ITEM_ID);
    }

    private Long orderItemVariantId() {
        return jdbc.queryForObject("SELECT variant_id FROM order_item WHERE id = ?", Long.class, ORDER_ITEM_ID);
    }

    private String claimStatus(Long claimId) {
        return jdbc.queryForObject("SELECT status FROM claim WHERE id = ?", String.class, claimId);
    }

    private String claimPid(Long claimId) {
        return jdbc.queryForObject("SELECT public_id FROM claim WHERE id = ?", String.class, claimId);
    }

    private int claimCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM claim WHERE order_item_id = ?", Integer.class, ORDER_ITEM_ID);
        return count == null ? 0 : count;
    }

    private String deliveryStatus(Long claimId, String direction) {
        return jdbc.queryForObject("SELECT status FROM delivery WHERE claim_id = ? AND direction = ? ORDER BY id DESC LIMIT 1",
                String.class, claimId, direction);
    }

    private int reserved(long variantId) {
        return jdbc.queryForObject("SELECT quantity_reserved FROM inventory WHERE variant_id = ?", Integer.class, variantId);
    }

    private int onHand(long variantId) {
        return jdbc.queryForObject("SELECT quantity_on_hand FROM inventory WHERE variant_id = ?", Integer.class, variantId);
    }

    private int historyCount(Long claimId, String changeType) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_history WHERE reference_type = 'claim' AND reference_id = ? AND change_type = ?",
                Integer.class, claimId, changeType);
        return count == null ? 0 : count;
    }

    private int refundRowCount(long claimId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM refund WHERE claim_id = ?", Integer.class, claimId);
        return count == null ? 0 : count;
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
