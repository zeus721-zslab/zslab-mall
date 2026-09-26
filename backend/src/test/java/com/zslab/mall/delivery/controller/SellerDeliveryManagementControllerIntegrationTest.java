package com.zslab.mall.delivery.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 셀러 배송 목록·송장 정정 통합 테스트(Track 90-B-1·실 MariaDB·HTTP 경유). {@code AdminDeliveryQueryControllerIntegrationTest} 시드 그래프를
 * 셀러 2명으로 나눈다.
 *
 * <p><b>시드 그래프</b>: 셀러 A(user A·상태 파라미터)·셀러 B(user B·ACTIVE) / 주문 A(수령인 "홍길동검색")·품목 A1(셀러 A) / 주문 B(수령인 "김철수검색")·
 * 품목 B1(셀러 B) / 클레임 RETURN(B1)·EXCHANGE(A1) / 배송 4행 — D1 원 발송 A1 SHIPPING CJ · D2 원 발송 B1 DELIVERED HANJIN ·
 * D3 반품 회수 B1(RETURN·RETURN 클레임) · D4 교환품 발송 A1(OUTBOUND·EXCHANGE 클레임).
 * 셀러 A 기대: ORIGINAL 1(D1) / CLAIM_OUTBOUND 1(D4) / RETURN 0 / ALL 2 — D2·D3(셀러 B)은 어떤 scope에서도 보이지 않는다.
 *
 * <p>트랜잭션: 송장 정정 커밋·감사 행을 JdbcTemplate로 검증하므로 클래스 {@code @Transactional} 없음. 시드/정리는
 * {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(try-finally 복원). 모든 시드 INSERT는 ? positional 바인딩·정적 SQL이다.
 */
@AutoConfigureMockMvc
class SellerDeliveryManagementControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String LIST_URL = "/api/v1/seller/deliveries";

    private static final long USER_A = 9630L;
    private static final long USER_B = 9631L;
    private static final long BUYER_ID = 9632L;
    private static final long SELLER_A = 9630L;
    private static final long SELLER_B = 9631L;
    private static final long PRODUCT_ID = 9630L;
    private static final long VARIANT_ID = 9630L;
    private static final long DUMMY_FK_ID = 9630L;
    private static final long ORDER_A = 9630L;
    private static final long ORDER_B = 9631L;
    private static final long ITEM_A1 = 9630L;
    private static final long ITEM_B1 = 9631L;
    private static final long CLAIM_RETURN = 9630L;
    private static final long CLAIM_EXCHANGE = 9631L;
    private static final long D1 = 9630L;
    private static final long D2 = 9631L;
    private static final long D3 = 9632L;
    private static final long D4 = 9633L;
    private static final long ITEM_PRICE = 10_000L;

    private static final String ORDER_A_NO = "ORDSDLA9630";
    private static final String ORDER_B_NO = "ORDSDLB9631";
    private static final String ITEM_A1_PID = pid("oit_", "SDLITA");
    private static final String CLAIM_EXCHANGE_PID = pid("clm_", "SDLCLX");
    private static final String D1_PID = pid("dlv_", "SDLD1");
    private static final String D2_PID = pid("dlv_", "SDLD2");
    private static final String D3_PID = pid("dlv_", "SDLD3");
    private static final String D4_PID = pid("dlv_", "SDLD4");
    private static final String MISSING_PID = pid("dlv_", "SDLNONE");
    private static final String D1_TRACKING = "SDL-TRK-A1";
    private static final String D2_TRACKING = "SDL-TRK-B1";
    private static final String D3_TRACKING = "SDL-TRK-R1";
    private static final String D4_TRACKING = "SDL-TRK-X1";
    private static final String NEW_TRACKING = "SDL-TRK-A1-FIXED";
    private static final String SUSPENDED_DETAIL = "정지된 판매자는 조회만 가능합니다";

    /** 목록 행 키 화이트리스트 — 필드가 늘면 여기와 SellerDeliverySummaryResponse를 함께 바꿔야 한다. */
    private static final Set<String> ROW_KEYS = Set.of("deliveryId", "orderItemId", "orderNo", "productName", "optionLabel", "quantity",
            "recipientName", "direction", "status", "carrier", "trackingNo", "shippedAt", "deliveredAt", "claimId", "claimType");
    /** 송장 정정 응답 키 화이트리스트 — 필드가 늘면 여기와 SellerDeliveryTrackingCorrectionResponse를 함께 바꿔야 한다. */
    private static final Set<String> CORRECTION_KEYS = Set.of("deliveryPublicId", "status", "carrier", "trackingNo");
    /** 관리자 응답에는 있으나 셀러 노출 금지·불필요 키. */
    private static final Set<String> FORBIDDEN_KEYS = Set.of("orderId", "buyer", "buyerName", "buyerEmail", "shippingAddress",
            "orderItemStatus", "claimStatus");

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
    @DisplayName("T2 셀러 경계 × scope: A는 ORIGINAL 1(D1)·CLAIM_OUTBOUND 1(D4)·RETURN 0·ALL 2 / B는 ORIGINAL 1(D2)·RETURN 1(D3)·ALL 2")
    void list_sellerBoundaryByScope() throws Exception {
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].deliveryId").value(D1_PID))
                .andExpect(jsonPath("$.items[0].orderItemId").value(ITEM_A1_PID))
                .andExpect(jsonPath("$.items[0].orderNo").value(ORDER_A_NO))
                .andExpect(jsonPath("$.items[0].recipientName").value("홍길동검색"))
                .andExpect(jsonPath("$.items[0].claimId").doesNotExist());
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("scope", "CLAIM_OUTBOUND"))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].deliveryId").value(D4_PID))
                .andExpect(jsonPath("$.items[0].claimId").value(CLAIM_EXCHANGE_PID))
                .andExpect(jsonPath("$.items[0].claimType").value("EXCHANGE"));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("scope", "RETURN"))
                .andExpect(jsonPath("$.totalCount").value(0));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("scope", "ALL"))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items[*].deliveryId", containsInAnyOrder(D1_PID, D4_PID)));

        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_B)))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].deliveryId").value(D2_PID));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_B)).param("scope", "RETURN"))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].deliveryId").value(D3_PID));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_B)).param("scope", "ALL"))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items[*].deliveryId", containsInAnyOrder(D2_PID, D3_PID)));
    }

    @Test
    @DisplayName("T3 응답 키 화이트리스트(예상 밖 필드 추가 시 실패)·관리자 전용 키 없음 · keyword 3축 셀러 경계(타 셀러 송장·주문번호·수령인명 0·본인 1 대칭)")
    void list_responseWhitelist() throws Exception {
        String body = mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("scope", "ALL"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // NON_NULL 직렬화라 null 필드(optionLabel·deliveredAt·claimId 등)는 생략 → 부분집합 + 값 있는 키 전부 존재로 고정한다.
        for (JsonNode row : objectMapper.readTree(body).get("items")) {
            assertThat(ROW_KEYS).containsAll(keysOf(row));
            assertThat(keysOf(row)).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
            assertThat(keysOf(row)).contains("deliveryId", "orderItemId", "orderNo", "productName", "quantity", "recipientName",
                    "direction", "status", "carrier", "trackingNo", "shippedAt");
        }
        JsonNode d4 = objectMapper.readTree(body).get("items").get(0);
        Set<String> d4Keys = new LinkedHashSet<>(ROW_KEYS);
        d4Keys.remove("optionLabel");
        d4Keys.remove("deliveredAt");
        assertThat(keysOf(d4)).containsExactlyInAnyOrderElementsOf(d4Keys);

        // keyword 3축(송장·주문번호·수령인명) 모두 셀러 범위 안에서만 매칭된다 — A가 B의 값을 넣으면 0, B가 넣으면 1(대칭).
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("scope", "ALL").param("keyword", D2_TRACKING))
                .andExpect(jsonPath("$.totalCount").value(0));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("keyword", D1_TRACKING))
                .andExpect(jsonPath("$.totalCount").value(1));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("scope", "ALL").param("keyword", ORDER_B_NO))
                .andExpect(jsonPath("$.totalCount").value(0));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("scope", "ALL").param("keyword", "김철수"))
                .andExpect(jsonPath("$.totalCount").value(0));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_B)).param("keyword", ORDER_B_NO))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].deliveryId").value(D2_PID));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_B)).param("keyword", "김철수"))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].deliveryId").value(D2_PID));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("keyword", "K".repeat(51)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("T4 송장 정정: 자기 SHIPPING 배송 200·값 반영·상태/발송시각 불변·감사 DELIVERY UPDATE 1행(actor_role SELLER·reason)")
    void correctTracking_ownShipping() throws Exception {
        String body = mockMvc.perform(patch(LIST_URL + "/" + D1_PID + "/tracking").headers(authHeaders.seller(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(correctionBody("LOGEN", NEW_TRACKING, "택배사 오입력 정정")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryPublicId").value(D1_PID))
                .andExpect(jsonPath("$.status").value("SHIPPING"))
                .andExpect(jsonPath("$.carrier").value("LOGEN"))
                .andExpect(jsonPath("$.trackingNo").value(NEW_TRACKING))
                .andReturn().getResponse().getContentAsString();
        // 셀러 전용 응답 DTO(공용 RegisterExchangeShipmentResponse 미재사용) — 4키 정확 일치(전부 non-null이라 NON_NULL 생략 없음).
        assertThat(keysOf(objectMapper.readTree(body))).containsExactlyInAnyOrderElementsOf(CORRECTION_KEYS);

        Map<String, Object> row = deliveryRow(D1);
        assertThat(row.get("carrier")).isEqualTo("LOGEN");
        assertThat(row.get("tracking_no")).isEqualTo(NEW_TRACKING);
        assertThat(row.get("status")).isEqualTo("SHIPPING");
        assertThat(row.get("shipped_at").toString()).startsWith("2026-01-10 10:00:00");
        assertThat(auditCount(D1)).isEqualTo(1);
        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT actor_user_id, actor_role, diff_json FROM audit_log WHERE target_type = 'DELIVERY' AND target_id = ?", D1);
        assertThat(((Number) audit.get("actor_user_id")).longValue()).isEqualTo(USER_A);
        assertThat(audit.get("actor_role")).isEqualTo("SELLER");
        assertThat(audit.get("diff_json").toString()).contains(D1_TRACKING).contains(NEW_TRACKING).contains("택배사 오입력 정정");
    }

    @Test
    @DisplayName("T5 송장 정정 거부: 타 셀러 배송 404(존재 은닉·값 불변) · 미존재 404 · DELIVERED 422 · 사유 공백 400 · 타 배송 번호는 허용 200(D-227)")
    void correctTracking_rejected() throws Exception {
        // 셀러 A가 셀러 B의 SHIPPING 아닌 D2·회수 D3에 접근 → 404(403 아님)
        mockMvc.perform(patch(LIST_URL + "/" + D2_PID + "/tracking").headers(authHeaders.seller(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(correctionBody("CJ", NEW_TRACKING, "사유")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DELIVERY_NOT_FOUND"));
        assertThat(deliveryRow(D2).get("tracking_no")).isEqualTo(D2_TRACKING);
        mockMvc.perform(patch(LIST_URL + "/" + MISSING_PID + "/tracking").headers(authHeaders.seller(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(correctionBody("CJ", NEW_TRACKING, "사유")))
                .andExpect(status().isNotFound());
        // 셀러 B의 자기 배송이지만 DELIVERED → 422
        mockMvc.perform(patch(LIST_URL + "/" + D2_PID + "/tracking").headers(authHeaders.seller(USER_B))
                        .contentType(MediaType.APPLICATION_JSON).content(correctionBody("CJ", NEW_TRACKING, "사유")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DELIVERY_INVALID_STATE"));
        // 자기 행의 기존 번호·같은 택배사 재요청 → 200·감사 0행(무변경)
        mockMvc.perform(patch(LIST_URL + "/" + D1_PID + "/tracking").headers(authHeaders.seller(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(correctionBody("CJ", D1_TRACKING, "사유")))
                .andExpect(status().isOk());
        assertThat(auditCount(D1)).isZero();
        mockMvc.perform(patch(LIST_URL + "/" + D1_PID + "/tracking").headers(authHeaders.seller(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(correctionBody("CJ", NEW_TRACKING, " ")))
                .andExpect(status().isBadRequest());
        assertThat(deliveryRow(D1).get("tracking_no")).isEqualTo(D1_TRACKING);
        // 타 배송(D4)의 송장번호로 정정 → 합포장·택배사 번호 재사용이라 허용(유니크·409 사전 검사 제거)
        mockMvc.perform(patch(LIST_URL + "/" + D1_PID + "/tracking").headers(authHeaders.seller(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(correctionBody("CJ", D4_TRACKING, "합포장 송장으로 정정")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackingNo").value(D4_TRACKING));
        assertThat(deliveryRow(D1).get("tracking_no")).isEqualTo(D4_TRACKING);
    }

    @Test
    @DisplayName("T6 D-190: SUSPENDED 셀러 목록 200 · 송장 정정 403 SELLER_SUSPENDED(고정 문구·값 불변·감사 0행) · malformed body는 400(값·감사 불변)")
    void suspended_readAllowed_writeForbidden() throws Exception {
        cleanup();
        seedAll(SellerStatus.SUSPENDED);

        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1));
        mockMvc.perform(patch(LIST_URL + "/" + D1_PID + "/tracking").headers(authHeaders.seller(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(correctionBody("LOGEN", NEW_TRACKING, "정지 중 정정 시도")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELLER_SUSPENDED"))
                .andExpect(jsonPath("$.detail").value(SUSPENDED_DETAIL));
        assertThat(deliveryRow(D1).get("tracking_no")).isEqualTo(D1_TRACKING);
        assertThat(auditCount(D1)).isZero();
        // 계약(외부 검토 r2·resolver 위치 불변): @Valid가 상태 가드보다 먼저 실행돼 malformed body는 SUSPENDED여도 403이 아닌 400이다.
        // 어느 경로든 쓰기는 차단되고 DB는 변하지 않는다 — 여기서는 사유 공백(400)으로 고정한다.
        mockMvc.perform(patch(LIST_URL + "/" + D1_PID + "/tracking").headers(authHeaders.seller(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(correctionBody("LOGEN", NEW_TRACKING, " ")))
                .andExpect(status().isBadRequest());
        assertThat(deliveryRow(D1).get("tracking_no")).isEqualTo(D1_TRACKING);
        assertThat(auditCount(D1)).isZero();
    }

    @ParameterizedTest(name = "{0} 셀러 → 목록·정정 모두 401")
    @EnumSource(value = SellerStatus.class, names = {"PENDING", "TERMINATED"})
    @DisplayName("T7 D-190: 세션 불가 상태 → 401 UNAUTHENTICATED·값 불변")
    void sessionDenied_returns401(SellerStatus status) throws Exception {
        cleanup();
        seedAll(status);

        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(patch(LIST_URL + "/" + D1_PID + "/tracking").headers(authHeaders.seller(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(correctionBody("LOGEN", NEW_TRACKING, "사유")))
                .andExpect(status().isUnauthorized());
        assertThat(deliveryRow(D1).get("tracking_no")).isEqualTo(D1_TRACKING);
    }

    // ---------- seed·helpers ----------

    private void seedAll(SellerStatus sellerAStatus) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedSellerWithOwner(USER_A, SELLER_A, "SDLUSA", "SDLSLA", "배송셀러A", sellerAStatus);
                seedSellerWithOwner(USER_B, SELLER_B, "SDLUSB", "SDLSLB", "배송셀러B", SellerStatus.ACTIVE);
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, '배송상품', 'SALE', 10000, NOW(6), NOW(6))", PRODUCT_ID, pid("prd_", "SDLPRD"), SELLER_A, DUMMY_FK_ID);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VCSDL', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))", VARIANT_ID, pid("var_", "SDLVAR"), PRODUCT_ID, DUMMY_FK_ID);
                seedOrder(ORDER_A, pid("ord_", "SDLORDA"), ORDER_A_NO, "홍길동검색");
                seedOrder(ORDER_B, pid("ord_", "SDLORDB"), ORDER_B_NO, "김철수검색");
                seedOrderItem(ITEM_A1, ITEM_A1_PID, ORDER_A, SELLER_A, "배송상품A", "EXCHANGE_REQUESTED");
                seedOrderItem(ITEM_B1, pid("oit_", "SDLITB"), ORDER_B, SELLER_B, "배송상품B", "RETURNED");
                seedClaim(CLAIM_RETURN, pid("clm_", "SDLCLR"), ITEM_B1, "RETURN");
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

    private void seedSellerWithOwner(long userId, long sellerId, String userTag, String sellerTag, String companyName,
            SellerStatus status) {
        jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                userId, pid("usr_", userTag));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, '대표', ?, NOW(6), NOW(6))", sellerId, pid("slr_", sellerTag), companyName, status.name());
        jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'", userId, sellerId);
    }

    private void seedOrder(long id, String publicId, String orderNo, String recipientName) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, 'DELIVERED', ?, 0, 0, NOW(6), NOW(6))",
                id, publicId, BUYER_ID, orderNo, ITEM_PRICE);
        jdbc.update("INSERT INTO order_shipping_snapshot (id, order_id, recipient_name, recipient_phone, zonecode, address_road, "
                + "address_detail, created_at, updated_at) VALUES (?, ?, ?, '010-1234-5678', '04524', '서울 테스트로 1', '101호', NOW(6), NOW(6))",
                id, id, recipientName);
    }

    private void seedOrderItem(long id, String publicId, long orderId, long sellerId, String productName, String itemStatus) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                + "total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(6), NOW(6), ?, 1000)",
                id, publicId, orderId, PRODUCT_ID, VARIANT_ID, sellerId, ITEM_PRICE, ITEM_PRICE, itemStatus, productName);
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
                jdbc.update("DELETE FROM seller_user WHERE user_id IN (?, ?)", USER_A, USER_B);
                jdbc.update("DELETE FROM seller WHERE id IN (?, ?)", SELLER_A, SELLER_B);
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?)", USER_A, USER_B);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private Map<String, Object> deliveryRow(long id) {
        return jdbc.queryForMap("SELECT carrier, tracking_no, status, shipped_at FROM delivery WHERE id = ?", id);
    }

    private Long auditCount(long deliveryId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target_type = 'DELIVERY' AND target_id = ?", Long.class,
                deliveryId);
    }

    private static String correctionBody(String carrier, String trackingNo, String reason) {
        return "{\"carrier\":\"" + carrier + "\",\"trackingNo\":\"" + trackingNo + "\",\"reason\":\"" + reason + "\"}";
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
