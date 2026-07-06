package com.zslab.mall.claim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.claim.controller.request.ClaimRequestCommand;
import com.zslab.mall.claim.enums.ClaimReasonCode;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.service.ClaimService;
import com.zslab.mall.order.enums.OrderItemStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.zslab.mall.support.AbstractIntegrationTest;

/**
 * 클레임 이벤트 핸들러 E2E 통합 테스트(Track 9 PR-C·D-90 Q5 β·실 MariaDB·Flyway V1~V5). ClaimService 호출 →
 * 도메인 이벤트(AFTER_COMMIT) → ClaimRequested/Rejected/CompletedHandler → OrderItem 전이·Order.status 재계산까지
 * 실제 커밋 경로로 검증해 라이브 트랩을 차단한다. 특히 markCompleted → {@code ClaimCompleted} → CANCELLED 종결은
 * 중첩 AFTER_COMMIT(D-90 Q1·발행이 AFTER_COMMIT 핸들러 내부 REQUIRES_NEW에서 발생)을 실측 검증한다.
 *
 * <p><b>트랜잭션(D-90 Q5 β·RefundWebhookIntegrationTest 패턴)</b>: AFTER_COMMIT 핸들러는 실제 커밋 후에만 실행되므로
 * 클래스에 {@code @Transactional}을 두지 않는다. 시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}으로
 * 커밋하고, 검증은 {@link JdbcTemplate} 직접 조회(1차 캐시 무관)로 한다.
 *
 * <p><b>FK 정합(라이브 트랩 차단)</b>: 핸들러가 order_item·order를 UPDATE하면 Hibernate 전체 컬럼 UPDATE가 FK를 재검증한다.
 * 따라서 RefundWebhookIntegrationTest(UPDATE 없음·상위 그래프 생략)와 달리 order_item·order의 직접 FK 부모(user·seller·
 * product·product_variant)를 시드한다. 더 상위 FK(category·option_value)는 핸들러가 해당 테이블을 UPDATE하지 않으므로
 * FK_CHECKS=0 시드로 우회한다.
 *
 * <p><b>LT-02</b>: {@code SET FOREIGN_KEY_CHECKS=0}은 try-finally로 {@code =1} 복원과 1:1 짝을 이룬다(ClaimIntegrationTest 정합).
 */
class ClaimEventIntegrationTest extends AbstractIntegrationTest {

    private static final long USER_ID = 9101L;
    private static final long SELLER_ID = 9101L;
    private static final long PRODUCT_ID = 9101L;
    private static final long VARIANT_ID = 9101L;
    private static final long ORDER_ID = 9101L;
    private static final long ORDER_ITEM_ID = 9101L;
    private static final long CLAIM_ID = 9101L;
    /** product.category_id·variant.option1_value_id NOT NULL FK 충족용 더미. 핸들러가 해당 테이블을 UPDATE하지 않아 FK_CHECKS=0 시드로 우회한다. */
    private static final long DUMMY_FK_ID = 9101L;

    private static final String ORDER_ITEM_PID = pid("oit_", "EVTOIT");
    private static final String CLAIM_PID = pid("clm_", "EVTCLM");

