package com.zslab.mall.order.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 구매자 주문 목록 품목 요약 items[]·썸네일(Track 105-2d·D-223) 통합 테스트(실 MariaDB·HTTP 경유). 목록
 * {@code OrderSummaryResponse.items}와 상세 {@code OrderItemResponse.thumbnailUrl}이 같은 썸네일(product.thumbnail_url)을 쓰는지 본다.
 *
 * <p><b>커버</b>: T1 다품목 items 순서(created_at ASC)·필드 · T2 썸네일(thumbnail_url 값 전달·null·삭제 상품 null) ·
 * T3 기존 필드 불변(previewTitle·sellerCount·totalPrice·status·orderedAt·activeClaims) · T4 상세 thumbnailUrl(목록과 같은 값).
 *
 * <p>시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally)·id 985100~985119 고정.
 */
@AutoConfigureMockMvc
class BuyerOrderItemSummaryIntegrationTest extends AbstractIntegrationTest {

    private static final long ID_BASE = 985100L;
    private static final long ID_LAST = 985119L;
    private static final long BUYER_USER = ID_BASE;
    private static final long SELLER_ID = ID_BASE;
    private static final long DUMMY_FK_ID = ID_BASE;
    private static final long ORDER_ID = ID_BASE;
    private static final long PRODUCT_THUMB_A = ID_BASE;
    private static final long PRODUCT_THUMB_B = ID_BASE + 1;
    private static final long PRODUCT_DELETED = ID_BASE + 2;   // soft delete 상품(thumbnail_url 있음)
    private static final long PRODUCT_NO_THUMB = ID_BASE + 3;  // thumbnail_url NULL
    private static final String THUMB_A = "https://img.test/a_thumb.jpg";
    private static final String THUMB_B = "https://img.test/b_thumb.jpg";
    private static final long ITEM_FIRST = ID_BASE + 2;       // created_at 최선·id는 중간
    private static final long ITEM_SECOND = ID_BASE;
    // THIRD·FOURTH는 created_at 동률이다. DB 반환 순서가 이미 id ASC라 동률 id 정렬(previewTitle과 같은 비교자)은 이 시드로 가려지지 않는다.
    private static final long ITEM_THIRD = ID_BASE + 1;
    private static final long ITEM_FOURTH = ID_BASE + 3;
    private static final long CLAIM_ID = ID_BASE;

