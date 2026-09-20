package com.zslab.mall.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.order.controller.response.AdminOrderDetailResponse;
import com.zslab.mall.order.controller.response.AdminOrderSummaryResponse;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 셀러 품목 목록·상세 통합 테스트(Track 90-B-1·실 MariaDB·HTTP 경유). 셀러의 주문 단위 = 자기 품목 행.
 *
 * <p><b>시드 그래프</b>: 셀러 A(user A·상태 파라미터)·셀러 B(user B·ACTIVE) /
 * 주문 M(혼합·PAID·결제 2026-03-10) — 품목 A1(셀러 A·PAID·"혼합상품A") + 품목 B1(셀러 B·PAID·"혼합상품B") /
 * 주문 N(셀러 A 단독·SHIPPING·결제 2026-04-05) — 품목 A2(SHIPPING·"단독상품A"·원 발송 배송 D1 SHIPPING) /
 * 주문 X(미결제 PENDING_PAYMENT) — 품목 A3(ORDERED) / 주문 Y(만료 PAYMENT_EXPIRED) — 품목 A4(ORDERED·품목 전이 없음 트랩).
 * 기대: 셀러 A 목록 = A2·A1(결제일 최신순) 2행 — B1(타 셀러)·A3·A4(미결제) 제외.
 *
 * <p>시드는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}·? 바인딩(SQL injection 없음).
 */
