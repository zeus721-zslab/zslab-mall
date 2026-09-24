package com.zslab.mall.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
 * 구매자 주문 응답의 주문번호·주문 일시·결제 요약(Track 105-4g-3) 통합 테스트(실 MariaDB·HTTP 경유).
 *
 * <p><b>커버</b>: T1 상세 orderNo·orderedAt·payment(실패 행 뒤 결제 완료 행) · T2 미결제(실패·대기 행만) → payment 키 생략 ·
 * T3 환불(CANCELLED·paidAt 유지) → payment 포함 · T4 목록 orderNo(마이페이지 최근 주문 공용) · T5 다른 구매자 상세 404 불변 ·
 * T6 상세 쿼리 수가 품목 수와 무관(품목 1개 = 3개·상품 모두 다름).
 *
 * <p>시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally)·id 985400~985429 고정.
 */
@AutoConfigureMockMvc
class BuyerOrderNumberPaymentIntegrationTest extends AbstractIntegrationTest {

    private static final long ID_BASE = 985400L;
    private static final long ID_LAST = 985429L;
    private static final long BUYER_USER = ID_BASE;
    private static final long OTHER_BUYER_USER = ID_BASE + 1;
    private static final long SELLER_ID = ID_BASE;
    private static final long DUMMY_FK_ID = ID_BASE;

    private static final long ORDER_PAID = ID_BASE;
    private static final long ORDER_UNPAID = ID_BASE + 1;
    private static final long ORDER_REFUNDED = ID_BASE + 2;
    private static final long ORDER_MULTI_ITEM = ID_BASE + 3;

    private static final String ORDERED_AT = "2026-09-20 12:00:00";
    private static final String PAID_AT = "2026-09-20 12:05:00";
    private static final String ORDERED_AT_KST = "2026-09-20T12:00:00+09:00";
    private static final String PAID_AT_KST = "2026-09-20T12:05:00+09:00";