    private static final String ORDER_PID = pid("ord_", "BOISORD");
    private static final String SELLER_NAME = "목록셀러";
    private static final String LIST_URL = "/api/v1/orders";
    private static final String DETAIL_URL = "/api/v1/orders/" + ORDER_PID;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        seedGraph();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("T1 다품목 items: created_at ASC 순서(id 순서와 어긋난 시드) + 품목 필드(식별자·스냅샷·가격·상태·셀러명·교환 완료)")
    void list_itemsOrderAndFields() throws Exception {
        mockMvc.perform(get(LIST_URL).headers(authHeaders.buyer(BUYER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].items.length()").value(4))
                .andExpect(jsonPath("$.items[0].items[0].orderItemId").value(itemPid(ITEM_FIRST)))
                .andExpect(jsonPath("$.items[0].items[1].orderItemId").value(itemPid(ITEM_SECOND)))
                .andExpect(jsonPath("$.items[0].items[2].orderItemId").value(itemPid(ITEM_THIRD)))
                .andExpect(jsonPath("$.items[0].items[3].orderItemId").value(itemPid(ITEM_FOURTH)))
                .andExpect(jsonPath("$.items[0].items[0].productName").value("썸네일상품A"))
                .andExpect(jsonPath("$.items[0].items[0].optionLabel").value("색상: 블랙"))
                .andExpect(jsonPath("$.items[0].items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].items[0].unitPrice").value(10000))
                .andExpect(jsonPath("$.items[0].items[0].totalPrice").value(20000))
                .andExpect(jsonPath("$.items[0].items[0].status.code").value("DELIVERED"))
                .andExpect(jsonPath("$.items[0].items[0].sellerName").value(SELLER_NAME))
                .andExpect(jsonPath("$.items[0].items[0].productId").value(pid("prd_", "BOISPRD" + PRODUCT_THUMB_A)))
                .andExpect(jsonPath("$.items[0].items[0].variantId").value(pid("var_", "BOISVAR" + PRODUCT_THUMB_A)))
                .andExpect(jsonPath("$.items[0].items[0].exchangeCompleted").value(false))
                .andExpect(jsonPath("$.items[0].items[1].optionLabel").doesNotExist())
                .andExpect(jsonPath("$.items[0].items[1].exchangeCompleted").value(true))
                .andExpect(jsonPath("$.items[0].items[2].productId").doesNotExist());  // 삭제 상품
    }

    @Test
    @DisplayName("T2 썸네일: product.thumbnail_url 값 전달·thumbnail_url NULL → null·삭제 상품(thumbnail_url 있음) → null")
    void list_thumbnailUrl() throws Exception {
        mockMvc.perform(get(LIST_URL).headers(authHeaders.buyer(BUYER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].items[0].thumbnailUrl").value(THUMB_A))
                .andExpect(jsonPath("$.items[0].items[1].thumbnailUrl").value(THUMB_B))
                .andExpect(jsonPath("$.items[0].items[2].thumbnailUrl").doesNotExist())
                .andExpect(jsonPath("$.items[0].items[3].thumbnailUrl").doesNotExist());
    }

    @Test
    @DisplayName("T3 기존 필드 불변: orderId·previewTitle(첫 품목 외 N건)·sellerCount·totalPrice·status·orderedAt·activeClaims")
    void list_existingFieldsUnchanged() throws Exception {
        mockMvc.perform(get(LIST_URL).headers(authHeaders.buyer(BUYER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].orderId").value(ORDER_PID))
                .andExpect(jsonPath("$.items[0].previewTitle").value("썸네일상품A 외 3건"))
                .andExpect(jsonPath("$.items[0].sellerCount").value(1))
                .andExpect(jsonPath("$.items[0].totalPrice").value(35000))
                .andExpect(jsonPath("$.items[0].status.code").value("DELIVERED"))
                .andExpect(jsonPath("$.items[0].orderedAt").exists())
                .andExpect(jsonPath("$.items[0].activeClaims.length()").value(0))
                .andExpect(jsonPath("$.totalCount").value(1));
    }

    @Test
    @DisplayName("T4 상세 thumbnailUrl: 목록과 같은 값(값 전달·NULL → null·삭제 상품 null)")
    void detail_thumbnailUrl() throws Exception {
        mockMvc.perform(get(DETAIL_URL).headers(authHeaders.buyer(BUYER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(detailItemPath(ITEM_FIRST) + ".thumbnailUrl").value(THUMB_A))
                .andExpect(jsonPath(detailItemPath(ITEM_SECOND) + ".thumbnailUrl").value(THUMB_B))
                .andExpect(jsonPath(detailItemPath(ITEM_THIRD) + ".thumbnailUrl").doesNotExist())
                .andExpect(jsonPath(detailItemPath(ITEM_FOURTH) + ".thumbnailUrl").doesNotExist());
    }

    // ---------- seed·helpers (BuyerOrderDeliveryQueryIntegrationTest 패턴) ----------

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    /**
     * 주문 1건(DELIVERED) · 품목 4개(상품 4종·셀러 1). 품목 id와 created_at 순서를 어긋나게 넣어 정렬 기준을 검증한다.
     * ITEM_SECOND에는 완료된 교환 클레임(exchangeCompleted true)을 붙인다.
     */
    private void seedGraph() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        BUYER_USER, pid("usr_", "BOISUSR"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, ?, '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "BOISSLR"), SELLER_NAME);
                insertProduct(PRODUCT_THUMB_A, "썸네일상품A", THUMB_A, null);
                insertProduct(PRODUCT_THUMB_B, "썸네일상품B", THUMB_B, null);
                insertProduct(PRODUCT_DELETED, "삭제상품", "https://img.test/deleted_thumb.jpg", LocalDateTime.now().minusDays(1));
                insertProduct(PRODUCT_NO_THUMB, "무썸네일상품", null, null);

                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, "
                                + "shipping_fee, ordered_at, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'ORDBOIS', 'DELIVERED', 35000, 0, 0, ?, NOW(6), NOW(6))",
                        ORDER_ID, ORDER_PID, BUYER_USER, LocalDateTime.now().minusDays(1));
                insertItem(ITEM_FIRST, PRODUCT_THUMB_A, "썸네일상품A", "색상: 블랙", 2, 10_000L, "2026-09-01 10:00:00");
                insertItem(ITEM_SECOND, PRODUCT_THUMB_B, "썸네일상품B", null, 1, 5_000L, "2026-09-01 10:00:01");
                insertItem(ITEM_THIRD, PRODUCT_DELETED, "삭제상품", null, 1, 5_000L, "2026-09-01 10:00:02");
                insertItem(ITEM_FOURTH, PRODUCT_NO_THUMB, "무썸네일상품", null, 1, 5_000L, "2026-09-01 10:00:02");

                jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, requested_by, "
                                + "previous_order_item_status, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'EXCHANGE', 'PRODUCT_DEFECT', 'COMPLETED', ?, 'DELIVERED', NOW(6), NOW(6))",
                        CLAIM_ID, pid("clm_", "BOISCLM"), ITEM_SECOND, BUYER_USER);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void insertProduct(long id, String name, String thumbnailUrl, LocalDateTime deletedAt) {
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, thumbnail_url, "
                        + "created_at, updated_at, deleted_at) VALUES (?, ?, ?, ?, ?, 'SALE', 10000, ?, NOW(6), NOW(6), ?)",
                id, pid("prd_", "BOISPRD" + id), SELLER_ID, DUMMY_FK_ID, name, thumbnailUrl, deletedAt);
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, "
                        + "status, is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                id, pid("var_", "BOISVAR" + id), id, "VCBOIS" + id, DUMMY_FK_ID);
    }

    private void insertItem(long id, long productId, String productName, String optionLabel, int quantity, long unitPrice,
            String createdAt) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                        + "total_price, item_status, option_label, created_at, updated_at, product_name, commission_rate) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'DELIVERED', ?, ?, NOW(6), ?, 1000)",
                id, itemPid(id), ORDER_ID, productId, productId, SELLER_ID, quantity, unitPrice, unitPrice * quantity,
                optionLabel, createdAt, productName);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM claim WHERE id = ?", CLAIM_ID);
                jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
                jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
                jdbc.update("DELETE FROM product_variant WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
                jdbc.update("DELETE FROM product WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id = ?", BUYER_USER);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String itemPid(long id) {
        return pid("oit_", "BOISIT" + id);
    }

    private static String detailItemPath(long itemId) {
        return "$.sellers[0].items[?(@.orderItemId == '" + itemPid(itemId) + "')]";
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
