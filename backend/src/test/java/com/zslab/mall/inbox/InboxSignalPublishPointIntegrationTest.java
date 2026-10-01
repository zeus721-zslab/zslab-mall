package com.zslab.mall.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.claim.enums.ClaimRejectReasonCode;
import com.zslab.mall.claim.service.ClaimService;
import com.zslab.mall.common.security.ActorRole;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.service.DeliveryService;
import com.zslab.mall.inbox.collector.InboxViewer;
import com.zslab.mall.inbox.enums.InboxItemType;
import com.zslab.mall.inbox.service.InboxSnoozeService;
import com.zslab.mall.inquiry.enums.InquiryCategory;
import com.zslab.mall.inquiry.service.InquiryService;
import com.zslab.mall.inventory.service.InventoryService;
import com.zslab.mall.order.service.OrderShippingService;
import com.zslab.mall.product.service.ProductApprovalService;
import com.zslab.mall.productquestion.service.SellerProductQuestionService;
import com.zslab.mall.reconciliation.service.AdminReconciliationIssueService;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.seller.service.AdminSellerCommandService;
import com.zslab.mall.settlement.service.SettlementTransitionService;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 인박스 변경 신호 대표 발행 지점 통합 테스트(D-249·실 서버·실 MariaDB). 유형마다 실제 서비스 메서드 하나를 불러, 커밋 후 신호가 정해진 범위
 * (관리자 전체 / 지정 셀러 / sellerId가 없는 경로의 셀러 전체)에만 도착하는지 스트림으로 확인한다.
 *
 * <p><b>시드</b>: 셀러 A(구성원 A1)·B(구성원 B1)·입점 대기 셀러 P · 구매자 · 셀러 A 상품(판매중·승인 대기)·옵션·재고 · 주문 1건(품목 1·결제 PAID).
 * 품목 상태·클레임·배송·질문·정산·불일치는 테스트마다 넣는다. 시드는 ? 바인딩(SQL injection 없음).
 */
class InboxSignalPublishPointIntegrationTest extends InboxStreamTestSupport {

    private static final long ADMIN_ID = 949190L;
    private static final long SELLER_A = 949101L;
    private static final long SELLER_B = 949102L;
    private static final long SELLER_PENDING = 949103L;
    private static final long USER_A1 = 949111L;
    private static final long USER_B1 = 949112L;
    private static final long BUYER_ID = 949120L;
    private static final long PRODUCT_ID = 949101L;
    private static final long PRODUCT_PENDING = 949102L;
    private static final long VARIANT_ID = 949101L;
    private static final long INVENTORY_ID = 949101L;
    private static final long ORDER_ID = 949101L;
    private static final long ORDER_ITEM_ID = 949101L;
    private static final long PAYMENT_ID = 949101L;
    private static final long CLAIM_ID = 949101L;
    private static final long DELIVERY_ID = 949101L;
    private static final long QUESTION_ID = 949101L;
    private static final long SETTLEMENT_ID = 949101L;
    private static final long ISSUE_ID = 949101L;
    private static final long DUMMY_FK_ID = 949100L;
    private static final long CATEGORY_ID = 949100L;
    private static final long ITEM_PRICE = 10_000L;
    private static final AuditContext ADMIN = AuditContext.of(ADMIN_ID, "ADMIN");

    private static final String SELLER_PENDING_PID = pid("slr_", "ISPP");
    private static final String PRODUCT_PENDING_PID = pid("prd_", "ISPPEND");
    private static final String QUESTION_PID = pid("pqn_", "ISPQ");

