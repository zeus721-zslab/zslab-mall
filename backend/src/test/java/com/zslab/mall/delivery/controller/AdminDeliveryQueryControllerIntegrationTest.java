package com.zslab.mall.delivery.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 배송 목록·상세·송장 정정 통합 테스트(Track 89-B D-184·실 MariaDB·HTTP 경유). 기존 {@link AdminDeliveryControllerIntegrationTest}
 * (교환 출고·배송완료)는 건드리지 않고 별도 클래스로 둔다.
 *
 * <p><b>시드 그래프</b>: 주문 A(수령인 "홍길동검색")·품목 A1 / 주문 B(수령인 "김철수검색")·품목 B1 / 클레임 RETURN(B1)·EXCHANGE(A1) /
 * 배송 4행 — D1 원 발송 A1 SHIPPING CJ · D2 원 발송 B1 DELIVERED HANJIN · D3 반품 회수 B1(RETURN·RETURN 클레임) · D4 교환품 발송
 * A1(OUTBOUND·EXCHANGE 클레임). scope별 기대: ORIGINAL 2 / CLAIM_OUTBOUND 1 / RETURN 1 / ALL 4.
 *
 * <p>트랜잭션: 송장 정정 커밋·감사 행을 JdbcTemplate로 검증하므로 클래스 {@code @Transactional} 없음. 시드/정리는
 * {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(try-finally 복원). 모든 시드 INSERT는 ? positional 바인딩·정적 SQL이다.
 */
@AutoConfigureMockMvc
class AdminDeliveryQueryControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String LIST_URL = "/api/v1/admin/deliveries";

    private static final long ADMIN = 8930L;
    private static final long BUYER = 8931L;
    private static final long USER_ID = 8930L;
    private static final long SELLER_ID = 8930L;
    private static final long PRODUCT_ID = 8930L;
    private static final long VARIANT_ID = 8930L;
    private static final long DUMMY_FK_ID = 8930L;
    private static final long ORDER_A = 8930L;
    private static final long ORDER_B = 8931L;
    private static final long ITEM_A1 = 8930L;
    private static final long ITEM_B1 = 8931L;
    private static final long CLAIM_RETURN = 8930L;
    private static final long CLAIM_EXCHANGE = 8931L;
    private static final long D1 = 8930L;
    private static final long D2 = 8931L;
    private static final long D3 = 8932L;
    private static final long D4 = 8933L;
    private static final long ITEM_PRICE = 10_000L;

    private static final String ORDER_A_NO = "ORDDLVQA8930";
    private static final String ORDER_B_NO = "ORDDLVQB8931";
    private static final String ORDER_A_PID = pid("ord_", "DLQORDA");
    private static final String ITEM_A1_PID = pid("oit_", "DLQITA");
    private static final String CLAIM_RETURN_PID = pid("clm_", "DLQCLR");
    private static final String CLAIM_EXCHANGE_PID = pid("clm_", "DLQCLX");
    private static final String D1_PID = pid("dlv_", "DLQD1");
    private static final String D2_PID = pid("dlv_", "DLQD2");
    private static final String D3_PID = pid("dlv_", "DLQD3");
    private static final String D4_PID = pid("dlv_", "DLQD4");
    private static final String MISSING_PID = pid("dlv_", "DLQNONE");
    private static final String D1_TRACKING = "DLQ-TRK-A1";
    private static final String D2_TRACKING = "DLQ-TRK-B1";
    private static final String D3_TRACKING = "DLQ-TRK-R1";
    private static final String D4_TRACKING = "DLQ-TRK-X1";
    private static final String NEW_TRACKING = "DLQ-TRK-A1-FIXED";
    private static final String KEYWORD_LIMIT_EXCEEDED = "K".repeat(51);

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
        seedAll();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    // ---------- 인가 ----------

    @Test
    @DisplayName("T1 인가: 비인증 401 · 구매자 403 · 관리자 200")
    void list_authorization() throws Exception {
        mockMvc.perform(get(LIST_URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(LIST_URL).headers(authHeaders.buyer(BUYER))).andExpect(status().isForbidden());
        mockMvc.perform(get(LIST_URL).headers(authHeaders.admin(ADMIN))).andExpect(status().isOk());
        mockMvc.perform(get(LIST_URL + "/" + D1_PID)).andExpect(status().isUnauthorized());
        mockMvc.perform(patch(LIST_URL + "/" + D1_PID + "/tracking").contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("CJ", NEW_TRACKING, "사유")))
                .andExpect(status().isUnauthorized());
    }

    // ---------- scope ----------

    @Test
    @DisplayName("T2 scope: 기본 ORIGINAL=원 발송 2건(교환품 발송·회수 제외) · CLAIM_OUTBOUND 1 · RETURN 1 · ALL 4")
    void list_scope() throws Exception {
        list(Map.of())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items[*].deliveryId").value(containsInAnyOrder(D1_PID, D2_PID)))
                .andExpect(jsonPath("$.items[*].claimId").isEmpty());
        list(Map.of("scope", "CLAIM_OUTBOUND"))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].deliveryId").value(D4_PID))
                .andExpect(jsonPath("$.items[0].direction").value("OUTBOUND"))
                .andExpect(jsonPath("$.items[0].claimId").value(CLAIM_EXCHANGE_PID))
                .andExpect(jsonPath("$.items[0].claimType").value("EXCHANGE"));
        list(Map.of("scope", "RETURN"))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].deliveryId").value(D3_PID))
                .andExpect(jsonPath("$.items[0].direction").value("RETURN"))
                .andExpect(jsonPath("$.items[0].claimType").value("RETURN"));
        list(Map.of("scope", "ALL"))
                .andExpect(jsonPath("$.totalCount").value(4))
                .andExpect(jsonPath("$.items[*].deliveryId").value(containsInAnyOrder(D1_PID, D2_PID, D3_PID, D4_PID)));
    }

    // ---------- keyword 3종 ----------

    @Test
    @DisplayName("T3 keyword: 송장번호 정확 일치(부분은 미매칭) · 주문번호 정확 일치 · 수령인명 부분 일치")
    void list_keyword() throws Exception {
        list(Map.of("scope", "ALL", "keyword", D3_TRACKING))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].deliveryId").value(D3_PID));
        list(Map.of("scope", "ALL", "keyword", "DLQ-TRK"))
                .andExpect(jsonPath("$.totalCount").value(0));
        list(Map.of("scope", "ALL", "keyword", ORDER_A_NO))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items[*].deliveryId").value(containsInAnyOrder(D1_PID, D4_PID)))
                .andExpect(jsonPath("$.items[*].orderNo").value(containsInAnyOrder(ORDER_A_NO, ORDER_A_NO)));
        list(Map.of("scope", "ALL", "keyword", "철수"))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items[*].deliveryId").value(containsInAnyOrder(D2_PID, D3_PID)))
                .andExpect(jsonPath("$.items[*].recipientName").value(containsInAnyOrder("김철수검색", "김철수검색")));
        list(Map.of("scope", "ALL", "keyword", "없는수령인"))
                .andExpect(jsonPath("$.totalCount").value(0));
    }

    // ---------- status·carrier·기간·정렬 ----------

    @Test
    @DisplayName("T4 필터: status SHIPPING 2 · carrier HANJIN 1 · 발송일 경계 포함 · LATEST/OLDEST 정렬")
    void list_statusCarrierPeriodSort() throws Exception {
        list(Map.of("scope", "ALL", "status", "SHIPPING"))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items[*].deliveryId").value(containsInAnyOrder(D1_PID, D4_PID)));
        list(Map.of("scope", "ALL", "carrier", "HANJIN"))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].deliveryId").value(D2_PID));
        // D2 shipped_at = 2026-01-20T10:00:00 — 경계 양끝 포함
        list(Map.of("scope", "ALL", "from", "2026-01-20T10:00:00", "to", "2026-01-20T10:00:00"))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].deliveryId").value(D2_PID));
        list(Map.of("scope", "ALL", "from", "2026-01-20T10:00:01"))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items[*].deliveryId").value(containsInAnyOrder(D3_PID, D4_PID)));
        list(Map.of("scope", "ALL", "to", "2026-01-19T23:59:59"))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].deliveryId").value(D1_PID));
        list(Map.of("scope", "ALL"))
                .andExpect(jsonPath("$.items[0].deliveryId").value(D4_PID))
                .andExpect(jsonPath("$.items[3].deliveryId").value(D1_PID));
        list(Map.of("scope", "ALL", "sort", "OLDEST"))
                .andExpect(jsonPath("$.items[0].deliveryId").value(D1_PID))
                .andExpect(jsonPath("$.items[3].deliveryId").value(D4_PID));
    }

    @Test
    @DisplayName("T5 400: from>to · keyword 51자 · 허용 외 scope/status/carrier/sort")
    void list_malformed() throws Exception {
        mockMvc.perform(get(LIST_URL).headers(authHeaders.admin(ADMIN)).param("from", "2026-02-01T00:00:00").param("to", "2026-01-01T00:00:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.admin(ADMIN)).param("keyword", KEYWORD_LIMIT_EXCEEDED))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        for (String[] bogus : new String[][] {{"scope", "BOGUS"}, {"status", "BOGUS"}, {"carrier", "BOGUS"}, {"sort", "BOGUS"}}) {
            mockMvc.perform(get(LIST_URL).headers(authHeaders.admin(ADMIN)).param(bogus[0], bogus[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }
    }

    // ---------- 상세 ----------

    @Test
    @DisplayName("T6 상세: 200(배송지 스냅샷·품목·클레임) · 미존재 404 DELIVERY_NOT_FOUND")
    void detail() throws Exception {
        mockMvc.perform(get(LIST_URL + "/" + D4_PID).headers(authHeaders.admin(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryId").value(D4_PID))
                .andExpect(jsonPath("$.orderId").value(ORDER_A_PID))
                .andExpect(jsonPath("$.orderNo").value(ORDER_A_NO))
                .andExpect(jsonPath("$.orderItemId").value(ITEM_A1_PID))
                .andExpect(jsonPath("$.productName").value("배송상품A"))
                .andExpect(jsonPath("$.quantity").value(1))
                .andExpect(jsonPath("$.orderItemStatus").value("EXCHANGE_REQUESTED"))
                .andExpect(jsonPath("$.trackingNo").value(D4_TRACKING))
                .andExpect(jsonPath("$.shippingAddress.recipientName").value("홍길동검색"))
                .andExpect(jsonPath("$.shippingAddress.recipientPhone").value("010-1234-5678"))
                .andExpect(jsonPath("$.shippingAddress.addressRoad").value("서울 테스트로 1"))
                .andExpect(jsonPath("$.claimId").value(CLAIM_EXCHANGE_PID))
                .andExpect(jsonPath("$.claimType").value("EXCHANGE"))
                .andExpect(jsonPath("$.claimStatus").value("APPROVED"));
        mockMvc.perform(get(LIST_URL + "/" + D1_PID).headers(authHeaders.admin(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimId").doesNotExist());
        mockMvc.perform(get(LIST_URL + "/" + MISSING_PID).headers(authHeaders.admin(ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DELIVERY_NOT_FOUND"));
    }

    // ---------- 송장 정정 ----------

    @Test
    @DisplayName("T7 송장 정정: SHIPPING 200·값 반영·상태/발송시각 불변·감사 DELIVERY UPDATE 1행(reason)")
    void correctTracking_shipping() throws Exception {
        mockMvc.perform(patch(LIST_URL + "/" + D1_PID + "/tracking").headers(authHeaders.admin(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(correctionBody("LOGEN", NEW_TRACKING, "택배사 오입력 정정")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryPublicId").value(D1_PID))
                .andExpect(jsonPath("$.status").value("SHIPPING"))
                .andExpect(jsonPath("$.carrier").value("LOGEN"))
                .andExpect(jsonPath("$.trackingNo").value(NEW_TRACKING));

        Map<String, Object> row = deliveryRow(D1);
        assertThat(row.get("carrier")).isEqualTo("LOGEN");
        assertThat(row.get("tracking_no")).isEqualTo(NEW_TRACKING);
        assertThat(row.get("status")).isEqualTo("SHIPPING");
        assertThat(row.get("shipped_at").toString()).startsWith("2026-01-10 10:00:00");
        assertThat(auditCount(D1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT diff_json FROM audit_log WHERE target_type = 'DELIVERY' AND target_id = ?",
                String.class, D1)).contains(D1_TRACKING).contains(NEW_TRACKING).contains("택배사 오입력 정정");
    }

    @Test
    @DisplayName("T8 송장 정정 실패: DELIVERED 422 DELIVERY_INVALID_STATE(값·감사 불변) · 사유 공백 400 · carrier 누락 400 · 미존재 404")
    void correctTracking_rejected() throws Exception {
        mockMvc.perform(patch(LIST_URL + "/" + D2_PID + "/tracking").headers(authHeaders.admin(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(correctionBody("CJ", NEW_TRACKING, "사유")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DELIVERY_INVALID_STATE"));
        assertThat(deliveryRow(D2).get("tracking_no")).isEqualTo(D2_TRACKING);
        assertThat(deliveryRow(D2).get("status")).isEqualTo("DELIVERED");
        assertThat(auditCount(D2)).isZero();

        mockMvc.perform(patch(LIST_URL + "/" + D1_PID + "/tracking").headers(authHeaders.admin(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(correctionBody("CJ", NEW_TRACKING, " ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("reason"));
        mockMvc.perform(patch(LIST_URL + "/" + D1_PID + "/tracking").headers(authHeaders.admin(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"trackingNo\":\"X\",\"reason\":\"사유\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(deliveryRow(D1).get("tracking_no")).isEqualTo(D1_TRACKING);
        assertThat(auditCount(D1)).isZero();

        mockMvc.perform(patch(LIST_URL + "/" + MISSING_PID + "/tracking").headers(authHeaders.admin(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(correctionBody("CJ", NEW_TRACKING, "사유")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DELIVERY_NOT_FOUND"));
    }

    @Test
    @DisplayName("T9 송장 중복: 타 배송 번호 409 DELIVERY_TRACKING_NO_CONFLICT(불변) · 자기 행 기존 번호 재저장은 200·무변경이면 감사 0행")
    void correctTracking_duplicate() throws Exception {
        mockMvc.perform(patch(LIST_URL + "/" + D1_PID + "/tracking").headers(authHeaders.admin(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(correctionBody("CJ", D4_TRACKING, "사유")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELIVERY_TRACKING_NO_CONFLICT"));
        assertThat(deliveryRow(D1).get("tracking_no")).isEqualTo(D1_TRACKING);
        assertThat(auditCount(D1)).isZero();

        // 자기 자신의 기존 번호로 택배사만 정정 → 충돌 아님·carrier 변경 감사 1행
        mockMvc.perform(patch(LIST_URL + "/" + D1_PID + "/tracking").headers(authHeaders.admin(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(correctionBody("POST", D1_TRACKING, "택배사만 정정")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.carrier").value("POST"))
                .andExpect(jsonPath("$.trackingNo").value(D1_TRACKING));
        assertThat(auditCount(D1)).isEqualTo(1);

        // 같은 값 재요청 → 200·무변경·감사 행 증가 없음
        mockMvc.perform(patch(LIST_URL + "/" + D1_PID + "/tracking").headers(authHeaders.admin(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(correctionBody("POST", D1_TRACKING, "재요청")))
                .andExpect(status().isOk());
        assertThat(auditCount(D1)).isEqualTo(1);
    }

    // ---------- helpers ----------

    private ResultActions list(Map<String, String> params) throws Exception {
        var request = get(LIST_URL).headers(authHeaders.admin(ADMIN));
        params.forEach(request::param);
        return mockMvc.perform(request).andExpect(status().isOk());
    }

    private static String correctionBody(String carrier, String trackingNo, String reason) {
        return "{\"carrier\":\"" + carrier + "\",\"trackingNo\":\"" + trackingNo + "\",\"reason\":\"" + reason + "\"}";
    }

    private Map<String, Object> deliveryRow(long id) {
        return jdbc.queryForMap("SELECT carrier, tracking_no, status, shipped_at FROM delivery WHERE id = ?", id);
    }

    private long auditCount(long deliveryId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target_type = 'DELIVERY' AND target_id = ?",
                Long.class, deliveryId);
        return count == null ? 0 : count;
    }

    private void seedAll() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        USER_ID, pid("usr_", "DLQUSR"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, '배송셀러', '대표', 'ACTIVE', NOW(6), NOW(6))", SELLER_ID, pid("slr_", "DLQSLR"));
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, '배송상품', 'SALE', 10000, NOW(6), NOW(6))", PRODUCT_ID, pid("prd_", "DLQPRD"), SELLER_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VCDLQ', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))", VARIANT_ID, pid("var_", "DLQVAR"), PRODUCT_ID, DUMMY_FK_ID);
                seedOrder(ORDER_A, ORDER_A_PID, ORDER_A_NO, "홍길동검색");
                seedOrder(ORDER_B, pid("ord_", "DLQORDB"), ORDER_B_NO, "김철수검색");
                seedOrderItem(ITEM_A1, ITEM_A1_PID, ORDER_A, "배송상품A", "EXCHANGE_REQUESTED");
                seedOrderItem(ITEM_B1, pid("oit_", "DLQITB"), ORDER_B, "배송상품B", "RETURNED");
                seedClaim(CLAIM_RETURN, CLAIM_RETURN_PID, ITEM_B1, "RETURN");
                seedClaim(CLAIM_EXCHANGE, CLAIM_EXCHANGE_PID, ITEM_A1, "EXCHANGE");
                seedDelivery(D1, D1_PID, ITEM_A1, "OUTBOUND", "CJ", D1_TRACKING, "SHIPPING", "2026-01-10 10:00:00", null, null);
                seedDelivery(D2, D2_PID, ITEM_B1, "OUTBOUND", "HANJIN", D2_TRACKING, "DELIVERED", "2026-01-20 10:00:00", "2026-01-22 10:00:00", null);
                seedDelivery(D3, D3_PID, ITEM_B1, "RETURN", "POST", D3_TRACKING, "DELIVERED", "2026-02-01 10:00:00", "2026-02-03 10:00:00", CLAIM_RETURN);
                seedDelivery(D4, D4_PID, ITEM_A1, "OUTBOUND", "LOGEN", D4_TRACKING, "SHIPPING", "2026-02-10 10:00:00", null, CLAIM_EXCHANGE);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedOrder(long id, String publicId, String orderNo, String recipientName) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, 'DELIVERED', ?, 0, 0, NOW(6), NOW(6))",
                id, publicId, USER_ID, orderNo, ITEM_PRICE);
        jdbc.update("INSERT INTO order_shipping_snapshot (id, order_id, recipient_name, recipient_phone, zonecode, address_road, "
                + "address_detail, created_at, updated_at) VALUES (?, ?, ?, '010-1234-5678', '04524', '서울 테스트로 1', '101호', NOW(6), NOW(6))",
                id, id, recipientName);
    }

    private void seedOrderItem(long id, String publicId, long orderId, String productName, String itemStatus) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                + "total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(6), NOW(6), ?, 1000)",
                id, publicId, orderId, PRODUCT_ID, VARIANT_ID, SELLER_ID, ITEM_PRICE, ITEM_PRICE, itemStatus, productName);
    }

    private void seedClaim(long id, String publicId, long orderItemId, String type) {
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, previous_order_item_status, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, 'PRODUCT_DEFECT', 'APPROVED', 'DELIVERED', NOW(6), NOW(6))",
                id, publicId, orderItemId, type);
    }

    private void seedDelivery(long id, String publicId, long orderItemId, String direction, String carrier, String trackingNo,
            String status, String shippedAt, String deliveredAt, Long claimId) {
        jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, direction, carrier, tracking_no, status, shipped_at, "
                + "delivered_at, claim_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(6), NOW(6))",
                id, publicId, orderItemId, direction, carrier, trackingNo, status, shippedAt, deliveredAt, claimId);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM audit_log WHERE target_type = 'DELIVERY' AND target_id IN (?, ?, ?, ?)", D1, D2, D3, D4);
                jdbc.update("DELETE FROM delivery WHERE id IN (?, ?, ?, ?)", D1, D2, D3, D4);
                jdbc.update("DELETE FROM claim WHERE id IN (?, ?)", CLAIM_RETURN, CLAIM_EXCHANGE);
                jdbc.update("DELETE FROM order_item WHERE id IN (?, ?)", ITEM_A1, ITEM_B1);
                jdbc.update("DELETE FROM order_shipping_snapshot WHERE order_id IN (?, ?)", ORDER_A, ORDER_B);
                jdbc.update("DELETE FROM `order` WHERE id IN (?, ?)", ORDER_A, ORDER_B);
                jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id = ?", USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