    @Autowired
    private ClaimService claimService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("ClaimRequested e2e: request → AFTER_COMMIT 핸들러 → OrderItem PAID→CANCEL_REQUESTED")
    void claimRequested_transitionsItemToCancelRequested() {
        seed(() -> {
            seedCatalog();
            seedOrder("PAID");
            seedOrderItem(OrderItemStatus.PAID);
        });

        claimService.request(new ClaimRequestCommand(
                ORDER_ITEM_PID, ClaimType.CANCEL, ClaimReasonCode.BUYER_CHANGED_MIND, "통합", USER_ID, LocalDateTime.now()));

        assertThat(orderItemStatus()).isEqualTo("CANCEL_REQUESTED");
        // 단일 CANCEL_REQUESTED 품목 → Resolver 기본값 PAID 유지(state-machine §5·규칙 미매칭)
        assertThat(orderStatus()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("ClaimRejected e2e: reject → 핸들러 → OrderItem CANCEL_REQUESTED→PAID claim-lock release")
    void claimRejected_releasesItemToPaid() {
        seed(() -> {
            seedCatalog();
            seedOrder("PAID");
            seedOrderItem(OrderItemStatus.CANCEL_REQUESTED);
            seedClaim(ClaimStatus.REQUESTED);
        });

        claimService.reject(CLAIM_ID, LocalDateTime.now());

        assertThat(orderItemStatus()).isEqualTo("PAID");
        assertThat(claimStatus()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("ClaimCompleted e2e: markCompleted → 중첩 AFTER_COMMIT 핸들러 → OrderItem CANCELLED·Order PAID→CANCELLED")
    void claimCompleted_terminatesItemAndRecalculatesOrder() {
        seed(() -> {
            seedCatalog();
            seedOrder("PAID");
            seedOrderItem(OrderItemStatus.CANCEL_REQUESTED);
            seedClaim(ClaimStatus.APPROVED);
        });

        claimService.markCompleted(CLAIM_ID);

        assertThat(orderItemStatus()).isEqualTo("CANCELLED");
        assertThat(orderStatus()).isEqualTo("CANCELLED"); // 단일 CANCELLED 품목 → Resolver [5]
        assertThat(claimStatus()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("멱등 e2e: markCompleted 2회 → CANCELLED 종결 1회·2회차 no-op(ClaimCompleted 미발행·중복 전이 없음)")
    void claimCompleted_idempotent_secondCallNoOp() {
        seed(() -> {
            seedCatalog();
            seedOrder("PAID");
            seedOrderItem(OrderItemStatus.CANCEL_REQUESTED);
            seedClaim(ClaimStatus.APPROVED);
        });

        claimService.markCompleted(CLAIM_ID);
        claimService.markCompleted(CLAIM_ID); // 이미 COMPLETED → ClaimCompleted 미발행·핸들러 미발화

        assertThat(orderItemStatus()).isEqualTo("CANCELLED");
        assertThat(claimStatus()).isEqualTo("COMPLETED");
    }

    // ---------- seed·helpers ----------

    /** FK 비활성 상태로 시드하고 복원한다(LT-02 try-finally·ClaimIntegrationTest 정합). */
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

    /** order_item·order의 직접 FK 부모(user·seller·product·product_variant)를 시드한다. */
    private void seedCatalog() {
        jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                USER_ID, pid("usr_", "EVTUSR"));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, '통합셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                SELLER_ID, pid("slr_", "EVTSLR"));
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, '통합상품', 'SALE', 10000, NOW(6), NOW(6))",
                PRODUCT_ID, pid("prd_", "EVTPRD"), SELLER_ID, DUMMY_FK_ID);
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VCEVT', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                VARIANT_ID, pid("var_", "EVTVAR"), PRODUCT_ID, DUMMY_FK_ID);
    }

    private void seedOrder(String status) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                        + "discount_amount, shipping_fee, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 10000, 0, 0, NOW(6), NOW(6))",
                ORDER_ID, pid("ord_", "EVTORD"), USER_ID, "ORDEVT" + ORDER_ID, status);
    }

    private void seedOrderItem(OrderItemStatus itemStatus) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                        + "quantity, unit_price, total_price, item_status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, 10000, 10000, ?, NOW(6), NOW(6))",
                ORDER_ITEM_ID, ORDER_ITEM_PID, ORDER_ID, PRODUCT_ID, VARIANT_ID, SELLER_ID, itemStatus.name());
    }

    private void seedClaim(ClaimStatus status) {
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, reason_detail, status, "
                        + "previous_order_item_status, requested_by, requested_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CANCEL', 'BUYER_CHANGED_MIND', '통합', ?, 'PAID', ?, NOW(6), NOW(6), NOW(6))",
                CLAIM_ID, CLAIM_PID, ORDER_ITEM_ID, status.name(), USER_ID);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM claim WHERE order_item_id = ?", ORDER_ITEM_ID);
                jdbc.update("DELETE FROM order_item WHERE id = ?", ORDER_ITEM_ID);
                jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
                jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
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

    private String orderStatus() {
        return jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", String.class, ORDER_ID);
    }

    private String claimStatus() {
        return jdbc.queryForObject("SELECT status FROM claim WHERE id = ?", String.class, CLAIM_ID);
    }

    /** prefix + 26자 본문(tag 대문자 + '0' 패딩·[0-9A-Z])으로 30자 public_id를 만든다(@Pattern 정합). */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