    @Autowired
    private ClaimService claimService;
    @Autowired
    private DeliveryService deliveryService;
    @Autowired
    private OrderShippingService orderShippingService;
    @Autowired
    private SellerProductQuestionService sellerProductQuestionService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private InquiryService inquiryService;
    @Autowired
    private AdminSellerCommandService adminSellerCommandService;
    @Autowired
    private ProductApprovalService productApprovalService;
    @Autowired
    private SettlementTransitionService settlementTransitionService;
    @Autowired
    private AdminReconciliationIssueService adminReconciliationIssueService;
    @Autowired
    private InboxSnoozeService inboxSnoozeService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        seedBase();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("P1 클레임 접수 이탈(거부) + 발송 대기 재진입: 관리자 전체 · 품목 셀러 A만")
    void claimRejected() throws Exception {
        seed(() -> {
            jdbc.update("UPDATE order_item SET item_status = 'CANCEL_REQUESTED' WHERE id = ?", ORDER_ITEM_ID);
            seedClaim("REQUESTED");
        });
        Streams streams = openStreams();

        claimService.reject(CLAIM_ID, ClaimRejectReasonCode.OUT_OF_POLICY, null, LocalDateTime.now());

        streams.expect(true, true, false);
    }

    @Test
    @DisplayName("P2 클레임 후속 · 자동 환불: 승인 커밋 + AFTER_COMMIT→REQUIRES_NEW 환불 개시 커밋 + Mock 완료 콜백 커밋이 각각 신호")
    void autoRefundCommitsSignalSeparately() throws Exception {
        seed(() -> {
            jdbc.update("UPDATE order_item SET item_status = 'CANCEL_REQUESTED' WHERE id = ?", ORDER_ITEM_ID);
            seedClaim("REQUESTED");
        });
        Streams streams = openStreams();

        claimService.approve(CLAIM_ID, LocalDateTime.now(), null);

        assertThat(jdbc.queryForObject("SELECT status FROM refund WHERE claim_id = ?", String.class, CLAIM_ID))
                .isEqualTo("COMPLETED");
        // 관리자: 승인 · 환불 개시(REQUIRES_NEW) · 완료 콜백(REQUIRES_NEW) 세 커밋 / 셀러 A: 완료 콜백 커밋의 재입고만
        assertThat(streams.admin().countChanged(SILENCE_WAIT)).isEqualTo(3);
        assertThat(streams.sellerA().countChanged(SILENCE_WAIT)).isEqualTo(1);
        assertThat(streams.sellerB().awaitChanged(Duration.ZERO)).isFalse();
    }

    @Test
    @DisplayName("P3 장기 배송중 이탈(관리자 배송완료): 관리자 전체 · sellerId가 없는 경로라 셀러 전체(A·B)")
    void longShippingDeliveredByAdmin() throws Exception {
        seed(() -> {
            jdbc.update("UPDATE order_item SET item_status = 'SHIPPING' WHERE id = ?", ORDER_ITEM_ID);
            jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, direction, carrier, tracking_no, status, shipped_at, "
                    + "created_at, updated_at) VALUES (?, ?, ?, 'OUTBOUND', 'CJ', 'ISPTRK1', 'SHIPPING', NOW(6) - INTERVAL 4 DAY, "
                    + "NOW(6), NOW(6))", DELIVERY_ID, pid("dlv_", "ISP"), ORDER_ITEM_ID);
        });
        Streams streams = openStreams();

        deliveryService.markDeliveredByAdmin(DELIVERY_ID, ADMIN);