    private static final String ORDERS_URL = "/api/v1/orders";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

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
    @DisplayName("T1 상세: orderNo · orderedAt(KST) · payment = 결제 시각 있는 최신 행(앞선 실패 행 제외)")
    void detail_carriesOrderNoOrderedAtAndPayment() throws Exception {
        mockMvc.perform(get(detailUrl(ORDER_PAID)).headers(authHeaders.buyer(BUYER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderPid(ORDER_PAID)))
                .andExpect(jsonPath("$.orderNo").value(orderNo(ORDER_PAID)))
                .andExpect(jsonPath("$.orderedAt").value(ORDERED_AT_KST))
                .andExpect(jsonPath("$.payment.method").value("KAKAO"))
                .andExpect(jsonPath("$.payment.paidAt").value(PAID_AT_KST));
    }

    @Test
    @DisplayName("T2 미결제: 실패·대기 행만 있으면 payment 키 생략(orderNo·orderedAt은 있음)")
    void detail_unpaid_omitsPayment() throws Exception {
        mockMvc.perform(get(detailUrl(ORDER_UNPAID)).headers(authHeaders.buyer(BUYER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value(orderNo(ORDER_UNPAID)))
                .andExpect(jsonPath("$.orderedAt").value(ORDERED_AT_KST))
                .andExpect(jsonPath("$.payment").doesNotExist());
    }

    @Test
    @DisplayName("T3 환불: 결제 행이 CANCELLED여도 paidAt이 남아 있으면 payment 포함")
    void detail_refunded_keepsPayment() throws Exception {
        mockMvc.perform(get(detailUrl(ORDER_REFUNDED)).headers(authHeaders.buyer(BUYER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.method").value("CARD"))
                .andExpect(jsonPath("$.payment.paidAt").value(PAID_AT_KST));
    }

    @Test
    @DisplayName("T4 목록: 항목마다 orderNo(orderId는 그대로)")
    void list_carriesOrderNo() throws Exception {
        mockMvc.perform(get(ORDERS_URL).headers(authHeaders.buyer(BUYER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath(listItemPath(ORDER_PAID) + ".orderNo").value(orderNo(ORDER_PAID)))
                .andExpect(jsonPath(listItemPath(ORDER_UNPAID) + ".orderNo").value(orderNo(ORDER_UNPAID)))
                .andExpect(jsonPath(listItemPath(ORDER_MULTI_ITEM) + ".orderNo").value(orderNo(ORDER_MULTI_ITEM)));
    }

    @Test
    @DisplayName("T5 다른 구매자: 상세 404 불변(추가 필드가 권한 범위를 넓히지 않음)")
    void detail_otherBuyer_notFound() throws Exception {
        mockMvc.perform(get(detailUrl(ORDER_PAID)).headers(authHeaders.buyer(OTHER_BUYER_USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.orderNo").doesNotExist());
    }

    @Test
    @DisplayName("T6 쿼리 수: 상세 1회의 쿼리 수가 품목 수와 무관(품목 1개 주문 = 3개 주문)")
    void detail_queryCountIndependentOfItems() throws Exception {
        assertThat(countDetailQueries(ORDER_MULTI_ITEM)).isEqualTo(countDetailQueries(ORDER_PAID));
    }

    private long countDetailQueries(long orderId) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        mockMvc.perform(get(detailUrl(orderId)).headers(authHeaders.buyer(BUYER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.method").exists());
        long count = statistics.getPrepareStatementCount();
        statistics.setStatisticsEnabled(false);
        return count;
    }

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    /**
     * 주문 4건: 결제 완료(실패 → 결제 완료 행) · 미결제(실패 → 대기 행) · 환불(CANCELLED·paid_at 유지) · 품목 3개(결제 완료).
     * 품목 id = 주문 id(첫 품목) · 다품목 주문의 나머지 품목은 ID_BASE+10~. 상품은 품목마다 다르다.
     */
    private void seedGraph() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6)), "
                                + "(?, ?, NOW(6), NOW(6))",
                        BUYER_USER, pid("usr_", "BONPUSR1"), OTHER_BUYER_USER, pid("usr_", "BONPUSR2"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '번호셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "BONPSLR"));

                insertOrder(ORDER_PAID, "PAID");
                insertItem(ORDER_PAID, ORDER_PAID);
                insertPayment(ID_BASE, ORDER_PAID, "CARD", "FAILED", null);
                insertPayment(ID_BASE + 1, ORDER_PAID, "KAKAO", "PAID", PAID_AT);

                insertOrder(ORDER_UNPAID, "PENDING_PAYMENT");
                insertItem(ORDER_UNPAID, ORDER_UNPAID);
                insertPayment(ID_BASE + 2, ORDER_UNPAID, "CARD", "FAILED", null);
                insertPayment(ID_BASE + 3, ORDER_UNPAID, "CARD", "PENDING", null);

                insertOrder(ORDER_REFUNDED, "CANCELLED");
                insertItem(ORDER_REFUNDED, ORDER_REFUNDED);
                insertPayment(ID_BASE + 4, ORDER_REFUNDED, "CARD", "CANCELLED", PAID_AT);

                insertOrder(ORDER_MULTI_ITEM, "PAID");
                insertItem(ORDER_MULTI_ITEM, ORDER_MULTI_ITEM);
                insertItem(ID_BASE + 10, ORDER_MULTI_ITEM);
                insertItem(ID_BASE + 11, ORDER_MULTI_ITEM);
                insertPayment(ID_BASE + 5, ORDER_MULTI_ITEM, "BANK", "PAID", PAID_AT);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void insertOrder(long id, String status) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, "
                        + "shipping_fee, ordered_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 10000, 0, 0, ?, NOW(6), NOW(6))",
                id, orderPid(id), BUYER_USER, orderNo(id), status, ORDERED_AT);
    }

    /** 품목마다 같은 id의 상품·variant를 만든다(상세 enrich 배치가 품목 수만큼 커지는 시드). */
    private void insertItem(long id, long orderId) {
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, thumbnail_url, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, '번호상품', 'SALE', 10000, NULL, NOW(6), NOW(6))",
                id, pid("prd_", "BONPPRD" + id), SELLER_ID, DUMMY_FK_ID);
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, "
                        + "status, is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                id, pid("var_", "BONPVAR" + id), id, "VCBONP" + id, DUMMY_FK_ID);
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                        + "total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, 10000, 10000, 'PAID', NOW(6), NOW(6), '번호상품', 1000)",
                id, pid("oit_", "BONPIT" + id), orderId, id, id, SELLER_ID);
    }

    private void insertPayment(long id, long orderId, String method, String status, String paidAt) {
        jdbc.update("INSERT INTO payment (id, public_id, order_id, method, amount, status, paid_at, payment_attempt_key, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, 10000, ?, ?, ?, NOW(6), NOW(6))",
                id, pid("pay_", "BONPPAY" + id), orderId, method, status, paidAt, pid("pat_", "BONPPAT" + id));
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM payment WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
                jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
                jdbc.update("DELETE FROM `order` WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
                jdbc.update("DELETE FROM product_variant WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
                jdbc.update("DELETE FROM product WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String detailUrl(long orderId) {
        return ORDERS_URL + "/" + orderPid(orderId);
    }

    private static String listItemPath(long orderId) {
        return "$.items[?(@.orderId == '" + orderPid(orderId) + "')]";
    }

    private static String orderPid(long id) {
        return pid("ord_", "BONPORD" + id);
    }

    private static String orderNo(long id) {
        return "20260920-BONP" + (id - ID_BASE);
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
