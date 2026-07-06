package com.zslab.mall.claim.integration;

import static org.assertj.core.api.Assertions.assertThat;

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
 * ClaimRejected 스냅샷 원복 E2E 통합 테스트(Track 14 PR-1·D-98 Q7·D-90 Q3 의미 변경·ClaimEventIntegrationTest 패턴 1:1).
 * reject → AFTER_COMMIT {@code ClaimRejectedHandler} → OrderItem *_REQUESTED → {@code claim.previous_order_item_status}
 * 스냅샷 복원을 실 MariaDB 커밋 경로로 검증한다. CANCEL은 고정 PAID가 아닌 스냅샷(PREPARING) 복원으로 의미 변경을 실측한다.
 */
class ClaimRejectedRestoreIntegrationTest extends AbstractIntegrationTest {

    private static final long USER_ID = 9301L;
    private static final long SELLER_ID = 9301L;
    private static final long PRODUCT_ID = 9301L;
    private static final long VARIANT_ID = 9301L;
    private static final long ORDER_ID = 9301L;
    private static final long ORDER_ITEM_ID = 9301L;
    private static final long CLAIM_ID = 9301L;
    private static final long DUMMY_FK_ID = 9301L;

    private static final String ORDER_ITEM_PID = pid("oit_", "RSTOIT");
    private static final String CLAIM_PID = pid("clm_", "RSTCLM");

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
    @DisplayName("CANCEL reject e2e: CANCEL_REQUESTED → 스냅샷(PREPARING) 복원(D-90 Q3 의미 변경·고정 PAID 아님)")
    void cancelRejected_restoresToSnapshotPreparing() {
        seed(() -> {
            seedCatalog();
            seedOrder("PREPARING");
            seedOrderItem(OrderItemStatus.CANCEL_REQUESTED);
            seedClaim(ClaimType.CANCEL, ClaimStatus.REQUESTED, OrderItemStatus.PREPARING);
        });

        claimService.reject(CLAIM_ID, LocalDateTime.now());

        assertThat(orderItemStatus()).isEqualTo("PREPARING");
        assertThat(claimStatus()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("RETURN reject e2e: RETURN_REQUESTED → 스냅샷(DELIVERED) 복원(D-98 Q7 type 무관)")
    void returnRejected_restoresToSnapshotDelivered() {
        seed(() -> {
            seedCatalog();
            seedOrder("DELIVERED");
            seedOrderItem(OrderItemStatus.RETURN_REQUESTED);
            seedClaim(ClaimType.RETURN, ClaimStatus.REQUESTED, OrderItemStatus.DELIVERED);
        });

        claimService.reject(CLAIM_ID, LocalDateTime.now());

        assertThat(orderItemStatus()).isEqualTo("DELIVERED");
        assertThat(claimStatus()).isEqualTo("REJECTED");
    }

    // ---------- seed·helpers (ClaimEventIntegrationTest 패턴 1:1) ----------

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
        jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                USER_ID, pid("usr_", "RSTUSR"));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, '통합셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                SELLER_ID, pid("slr_", "RSTSLR"));
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, '통합상품', 'SALE', 10000, NOW(6), NOW(6))",
                PRODUCT_ID, pid("prd_", "RSTPRD"), SELLER_ID, DUMMY_FK_ID);
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VCRST', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                VARIANT_ID, pid("var_", "RSTVAR"), PRODUCT_ID, DUMMY_FK_ID);
    }

    private void seedOrder(String status) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                        + "discount_amount, shipping_fee, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 10000, 0, 0, NOW(6), NOW(6))",
                ORDER_ID, pid("ord_", "RSTORD"), USER_ID, "ORDRST" + ORDER_ID, status);
    }

    private void seedOrderItem(OrderItemStatus itemStatus) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                        + "quantity, unit_price, total_price, item_status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, 10000, 10000, ?, NOW(6), NOW(6))",
                ORDER_ITEM_ID, ORDER_ITEM_PID, ORDER_ID, PRODUCT_ID, VARIANT_ID, SELLER_ID, itemStatus.name());
    }

    private void seedClaim(ClaimType type, ClaimStatus status, OrderItemStatus snapshot) {
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, reason_detail, status, "
                        + "previous_order_item_status, requested_by, requested_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'BUYER_CHANGED_MIND', '통합', ?, ?, ?, NOW(6), NOW(6), NOW(6))",
                CLAIM_ID, CLAIM_PID, ORDER_ITEM_ID, type.name(), status.name(), snapshot.name(), USER_ID);
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

    private String claimStatus() {
        return jdbc.queryForObject("SELECT status FROM claim WHERE id = ?", String.class, CLAIM_ID);
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
