package com.zslab.mall.cart.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.order.event.OrderPlaced;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.zslab.mall.support.AbstractIntegrationTest;

/**
 * CartItem 소비(장바구니 비우기) 이벤트 E2E 통합 테스트(Track 41 Phase 4·실 MariaDB·Flyway). OrderPlaced를 커밋 트랜잭션에서
 * 발행해 AFTER_COMMIT·REQUIRES_NEW {@code CartOrderPlacedHandler}의 실제 커밋 경로(주문된 variant의 cart_item HARD DELETE)를
 * 검증한다(InventoryEventIntegrationTest 패턴 1:1 계승).
 *
 * <p><b>seed</b>: user·seller·product·product_variant 2행·order·order_item·cart_item을 {@code FOREIGN_KEY_CHECKS=0}
 * (LT-02 try-finally)으로 직접 시드한다. 삭제 결과는 JdbcTemplate 조회로 검증하므로 클래스에 {@code @Transactional}을 두지 않는다.
 * 형제 핸들러(Inventory 예약·Notification 적재)도 발화하나 각자 REQUIRES_NEW·실패 흡수라 cart_item 검증에 영향하지 않는다.
 */
@RecordApplicationEvents
class CartOrderPlacedEventIntegrationTest extends AbstractIntegrationTest {

    private static final long USER_ID = 9670L;
    private static final long SELLER_ID = 9670L;
    private static final long PRODUCT_ID = 9670L;
    private static final long ORDERED_VARIANT_ID = 9671L;
    private static final long OTHER_VARIANT_ID = 9672L;
    private static final long ORDER_ID = 9670L;
    private static final long ORDER_ITEM_ID = 9670L;
    /** product.category_id·variant.option1_value_id NOT NULL FK 충족용 더미(FK_CHECKS=0 시드로 우회). */
    private static final long DUMMY_FK_ID = 9670L;

    private static final String ORDER_PID = pid("ord_", "T41ORD");
    private static final String ORDER_ITEM_PID = pid("oit_", "T41OIT");