@AutoConfigureMockMvc
class SellerOrderItemQueryControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String LIST_URL = "/api/v1/seller/order-items";

    private static final long USER_A = 9620L;
    private static final long USER_B = 9621L;
    private static final long BUYER_ID = 9622L;
    private static final long SELLER_A = 9620L;
    private static final long SELLER_B = 9621L;
    private static final long PRODUCT_ID = 9620L;
    private static final long VARIANT_ID = 9620L;
    private static final long DUMMY_FK_ID = 9620L;
    private static final long ORDER_M = 9620L;
    private static final long ORDER_N = 9621L;
    private static final long ORDER_X = 9622L;
    private static final long ORDER_Y = 9623L;
    private static final long ITEM_A1 = 9620L;
    private static final long ITEM_B1 = 9621L;
    private static final long ITEM_A2 = 9622L;
    private static final long ITEM_A3 = 9623L;
    private static final long ITEM_A4 = 9624L;
    private static final long DELIVERY_D1 = 9620L;
    private static final long ITEM_PRICE = 15_000L;

    private static final String ORDER_M_NO = "ORDSOIM9620";
    private static final String ORDER_N_NO = "ORDSOIN9621";
    private static final String ITEM_A1_PID = pid("oit_", "SOIA1");
    private static final String ITEM_B1_PID = pid("oit_", "SOIB1");
    private static final String ITEM_A2_PID = pid("oit_", "SOIA2");
    private static final String ITEM_A3_PID = pid("oit_", "SOIA3");
    private static final String ITEM_A4_PID = pid("oit_", "SOIA4");
    private static final String DELIVERY_D1_PID = pid("dlv_", "SOID1");
    private static final String MISSING_PID = pid("oit_", "SOINONE");
    private static final String D1_TRACKING = "SOI-TRK-A2";
    private static final String KEYWORD_LIMIT_EXCEEDED = "K".repeat(51);

    /** 목록 행 키 화이트리스트 — 필드가 늘면 여기와 SellerOrderItemSummaryResponse를 함께 바꿔야 한다. */
    private static final Set<String> SUMMARY_KEYS = Set.of("orderItemId", "orderNo", "orderedAt", "paidAt", "productName",
            "optionLabel", "quantity", "unitPrice", "totalPrice", "itemStatus", "recipientName", "delivery");
    /** 상세 키 화이트리스트(목록 행 − recipientName + shippingAddress). */
    private static final Set<String> DETAIL_KEYS = Set.of("orderItemId", "orderNo", "orderedAt", "paidAt", "productName",
            "optionLabel", "quantity", "unitPrice", "totalPrice", "itemStatus", "delivery", "shippingAddress");
    private static final Set<String> DELIVERY_KEYS = Set.of("deliveryId", "carrier", "trackingNo", "status", "shippedAt", "deliveredAt");
    private static final Set<String> SHIPPING_ADDRESS_KEYS = Set.of("recipientName", "recipientPhone", "zonecode", "addressRoad",
            "addressJibun", "addressDetail", "deliveryMemo");
    /** 셀러 응답이 어느 층위에서든 가질 수 있는 키 전체(관리자 필드에서 뺄 허용 집합). */
    private static final Set<String> SELLER_ALLOWED_KEYS = union(SUMMARY_KEYS, DETAIL_KEYS, DELIVERY_KEYS, SHIPPING_ADDRESS_KEYS);
    /** 정찰 §2-3 전수 목록(수동). 자동 도출분과 합집합으로 쓰며, 관리자 DTO에서 사라져도 여기 항목은 남는다. */
    private static final Set<String> MANUAL_FORBIDDEN_KEYS = Set.of("buyer", "buyerName", "buyerEmail", "sellerNames", "sellerName",
            "totalPriceOfOrder", "discountAmount", "shippingFee", "paymentAmount", "payments", "paymentMethod", "paymentStatus",
            "cancelReasons", "actions", "items", "status", "orderId");
    /**
     * 셀러 노출 금지 키 = (관리자 주문 응답 DTO 2종의 선언 필드 — 중첩 record·List&lt;record&gt; 포함 — 셀러 허용 키) ∪ 수동 목록.
     * 관리자 DTO에 민감 필드가 추가되면 셀러 허용 집합에 없는 한 자동으로 금지 키가 돼 이 테스트가 알게 된다(외부 검토 r1 부분 수용).
     */
    private static final Set<String> FORBIDDEN_KEYS = forbiddenKeys();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private ObjectMapper objectMapper;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        seedAll(SellerStatus.ACTIVE);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("T1 인가: 비인증 401 · 구매자 403 · 셀러 200")
    void list_authorization() throws Exception {
        mockMvc.perform(get(LIST_URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(LIST_URL).headers(authHeaders.buyer(BUYER_ID))).andExpect(status().isForbidden());
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T2 혼합 셀러 주문: 셀러 A 목록은 자기 품목 A2·A1만(타 셀러 B1·미결제 A3·만료 A4 제외)·결제일 최신순·주문 축은 번호·시각만")
    void list_mixedOrder_returnsOnlyOwnItems() throws Exception {
        String body = mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items[0].orderItemId").value(ITEM_A2_PID))
                .andExpect(jsonPath("$.items[0].orderNo").value(ORDER_N_NO))
                .andExpect(jsonPath("$.items[0].itemStatus").value("SHIPPING"))
                .andExpect(jsonPath("$.items[0].recipientName").value("단독수령인"))
                .andExpect(jsonPath("$.items[0].delivery.deliveryId").value(DELIVERY_D1_PID))
                .andExpect(jsonPath("$.items[0].delivery.trackingNo").value(D1_TRACKING))
                .andExpect(jsonPath("$.items[1].orderItemId").value(ITEM_A1_PID))
                .andExpect(jsonPath("$.items[1].orderNo").value(ORDER_M_NO))
                .andExpect(jsonPath("$.items[1].productName").value("혼합상품A"))
                .andExpect(jsonPath("$.items[1].recipientName").value("혼합수령인"))
                .andExpect(jsonPath("$.items[1].delivery").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // NON_NULL 직렬화(application.yml)라 null 필드(optionLabel·delivery)는 생략된다 → 키 집합은 화이트리스트의 부분집합이어야 하고,
        // 값이 있는 필드는 전부 나와야 한다(A2 행 = optionLabel만 null).
        JsonNode items = objectMapper.readTree(body).get("items");
        for (JsonNode row : items) {
            assertThat(SUMMARY_KEYS).containsAll(keysOf(row));
            assertThat(keysOf(row)).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
            assertThat(row.toString()).doesNotContain("혼합상품B").doesNotContain("buyer");
        }
        Set<String> a2Keys = new LinkedHashSet<>(SUMMARY_KEYS);
        a2Keys.remove("optionLabel");
        assertThat(keysOf(items.get(0))).containsExactlyInAnyOrderElementsOf(a2Keys);
        Set<String> a2DeliveryKeys = new LinkedHashSet<>(DELIVERY_KEYS);
        a2DeliveryKeys.remove("deliveredAt");
        assertThat(keysOf(items.get(0).get("delivery"))).containsExactlyInAnyOrderElementsOf(a2DeliveryKeys);

        // 셀러 B 관점: 같은 주문 M에서 B1만 보인다.
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].orderItemId").value(ITEM_B1_PID));
    }

    @Test
    @DisplayName("T3 필터: status PAID 1 · keyword 상품명 부분 '단독' 1 · 주문번호 정확 1 · 송장 부분 미매칭 0 · 결제일 경계 from/to")
    void list_filters() throws Exception {
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("status", "PAID"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].orderItemId").value(ITEM_A1_PID));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("keyword", "단독"))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].orderItemId").value(ITEM_A2_PID));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("keyword", ORDER_M_NO))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].orderItemId").value(ITEM_A1_PID));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("keyword", "ORDSOI"))
                .andExpect(jsonPath("$.totalCount").value(0));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A))
                        .param("from", "2026-04-01T00:00:00").param("to", "2026-04-30T23:59:59"))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].orderItemId").value(ITEM_A2_PID));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("to", "2026-03-31T23:59:59"))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].orderItemId").value(ITEM_A1_PID));
    }

    @Test
    @DisplayName("T4 400: 허용 외 status · keyword 51자 · from>to")
    void list_badRequests() throws Exception {
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("status", "BOGUS"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("keyword", KEYWORD_LIMIT_EXCEEDED))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A))
                        .param("from", "2026-05-01T00:00:00").param("to", "2026-04-01T00:00:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("T5 상세: 자기 품목 200·키 화이트리스트·배송지 7필드 전체(마스킹 없음)·배송 행 · 송장 미등록 품목은 delivery 없음")
    void detail_ownItem_returnsShippingAddress() throws Exception {
        String body = mockMvc.perform(get(LIST_URL + "/" + ITEM_A2_PID).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderItemId").value(ITEM_A2_PID))
                .andExpect(jsonPath("$.orderNo").value(ORDER_N_NO))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.unitPrice").value(ITEM_PRICE))
                .andExpect(jsonPath("$.totalPrice").value(ITEM_PRICE * 2))
                .andExpect(jsonPath("$.shippingAddress.recipientName").value("단독수령인"))
                .andExpect(jsonPath("$.shippingAddress.recipientPhone").value("010-2222-3333"))
                .andExpect(jsonPath("$.shippingAddress.zonecode").value("04524"))
                .andExpect(jsonPath("$.shippingAddress.addressRoad").value("서울 단독로 2"))
                .andExpect(jsonPath("$.shippingAddress.addressDetail").value("202호"))
                .andExpect(jsonPath("$.delivery.status").value("SHIPPING"))
                .andReturn().getResponse().getContentAsString();

        JsonNode node = objectMapper.readTree(body);
        Set<String> a2DetailKeys = new LinkedHashSet<>(DETAIL_KEYS);
        a2DetailKeys.remove("optionLabel");
        assertThat(keysOf(node)).containsExactlyInAnyOrderElementsOf(a2DetailKeys);
        assertThat(keysOf(node)).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
        // 중첩 delivery도 목록(T2)과 같은 수준으로 고정한다 — 목록·상세가 같은 SellerOrderItemDeliveryResponse를 쓰므로 한쪽만 검증하면
        // 다른 쪽이 비대칭 누출 경로가 된다. deliveredAt은 D1이 SHIPPING이라 null → NON_NULL 직렬화로 생략되어 기대 집합에서 뺀다.
        Set<String> a2DeliveryKeys = new LinkedHashSet<>(DELIVERY_KEYS);
        a2DeliveryKeys.remove("deliveredAt");
        // 금지 키 단언은 최상위에만 건다 — 중첩 delivery의 "status"(배송 상태)는 관리자 최상위 status(주문 상태)와 이름만 같고 화이트리스트 정확 일치가 이미 범위를 고정한다.
        assertThat(keysOf(node.get("delivery"))).containsExactlyInAnyOrderElementsOf(a2DeliveryKeys);
        // NON_NULL 직렬화라 null 필드(addressJibun·deliveryMemo)는 생략된다 → 키 집합은 화이트리스트의 부분집합.
        assertThat(SHIPPING_ADDRESS_KEYS).containsAll(keysOf(node.get("shippingAddress")));
        assertThat(keysOf(node.get("shippingAddress"))).contains("recipientName", "recipientPhone", "zonecode", "addressRoad", "addressDetail");

        mockMvc.perform(get(LIST_URL + "/" + ITEM_A1_PID).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery").doesNotExist())
                .andExpect(jsonPath("$.shippingAddress.recipientName").value("혼합수령인"));
    }

    @Test
    @DisplayName("T6 소유권 경계: 타 셀러 품목 404(403 아님·존재 은닉) · 미존재 404 · 미결제 주문 품목 404 · 만료 주문 품목 404")
    void detail_notOwned_returns404() throws Exception {
        mockMvc.perform(get(LIST_URL + "/" + ITEM_B1_PID).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mockMvc.perform(get(LIST_URL + "/" + MISSING_PID).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mockMvc.perform(get(LIST_URL + "/" + ITEM_A3_PID).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(LIST_URL + "/" + ITEM_A4_PID).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isNotFound());
        // 셀러 B는 자기 품목 B1을 본다(경계가 대칭임을 고정).
        mockMvc.perform(get(LIST_URL + "/" + ITEM_B1_PID).headers(authHeaders.seller(USER_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productName").value("혼합상품B"));
    }

    @Test
    @DisplayName("T7 D-190: SUSPENDED 셀러 목록·상세 200(조회 허용)")
    void list_suspended_returns200() throws Exception {
        cleanup();
        seedAll(SellerStatus.SUSPENDED);

        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].orderItemId", containsInAnyOrder(ITEM_A1_PID, ITEM_A2_PID)));
        mockMvc.perform(get(LIST_URL + "/" + ITEM_A1_PID).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0} 셀러 GET order-items → 401")
    @EnumSource(value = SellerStatus.class, names = {"PENDING", "TERMINATED"})
    @DisplayName("T8 D-190: 세션 불가 상태 → 401 UNAUTHENTICATED")
    void list_sessionDenied_returns401(SellerStatus status) throws Exception {
        cleanup();
        seedAll(status);

        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get(LIST_URL + "/" + ITEM_A1_PID).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isUnauthorized());
    }

    // ---------- seed·helpers ----------

    private void seedAll(SellerStatus sellerAStatus) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedSellerWithOwner(USER_A, SELLER_A, "SOIUSA", "SOISLA", "품목셀러A", sellerAStatus);
                seedSellerWithOwner(USER_B, SELLER_B, "SOIUSB", "SOISLB", "품목셀러B", SellerStatus.ACTIVE);
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, '품목상품', 'SALE', 15000, NOW(6), NOW(6))", PRODUCT_ID, pid("prd_", "SOIPRD"), SELLER_A, DUMMY_FK_ID);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VCSOI', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))", VARIANT_ID, pid("var_", "SOIVAR"), PRODUCT_ID, DUMMY_FK_ID);
                seedOrder(ORDER_M, pid("ord_", "SOIORM"), ORDER_M_NO, "PAID", "2026-03-10 09:00:00", "2026-03-10 09:05:00",
                        "혼합수령인", "010-1111-2222", "서울 혼합로 1", "101호");
                seedOrder(ORDER_N, pid("ord_", "SOIORN"), ORDER_N_NO, "SHIPPING", "2026-04-05 09:00:00", "2026-04-05 09:05:00",
                        "단독수령인", "010-2222-3333", "서울 단독로 2", "202호");
                seedOrder(ORDER_X, pid("ord_", "SOIORX"), "ORDSOIX9622", "PENDING_PAYMENT", "2026-04-20 09:00:00", null,
                        "미결제수령인", "010-3333-4444", "서울 미결제로 3", "303호");
                seedOrder(ORDER_Y, pid("ord_", "SOIORY"), "ORDSOIY9623", "PAYMENT_EXPIRED", "2026-04-21 09:00:00", null,
                        "만료수령인", "010-4444-5555", "서울 만료로 4", "404호");
                seedOrderItem(ITEM_A1, ITEM_A1_PID, ORDER_M, SELLER_A, "혼합상품A", "PAID", 1);
                seedOrderItem(ITEM_B1, ITEM_B1_PID, ORDER_M, SELLER_B, "혼합상품B", "PAID", 1);
                seedOrderItem(ITEM_A2, ITEM_A2_PID, ORDER_N, SELLER_A, "단독상품A", "SHIPPING", 2);
                seedOrderItem(ITEM_A3, ITEM_A3_PID, ORDER_X, SELLER_A, "미결제상품A", "ORDERED", 1);
                seedOrderItem(ITEM_A4, ITEM_A4_PID, ORDER_Y, SELLER_A, "만료상품A", "ORDERED", 1);
                jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, direction, carrier, tracking_no, status, shipped_at, "
                        + "delivered_at, claim_id, created_at, updated_at) VALUES (?, ?, ?, 'OUTBOUND', 'CJ', ?, 'SHIPPING', "
                        + "'2026-04-06 10:00:00', NULL, NULL, NOW(6), NOW(6))", DELIVERY_D1, DELIVERY_D1_PID, ITEM_A2, D1_TRACKING);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedSellerWithOwner(long userId, long sellerId, String userTag, String sellerTag, String companyName,
            SellerStatus status) {
        jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                userId, pid("usr_", userTag));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, '대표', ?, NOW(6), NOW(6))", sellerId, pid("slr_", sellerTag), companyName, status.name());
        jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'", userId, sellerId);
    }

    private void seedOrder(long id, String publicId, String orderNo, String status, String orderedAt, String paidAt,
            String recipientName, String recipientPhone, String addressRoad, String addressDetail) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                + "ordered_at, paid_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 0, 0, ?, ?, NOW(6), NOW(6))",
                id, publicId, BUYER_ID, orderNo, status, ITEM_PRICE, orderedAt, paidAt);
        jdbc.update("INSERT INTO order_shipping_snapshot (id, order_id, recipient_name, recipient_phone, zonecode, address_road, "
                + "address_detail, created_at, updated_at) VALUES (?, ?, ?, ?, '04524', ?, ?, NOW(6), NOW(6))",
                id, id, recipientName, recipientPhone, addressRoad, addressDetail);
    }

    private void seedOrderItem(long id, String publicId, long orderId, long sellerId, String productName, String itemStatus,
            int quantity) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                + "total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(6), NOW(6), ?, 1000)",
                id, publicId, orderId, PRODUCT_ID, VARIANT_ID, sellerId, quantity, ITEM_PRICE, ITEM_PRICE * quantity, itemStatus,
                productName);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM delivery WHERE id = ?", DELIVERY_D1);
                jdbc.update("DELETE FROM order_item WHERE id IN (?, ?, ?, ?, ?)", ITEM_A1, ITEM_B1, ITEM_A2, ITEM_A3, ITEM_A4);
                jdbc.update("DELETE FROM order_shipping_snapshot WHERE order_id IN (?, ?, ?, ?)", ORDER_M, ORDER_N, ORDER_X, ORDER_Y);
                jdbc.update("DELETE FROM `order` WHERE id IN (?, ?, ?, ?)", ORDER_M, ORDER_N, ORDER_X, ORDER_Y);
                jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller_user WHERE user_id IN (?, ?)", USER_A, USER_B);
                jdbc.update("DELETE FROM seller WHERE id IN (?, ?)", SELLER_A, SELLER_B);
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?)", USER_A, USER_B);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        Set<String> result = new LinkedHashSet<>();
        for (Set<String> set : sets) {
            result.addAll(set);
        }
        return result;
    }

    private static Set<String> forbiddenKeys() {
        Set<String> adminKeys = new LinkedHashSet<>();
        collectRecordKeys(AdminOrderSummaryResponse.class, adminKeys);
        collectRecordKeys(AdminOrderDetailResponse.class, adminKeys);
        adminKeys.removeAll(SELLER_ALLOWED_KEYS);
        adminKeys.addAll(MANUAL_FORBIDDEN_KEYS);
        return Set.copyOf(adminKeys);
    }

    /** record 컴포넌트명을 모으고, 컴포넌트 타입이 record이거나 List&lt;record&gt;면 재귀한다(중첩 Buyer·PaymentRow·Item·ClaimRow 등). */
    private static void collectRecordKeys(Class<?> type, Set<String> into) {
        if (!type.isRecord()) {
            return;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            into.add(component.getName());
            Class<?> nested = component.getType();
            if (List.class.isAssignableFrom(nested) && component.getGenericType() instanceof ParameterizedType parameterized
                    && parameterized.getActualTypeArguments()[0] instanceof Class<?> element) {
                nested = element;
            }
            if (nested != type) {
                collectRecordKeys(nested, into);
            }
        }
    }

    private static Set<String> keysOf(JsonNode node) {
        Set<String> keys = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