        streams.expect(true, true, true);
    }

    @Test
    @DisplayName("P4 발송 대기 이탈(셀러 송장 등록): 셀러 A만")
    void deliveryReadyShipped() throws Exception {
        Streams streams = openStreams();

        orderShippingService.prepareShipment(SELLER_A, ORDER_ITEM_ID, DeliveryCarrier.CJ, "ISPTRK2");

        streams.expect(false, true, false);
    }

    @Test
    @DisplayName("P5 Q&A 미답변 이탈(셀러 답변): 셀러 A만")
    void questionAnswered() throws Exception {
        seed(() -> jdbc.update("INSERT INTO product_question (id, public_id, product_id, buyer_id, content, status, created_at, "
                + "updated_at) VALUES (?, ?, ?, ?, '배송은 언제 되나요?', 'VISIBLE', NOW(6), NOW(6))",
                QUESTION_ID, QUESTION_PID, PRODUCT_ID, BUYER_ID));
        Streams streams = openStreams();

        sellerProductQuestionService.answer(SELLER_A, USER_A1, QUESTION_PID, "내일 발송합니다.");

        streams.expect(false, true, false);
    }

    @Test
    @DisplayName("P6 재고 임박(셀러 입고): 셀러 A만")
    void lowStockInbound() throws Exception {
        Streams streams = openStreams();

        inventoryService.markInboundBySeller(SELLER_A, VARIANT_ID, 3, "입고", AuditContext.of(USER_A1, "SELLER"));

        streams.expect(false, true, false);
    }

    @Test
    @DisplayName("P7 1:1 문의 진입(구매자 등록): 관리자만")
    void inquiryCreated() throws Exception {
        Streams streams = openStreams();

        inquiryService.create(BUYER_ID, InquiryCategory.DELIVERY, "배송 문의 드립니다.", null);

        streams.expect(true, false, false);
    }

    @Test
    @DisplayName("P8 셀러 입점 심사 이탈(PENDING → ACTIVE): 관리자만")
    void sellerReviewed() throws Exception {
        Streams streams = openStreams();

        adminSellerCommandService.changeStatus(SELLER_PENDING_PID, SellerStatus.ACTIVE, "서류 확인", ADMIN);

        streams.expect(true, false, false);
    }

    @Test
    @DisplayName("P9 상품 승인 이탈(승인): 관리자만")
    void productApproved() throws Exception {
        Streams streams = openStreams();

        productApprovalService.approve(PRODUCT_PENDING_PID, ADMIN);

        streams.expect(true, false, false);
    }

    @Test
    @DisplayName("P10 정산 확정 이탈 · 지급 진입(확정): 관리자만")
    void settlementConfirmed() throws Exception {
        seed(() -> jdbc.update("INSERT INTO settlement (id, seller_id, period_start, period_end, gross_amount, fee_amount, "
                + "refund_amount, net_amount, commission_rate, status, created_at, updated_at) "
                + "VALUES (?, ?, '2026-08-01 00:00:00', '2026-08-31 23:59:59', 10000, 1000, 0, 9000, 1000, 'PENDING', NOW(6), NOW(6))",
                SETTLEMENT_ID, SELLER_A));
        Streams streams = openStreams();

        settlementTransitionService.confirm(SETTLEMENT_ID, ADMIN);

        streams.expect(true, false, false);
    }

    @Test
    @DisplayName("P11 정합성 불일치 이탈(관리자 해결): 관리자만")
    void reconciliationResolved() throws Exception {
        seed(() -> jdbc.update("INSERT INTO reconciliation_issue (id, issue_type, dedupe_key, order_id, status, detected_at, created_at) "
                + "VALUES (?, 'ITEM_STATE_DRIFT', 'isp:949101', ?, 'OPEN', NOW(6), NOW(6))", ISSUE_ID, ORDER_ID));
        Streams streams = openStreams();

        adminReconciliationIssueService.resolve(ISSUE_ID, "확인 완료", ADMIN);

        streams.expect(true, false, false);
    }

    @Test
    @DisplayName("P12 보류 해제(셀러): 그 셀러(A)만")
    void sellerUnsnooze() throws Exception {
        Streams streams = openStreams();

        inboxSnoozeService.unsnooze(InboxViewer.seller(SELLER_A, USER_A1), InboxItemType.LOW_STOCK, pid("var_", "ISP"));

        streams.expect(false, true, false);
    }

    private Streams openStreams() throws Exception {
        return new Streams(openReady(ADMIN_STREAM, ActorRole.ADMIN, ADMIN_ID),
                openReady(SELLER_STREAM, ActorRole.SELLER, USER_A1),
                openReady(SELLER_STREAM, ActorRole.SELLER, USER_B1));
    }

    private record Streams(SseStream admin, SseStream sellerA, SseStream sellerB) {

        /** 받아야 하는 스트림은 신호를 기다리고, 받지 말아야 하는 스트림은 침묵 구간 동안 신호가 없어야 한다. */
        void expect(boolean adminSignaled, boolean sellerASignaled, boolean sellerBSignaled) throws InterruptedException {
            assertThat(admin.awaitChanged(adminSignaled ? SIGNAL_WAIT : SILENCE_WAIT)).as("관리자").isEqualTo(adminSignaled);
            assertThat(sellerA.awaitChanged(sellerASignaled ? SIGNAL_WAIT : SILENCE_WAIT)).as("셀러 A").isEqualTo(sellerASignaled);
            assertThat(sellerB.awaitChanged(sellerBSignaled ? SIGNAL_WAIT : SILENCE_WAIT)).as("셀러 B").isEqualTo(sellerBSignaled);
        }
    }

    private void seed(Runnable statements) {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                statements.run();
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedBase() {
        seed(() -> {
            seedSeller(SELLER_A, "ISPA", "ACTIVE");
            seedSeller(SELLER_B, "ISPB", "ACTIVE");
            jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                    + "VALUES (?, ?, '입점대기셀러', '대표', 'PENDING', NOW(6), NOW(6))", SELLER_PENDING, SELLER_PENDING_PID);
            seedMember(USER_A1, SELLER_A, "ISPUA1");
            seedMember(USER_B1, SELLER_B, "ISPUB1");
            jdbc.update("INSERT INTO `user` (id, public_id, name, created_at, updated_at) VALUES (?, ?, '신호구매자', NOW(6), NOW(6))",
                    BUYER_ID, pid("usr_", "ISPBUY"));
            // 상품 승인은 전체 컬럼 UPDATE라 category FK가 다시 검사된다 — 실 카테고리 행을 둔다.
            jdbc.update("INSERT INTO category (id, display_name, depth, sort_order, created_at, updated_at) "
                    + "VALUES (?, '신호카테고리', 0, 0, NOW(6), NOW(6))", CATEGORY_ID);
            seedProduct(PRODUCT_ID, pid("prd_", "ISPSALE"), "SALE");
            seedProduct(PRODUCT_PENDING, PRODUCT_PENDING_PID, "PENDING");
            jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                    + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                    + "VALUES (?, ?, ?, 'VISP1', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                    VARIANT_ID, pid("var_", "ISPVAR"), PRODUCT_ID, DUMMY_FK_ID);
            jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, "
                    + "created_at, updated_at) VALUES (?, ?, 10, 0, 10, NOW(6), NOW(6))", INVENTORY_ID, VARIANT_ID);
            jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                    + "ordered_at, paid_at, created_at, updated_at) VALUES (?, ?, ?, 'ORDISP1', 'PAID', ?, 0, 0, NOW(6), NOW(6), "
                    + "NOW(6), NOW(6))", ORDER_ID, pid("ord_", "ISP"), BUYER_ID, ITEM_PRICE);
            jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                    + "total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                    + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, 'PAID', NOW(6), NOW(6), '신호상품', 1000)",
                    ORDER_ITEM_ID, pid("oit_", "ISP"), ORDER_ID, PRODUCT_ID, VARIANT_ID, SELLER_A, ITEM_PRICE, ITEM_PRICE);
            jdbc.update("INSERT INTO payment (id, public_id, order_id, method, amount, status, pg_provider, pg_tid, payment_attempt_key, "
                    + "paid_at, created_at, updated_at) VALUES (?, ?, ?, 'CARD', ?, 'PAID', 'MOCK_PG', 'tid_isp', 'pat_isp', NOW(6), "
                    + "NOW(6), NOW(6))", PAYMENT_ID, pid("pay_", "ISP"), ORDER_ID, ITEM_PRICE);
            jdbc.update("INSERT INTO order_shipping_snapshot (order_id, recipient_name, recipient_phone, zonecode, address_road, "
                    + "address_detail, created_at, updated_at) VALUES (?, '홍길동', '010-1234-5678', '06236', '서울 강남대로 1', "
                    + "'101호', NOW(6), NOW(6))", ORDER_ID);
        });
    }

    private void seedSeller(long sellerId, String tag, String status) {
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, '대표', ?, NOW(6), NOW(6))", sellerId, pid("slr_", tag), "신호셀러" + tag, status);
    }

    private void seedMember(long userId, long sellerId, String tag) {
        jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))", userId, pid("usr_", tag));
        jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'", userId, sellerId);
    }

    private void seedProduct(long productId, String publicId, String status) {
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, '신호상품', ?, ?, NOW(6), NOW(6))", productId, publicId, SELLER_A, CATEGORY_ID, status, ITEM_PRICE);
    }

    private void seedClaim(String status) {
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, previous_order_item_status, "
                + "requested_by, requested_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'CANCEL', 'BUYER_CHANGED_MIND', ?, 'PAID', ?, NOW(6), NOW(6), NOW(6))",
                CLAIM_ID, pid("clm_", "ISP"), ORDER_ITEM_ID, status, BUYER_ID);
    }

    private void cleanup() {
        seed(() -> {
            jdbc.update("DELETE FROM notification_log WHERE recipient_user_id IN (?, ?, ?)", BUYER_ID, USER_A1, USER_B1);
            jdbc.update("DELETE FROM audit_log WHERE actor_user_id IN (?, ?)", ADMIN_ID, USER_A1);
            jdbc.update("DELETE FROM reconciliation_issue WHERE id = ? OR order_id = ?", ISSUE_ID, ORDER_ID);
            jdbc.update("DELETE FROM settlement WHERE id = ?", SETTLEMENT_ID);
            jdbc.update("DELETE FROM inquiry WHERE buyer_id = ?", BUYER_ID);
            jdbc.update("DELETE FROM product_question WHERE id = ?", QUESTION_ID);
            jdbc.update("DELETE FROM inbox_snooze WHERE owner_user_id IN (?, ?)", USER_A1, USER_B1);
            jdbc.update("DELETE FROM refund WHERE claim_id = ?", CLAIM_ID);
            jdbc.update("DELETE FROM claim WHERE order_item_id = ?", ORDER_ITEM_ID);
            jdbc.update("DELETE FROM delivery WHERE order_item_id = ?", ORDER_ITEM_ID);
            jdbc.update("DELETE FROM inventory_history WHERE inventory_id = ?", INVENTORY_ID);
            jdbc.update("DELETE FROM inventory WHERE id = ?", INVENTORY_ID);
            jdbc.update("DELETE FROM payment WHERE order_id = ?", ORDER_ID);
            jdbc.update("DELETE FROM order_shipping_snapshot WHERE order_id = ?", ORDER_ID);
            jdbc.update("DELETE FROM order_item WHERE order_id = ?", ORDER_ID);
            jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
            jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
            jdbc.update("DELETE FROM product WHERE id IN (?, ?)", PRODUCT_ID, PRODUCT_PENDING);
            jdbc.update("DELETE FROM category WHERE id = ?", CATEGORY_ID);
            jdbc.update("DELETE FROM seller_user WHERE user_id IN (?, ?)", USER_A1, USER_B1);
            jdbc.update("DELETE FROM `user` WHERE id IN (?, ?, ?)", USER_A1, USER_B1, BUYER_ID);
            jdbc.update("DELETE FROM seller WHERE id IN (?, ?, ?)", SELLER_A, SELLER_B, SELLER_PENDING);
        });
    }
}