    @Autowired
    private ApplicationEventPublisher eventPublisher;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private ApplicationEvents applicationEvents;

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
    @DisplayName("T1 소비: 주문된 variant의 cart_item(selected=true) → OrderPlaced 후 HARD DELETE")
    void orderPlaced_consumesOrderedVariantCartItem() {
        seed(() -> {
            seedCatalog();
            seedOrderWithItem();
            seedCartItem(ORDERED_VARIANT_ID, true);
        });

        publishInTx(new OrderPlaced(ORDER_PID, ORDER_ID, LocalDateTime.now()));

        assertThat(cartCount(ORDERED_VARIANT_ID)).isZero();
        assertThat(applicationEvents.stream(OrderPlaced.class).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("T2 통합 정책(D-126·①): selected=false여도 주문된 variant면 삭제(경로·선택 무관·Buy Now 포함)")
    void orderPlaced_consumesEvenUnselectedVariant() {
        seed(() -> {
            seedCatalog();
            seedOrderWithItem();
            seedCartItem(ORDERED_VARIANT_ID, false);   // 미선택이나 주문된 variant
        });

        publishInTx(new OrderPlaced(ORDER_PID, ORDER_ID, LocalDateTime.now()));

        assertThat(cartCount(ORDERED_VARIANT_ID)).isZero();
    }

    @Test
    @DisplayName("T3 재발행 멱등: OrderPlaced 2회 → 삭제 1회·2회차 무해(대상 0·예외 전파 없음)")
    void orderPlaced_republish_idempotent() {
        seed(() -> {
            seedCatalog();
            seedOrderWithItem();
            seedCartItem(ORDERED_VARIANT_ID, true);
        });

        OrderPlaced event = new OrderPlaced(ORDER_PID, ORDER_ID, LocalDateTime.now());
        publishInTx(event);
        publishInTx(event);   // 2회차: 이미 삭제·HARD DELETE 자연 멱등(0행)

        assertThat(cartCount(ORDERED_VARIANT_ID)).isZero();
    }

    @Test
    @DisplayName("T4 비대상 잔존: 주문에 없는 variant의 cart_item은 잔존·주문 variant만 삭제")
    void orderPlaced_retainsNonOrderedVariant() {
        seed(() -> {
            seedCatalog();
            seedOrderWithItem();
            seedCartItem(ORDERED_VARIANT_ID, true);
            seedCartItem(OTHER_VARIANT_ID, true);   // 주문에 없는 variant
        });

        publishInTx(new OrderPlaced(ORDER_PID, ORDER_ID, LocalDateTime.now()));

        assertThat(cartCount(ORDERED_VARIANT_ID)).isZero();
        assertThat(cartCount(OTHER_VARIANT_ID)).isEqualTo(1);
    }

    // ---------- 발행·seed·helpers (InventoryEventIntegrationTest 패턴) ----------

    /** 커밋 트랜잭션에서 이벤트를 발행해 그 커밋 시점에 AFTER_COMMIT 핸들러가 동기 발화하도록 한다(@Async 아님). */
    private void publishInTx(Object event) {
        tx.executeWithoutResult(s -> eventPublisher.publishEvent(event));
    }

    /** FK 비활성 상태로 시드하고 복원한다(LT-02 try-finally). */
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
                USER_ID, pid("usr_", "T41USR"));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, '트랙41셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                SELLER_ID, pid("slr_", "T41SLR"));
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, '트랙41상품', 'SALE', 10000, NOW(6), NOW(6))",
                PRODUCT_ID, pid("prd_", "T41PRD"), SELLER_ID, DUMMY_FK_ID);
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VC41A', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                ORDERED_VARIANT_ID, pid("var_", "T41VRA"), PRODUCT_ID, DUMMY_FK_ID);
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VC41B', 0, 'SALE', 0, 2, ?, NOW(6), NOW(6))",
                OTHER_VARIANT_ID, pid("var_", "T41VRB"), PRODUCT_ID, DUMMY_FK_ID);
    }

    private void seedOrderWithItem() {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                        + "discount_amount, shipping_fee, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'PENDING_PAYMENT', 10000, 0, 0, NOW(6), NOW(6))",
                ORDER_ID, ORDER_PID, USER_ID, "ORDT41" + ORDER_ID);
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                        + "quantity, unit_price, total_price, item_status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, 10000, 10000, 'ORDERED', NOW(6), NOW(6))",
                ORDER_ITEM_ID, ORDER_ITEM_PID, ORDER_ID, PRODUCT_ID, ORDERED_VARIANT_ID, SELLER_ID);
    }

    private void seedCartItem(long variantId, boolean selected) {
        // variant_public_id(V17·NOT NULL)는 담김 스냅샷 — 시드한 variant의 실제 public_id와 정합시킨다(무FK 컬럼).
        String variantPublicId = variantId == ORDERED_VARIANT_ID ? pid("var_", "T41VRA") : pid("var_", "T41VRB");
        jdbc.update("INSERT INTO cart_item (user_id, variant_id, variant_public_id, quantity, selected, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 1, ?, NOW(6), NOW(6))",
                USER_ID, variantId, variantPublicId, selected ? 1 : 0);
    }

    private int cartCount(long variantId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cart_item WHERE user_id = ? AND variant_id = ?", Integer.class, USER_ID, variantId);
        return count == null ? 0 : count;
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM cart_item WHERE user_id = ?", USER_ID);
                jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", USER_ID);
                jdbc.update("DELETE FROM order_item WHERE id = ?", ORDER_ITEM_ID);
                jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
                jdbc.update("DELETE FROM product_variant WHERE id IN (?, ?)", ORDERED_VARIANT_ID, OTHER_VARIANT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id = ?", USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    /** prefix + 26자 본문(tag 대문자 + '0' 패딩·[0-9A-Z])으로 30자 public_id를 만든다. */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
