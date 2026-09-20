package com.zslab.mall.claim.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.claim.controller.response.AdminClaimSummaryResponse;
import com.zslab.mall.claim.controller.response.ClaimResponse;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.order.controller.response.AdminOrderDetailResponse;
import com.zslab.mall.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
 * 셀러 클레임 목록·상세 + 셀러 품목 클레임 요약 통합 테스트(Track 90-D-1·실 MariaDB·HTTP 경유·조회 전용).
 *
 * <p><b>시드 그래프</b>: 셀러 A(user A)·셀러 B(user B)·구매자 /
 * 주문 M(혼합·PAID) — 품목 A1(셀러 A·"혼합상품A") + 품목 B1(셀러 B·"혼합상품B") /
 * 주문 N(셀러 A 단독·DELIVERED) — 품목 A2("단독상품A") /
 * 클레임 C1(A1·RETURN·REQUESTED·2026-05-01·첨부 2장·reasonDetail) · C2(B1·CANCEL·REQUESTED·타 셀러) ·
 * C3(A2·EXCHANGE·REJECTED·2026-04-01·거부 메모) · C4(A2·EXCHANGE·APPROVED·2026-06-01·재청구·교환품 배송 SHIPPING·환불 COMPLETED 15,000).
 * 기대: 셀러 A 목록 = C4·C1·C3(요청일 최신순) — C2(타 셀러) 제외. 품목 A2 요약 = C4(최신)·claimCount 2.
 *
 * <p>시드는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}·? 바인딩(SQL injection 없음).
 */
@AutoConfigureMockMvc
class SellerClaimQueryControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String LIST_URL = "/api/v1/seller/claims";
    private static final String ORDER_ITEMS_URL = "/api/v1/seller/order-items";

    private static final long USER_A = 9850L;
    private static final long USER_B = 9851L;
    private static final long BUYER_ID = 9852L;
    private static final long SELLER_A = 9850L;
    private static final long SELLER_B = 9851L;
    private static final long PRODUCT_ID = 9850L;
    private static final long VARIANT_ID = 9850L;
    private static final long DUMMY_FK_ID = 9850L;
    private static final long ORDER_M = 9850L;
    private static final long ORDER_N = 9851L;
    private static final long ITEM_A1 = 9850L;
    private static final long ITEM_B1 = 9851L;
    private static final long ITEM_A2 = 9852L;
    private static final long CLAIM_C1 = 9850L;
    private static final long CLAIM_C2 = 9851L;
    private static final long CLAIM_C3 = 9852L;
    private static final long CLAIM_C4 = 9853L;
    private static final long ATTACHMENT_1 = 9850L;
    private static final long ATTACHMENT_2 = 9851L;
    private static final long DELIVERY_EXCHANGE = 9850L;
    private static final long REFUND_C4 = 9850L;
    private static final long PAYMENT_ID = 9850L;
    private static final long ITEM_PRICE = 15_000L;

    private static final String ORDER_M_NO = "ORDSCLM9850";
    private static final String ORDER_N_NO = "ORDSCLN9851";
    private static final String ITEM_A1_PID = pid("oit_", "SCLA1");
    private static final String ITEM_B1_PID = pid("oit_", "SCLB1");
    private static final String ITEM_A2_PID = pid("oit_", "SCLA2");
    private static final String CLAIM_C1_PID = pid("clm_", "SCLC1");
    private static final String CLAIM_C2_PID = pid("clm_", "SCLC2");
    private static final String CLAIM_C3_PID = pid("clm_", "SCLC3");
    private static final String CLAIM_C4_PID = pid("clm_", "SCLC4");
    private static final String ATTACHMENT_1_PID = pid("att_", "SCLAT1");
    private static final String ATTACHMENT_2_PID = pid("att_", "SCLAT2");
    private static final String ATTACHMENT_1_URL = "/api/v1/files/claims/2026/05/01SCLATT0000000000000000001.png";
    private static final String ATTACHMENT_2_URL = "/api/v1/files/claims/2026/05/01SCLATT0000000000000000002.png";
    private static final String MISSING_PID = pid("clm_", "SCLNONE");
    private static final String REASON_DETAIL_C1 = "상자가 파손되어 도착했습니다";
    private static final String REJECT_MEMO_C3 = "내부메모 정책상 불가";
    /** 구매자 기기 원본 파일명(attachment.file_name) — 셀러 응답 어디에도 나오면 안 된다. */
    private static final String BUYER_FILE_NAME = "홍길동사진.png";
    private static final String BUYER_NAME = "클레임구매자";
    private static final String KEYWORD_LIMIT_EXCEEDED = "K".repeat(51);

    /** 목록 행 키 화이트리스트 — 필드가 늘면 여기와 SellerClaimSummaryResponse를 함께 바꿔야 한다. */
    private static final Set<String> SUMMARY_KEYS = Set.of("claimId", "type", "status", "requestedAt", "processedAt", "orderNo",
            "productName", "optionLabel", "reasonCode", "reasonDetail", "refundStatus", "attachmentCount");
    /** 상세 키 화이트리스트(목록 행 + rejectReasonCode + attachments + exchangeDeliveryStatus). 거부 메모(rejectMemo)는 상세에도 없다. */
    private static final Set<String> DETAIL_KEYS = union(SUMMARY_KEYS, Set.of("rejectReasonCode", "attachments", "exchangeDeliveryStatus"));
    private static final Set<String> ATTACHMENT_KEYS = Set.of("attachmentId", "url");
    /** 품목 행 클레임 요약(SellerOrderItemClaimResponse). */
    private static final Set<String> ITEM_CLAIM_KEYS = Set.of("claimId", "type", "status", "requestedAt");
    /** 셀러 응답이 어느 층위에서든 가질 수 있는 키 전체(관리자·구매자 클레임 DTO 필드에서 뺄 허용 집합). */
    private static final Set<String> SELLER_ALLOWED_KEYS = union(SUMMARY_KEYS, DETAIL_KEYS, ATTACHMENT_KEYS, ITEM_CLAIM_KEYS);
    /** 노출 판정 표의 차단 항목(수동). 자동 도출분과 합집합으로 쓰며, 원본 DTO에서 사라져도 여기 항목은 남는다. */
    private static final Set<String> MANUAL_FORBIDDEN_KEYS = Set.of("buyerName", "buyerEmail", "buyer", "buyerId", "userId", "requestedBy",
            "orderId", "availableActions", "approvable", "actions", "rejectMemo", "amount", "refundAmount", "totalPrice", "fileName",
            "attachmentUrls", "uploadedBy");
    /**
     * 셀러 노출 금지 키 = (관리자 클레임 목록 행·관리자 주문 상세 ClaimRow·구매자 상세 ClaimResponse의 선언 필드 — 중첩 record·List&lt;record&gt;
     * 포함 — 셀러 허용 키) ∪ 수동 목록. 원본 DTO에 민감 필드가 추가되면 셀러 허용 집합에 없는 한 자동으로 금지 키가 돼 이 테스트가 알게 된다.
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
    @Autowired
    private EntityManagerFactory entityManagerFactory;

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

    @Test
    @DisplayName("T1 인가: 목록·상세 비인증 401 · 구매자 403 · 셀러 200")
    void authorization() throws Exception {
        mockMvc.perform(get(LIST_URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(LIST_URL).headers(authHeaders.buyer(BUYER_ID))).andExpect(status().isForbidden());
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A))).andExpect(status().isOk());
        mockMvc.perform(get(LIST_URL + "/" + CLAIM_C1_PID)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(LIST_URL + "/" + CLAIM_C1_PID).headers(authHeaders.buyer(BUYER_ID))).andExpect(status().isForbidden());
        mockMvc.perform(get(LIST_URL + "/" + CLAIM_C1_PID).headers(authHeaders.seller(USER_A))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T2 혼합 주문 스코프: 셀러 A 목록 = C4·C1·C3(요청일 최신순·타 셀러 C2 제외)·키 화이트리스트·금지 키 0·구매자/메모/파일명/금액 문자열 0 · 셀러 B = C2만")
    void list_scopeAndWhitelist() throws Exception {
        String body = mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.items[0].claimId").value(CLAIM_C4_PID))
                .andExpect(jsonPath("$.items[0].type").value("EXCHANGE"))
                .andExpect(jsonPath("$.items[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.items[0].orderNo").value(ORDER_N_NO))
                .andExpect(jsonPath("$.items[0].refundStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.items[0].attachmentCount").value(0))
                .andExpect(jsonPath("$.items[1].claimId").value(CLAIM_C1_PID))
                .andExpect(jsonPath("$.items[1].orderNo").value(ORDER_M_NO))
                .andExpect(jsonPath("$.items[1].productName").value("혼합상품A"))
                .andExpect(jsonPath("$.items[1].reasonCode").value("PRODUCT_DEFECT"))
                .andExpect(jsonPath("$.items[1].reasonDetail").value(REASON_DETAIL_C1))
                .andExpect(jsonPath("$.items[1].attachmentCount").value(2))
                .andExpect(jsonPath("$.items[1].refundStatus").doesNotExist())
                .andExpect(jsonPath("$.items[2].claimId").value(CLAIM_C3_PID))
                .andExpect(jsonPath("$.items[2].status").value("REJECTED"))
                .andExpect(jsonPath("$.items[2].processedAt").exists())
                .andReturn().getResponse().getContentAsString();

        // NON_NULL 직렬화라 null 필드는 생략된다 → 키 집합은 화이트리스트의 부분집합이어야 하고, 값이 있는 키는 전부 나와야 한다.
        JsonNode items = objectMapper.readTree(body).get("items");
        for (JsonNode row : items) {
            assertThat(SUMMARY_KEYS).containsAll(keysOf(row));
            assertThat(keysOf(row)).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
        }
        Set<String> c1Keys = new LinkedHashSet<>(SUMMARY_KEYS);
        c1Keys.remove("processedAt");
        c1Keys.remove("optionLabel");
        c1Keys.remove("refundStatus");
        assertThat(keysOf(items.get(1))).containsExactlyInAnyOrderElementsOf(c1Keys);
        assertThat(body).doesNotContain("혼합상품B").doesNotContain(REJECT_MEMO_C3).doesNotContain(BUYER_FILE_NAME)
                .doesNotContain(BUYER_NAME).doesNotContain(String.valueOf(ITEM_PRICE)).doesNotContain("buyer@");

        // 셀러 B 관점: 같은 주문 M에서 B1의 C2만 보인다.
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].claimId").value(CLAIM_C2_PID));
    }

    @Test
    @DisplayName("T3 필터: type RETURN 1 · status REJECTED 1 · keyword 주문번호 정확 2 · 상품명 부분 '단독' 2 · 타 셀러 상품명 0 · 요청일 from/to 경계")
    void list_filters() throws Exception {
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("type", "RETURN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].claimId").value(CLAIM_C1_PID));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("status", "REJECTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].claimId").value(CLAIM_C3_PID));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("keyword", ORDER_N_NO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("keyword", "단독"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2));
        // 타 셀러 상품명으로 검색해도 그 셀러의 클레임은 잡히지 않는다(범위 조건이 최선두)
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("keyword", "혼합상품B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A))
                        .param("from", "2026-04-15T00:00:00").param("to", "2026-05-31T23:59:59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].claimId").value(CLAIM_C1_PID));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("from", "2026-06-01T09:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].claimId").value(CLAIM_C4_PID));
    }

    @Test
    @DisplayName("T4 400: 허용 외 type · 허용 외 status · keyword 51자 · from>to")
    void list_badRequest() throws Exception {
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("type", "REFUND"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("status", "DONE"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("keyword", KEYWORD_LIMIT_EXCEEDED))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A))
                        .param("from", "2026-06-01T00:00:00").param("to", "2026-05-01T00:00:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("T5 상세: C1 첨부 2건{attachmentId,url}(순서·fileName 없음)·키 화이트리스트 · C4 exchangeDeliveryStatus SHIPPING·refundStatus만(금액 없음) · C3 rejectReasonCode 코드만·rejectMemo 없음")
    void detail_ownClaim() throws Exception {
        String c1 = mockMvc.perform(get(LIST_URL + "/" + CLAIM_C1_PID).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimId").value(CLAIM_C1_PID))
                .andExpect(jsonPath("$.type").value("RETURN"))
                .andExpect(jsonPath("$.reasonDetail").value(REASON_DETAIL_C1))
                .andExpect(jsonPath("$.attachmentCount").value(2))
                .andExpect(jsonPath("$.attachments.length()").value(2))
                .andExpect(jsonPath("$.attachments[0].attachmentId").value(ATTACHMENT_1_PID))
                .andExpect(jsonPath("$.attachments[0].url").value(ATTACHMENT_1_URL))
                .andExpect(jsonPath("$.attachments[1].attachmentId").value(ATTACHMENT_2_PID))
                .andExpect(jsonPath("$.attachments[1].url").value(ATTACHMENT_2_URL))
                .andExpect(jsonPath("$.exchangeDeliveryStatus").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        JsonNode c1Node = objectMapper.readTree(c1);
        Set<String> c1Keys = new LinkedHashSet<>(DETAIL_KEYS);
        c1Keys.remove("processedAt");
        c1Keys.remove("optionLabel");
        c1Keys.remove("refundStatus");
        c1Keys.remove("rejectReasonCode");
        c1Keys.remove("exchangeDeliveryStatus");
        assertThat(keysOf(c1Node)).containsExactlyInAnyOrderElementsOf(c1Keys);
        assertThat(keysOf(c1Node)).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
        for (JsonNode attachment : c1Node.get("attachments")) {
            assertThat(keysOf(attachment)).containsExactlyInAnyOrderElementsOf(ATTACHMENT_KEYS);
        }
        assertThat(c1).doesNotContain(BUYER_FILE_NAME).doesNotContain(BUYER_NAME);

        String c4 = mockMvc.perform(get(LIST_URL + "/" + CLAIM_C4_PID).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("EXCHANGE"))
                .andExpect(jsonPath("$.exchangeDeliveryStatus").value("SHIPPING"))
                .andExpect(jsonPath("$.refundStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.attachments.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(keysOf(objectMapper.readTree(c4))).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
        assertThat(c4).doesNotContain(String.valueOf(ITEM_PRICE));

        // REJECTED: 거부 사유 코드만 노출·거부 메모(관리자 기록)는 없음. 목록 행에는 코드도 없다(T2 SUMMARY_KEYS).
        String c3 = mockMvc.perform(get(LIST_URL + "/" + CLAIM_C3_PID).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.processedAt").exists())
                .andExpect(jsonPath("$.rejectReasonCode").value("OUT_OF_POLICY"))
                .andExpect(jsonPath("$.rejectMemo").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(c3).doesNotContain(REJECT_MEMO_C3).doesNotContain("rejectMemo");
        assertThat(keysOf(objectMapper.readTree(c3))).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
        assertThat(c1).doesNotContain("rejectReasonCode"); // 거부 전 null → NON_NULL 생략
    }

    @Test
    @DisplayName("T6 소유권 경계: 타 셀러 클레임 404(403 아님·CLAIM_NOT_FOUND) · 미존재 404 · 소유 셀러는 200")
    void detail_ownershipBoundary() throws Exception {
        mockMvc.perform(get(LIST_URL + "/" + CLAIM_C2_PID).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
        mockMvc.perform(get(LIST_URL + "/" + MISSING_PID).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
        mockMvc.perform(get(LIST_URL + "/" + CLAIM_C2_PID).headers(authHeaders.seller(USER_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimId").value(CLAIM_C2_PID));
    }

    @Test
    @DisplayName("T7 품목 클레임 요약(1:N): A2 = 최신 C4·claimCount 2 · A1 = C1·claimCount 1 · 상세 동일 · 타 셀러 B1 요약은 C2(A 목록에 없음) · 요약 키 4개")
    void orderItem_claimSummary() throws Exception {
        String body = mockMvc.perform(get(ORDER_ITEMS_URL).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items[0].orderItemId").value(ITEM_A2_PID))
                .andExpect(jsonPath("$.items[0].claim.claimId").value(CLAIM_C4_PID))
                .andExpect(jsonPath("$.items[0].claim.status").value("APPROVED"))
                .andExpect(jsonPath("$.items[0].claimCount").value(2))
                .andExpect(jsonPath("$.items[1].orderItemId").value(ITEM_A1_PID))
                .andExpect(jsonPath("$.items[1].claim.claimId").value(CLAIM_C1_PID))
                .andExpect(jsonPath("$.items[1].claimCount").value(1))
                .andReturn().getResponse().getContentAsString();
        JsonNode items = objectMapper.readTree(body).get("items");
        for (JsonNode row : items) {
            assertThat(keysOf(row.get("claim"))).containsExactlyInAnyOrderElementsOf(ITEM_CLAIM_KEYS);
        }
        assertThat(body).doesNotContain(CLAIM_C2_PID).doesNotContain(CLAIM_C3_PID).doesNotContain(REASON_DETAIL_C1);

        mockMvc.perform(get(ORDER_ITEMS_URL + "/" + ITEM_A2_PID).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claim.claimId").value(CLAIM_C4_PID))
                .andExpect(jsonPath("$.claim.type").value("EXCHANGE"))
                .andExpect(jsonPath("$.claimCount").value(2));

        mockMvc.perform(get(ORDER_ITEMS_URL).headers(authHeaders.seller(USER_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].claim.claimId").value(CLAIM_C2_PID))
                .andExpect(jsonPath("$.items[0].claimCount").value(1));
    }

    @Test
    @DisplayName("T8 쿼리 예산(N+1 없음): 클레임 목록 3행 = 7(액터 해소 1 + count·page 2 + 배치 4) · 품목 목록 2행 = 8(액터 해소 1 + count·page 2 + 배치 5·클레임 +1)")
    void queryBudget() throws Exception {
        assertThat(countQueries(get(LIST_URL).headers(authHeaders.seller(USER_A)), 3)).isEqualTo(7);
        assertThat(countQueries(get(ORDER_ITEMS_URL).headers(authHeaders.seller(USER_A)), 2)).isEqualTo(8);
    }

    /**
     * 목록 1회 호출의 SQL 실행 수(Hibernate Statistics·prepared statement 기준·Track 80 T6 관례). 실측: 셀러 액터 해소
     * (seller_user membership) 1이 매 요청 고정으로 더해진다(필터의 user 상태 조회는 Hibernate 통계에 잡히지 않음).
     */
    private long countQueries(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, int expectedRows)
            throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(expectedRows));
        long count = statistics.getPrepareStatementCount();
        statistics.setStatisticsEnabled(false);
        return count;
    }

    // ---------- seed·helpers ----------

    private void seedAll() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedSellerWithOwner(USER_A, SELLER_A, "SCLUSA", "SCLSLA", "클레임셀러A");
                seedSellerWithOwner(USER_B, SELLER_B, "SCLUSB", "SCLSLB", "클레임셀러B");
                jdbc.update("INSERT INTO `user` (id, public_id, name, email, created_at, updated_at) VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
                        BUYER_ID, pid("usr_", "SCLBUY"), BUYER_NAME, "buyer@scl.test");
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, '클레임상품', 'SALE', 15000, NOW(6), NOW(6))", PRODUCT_ID, pid("prd_", "SCLPRD"), SELLER_A, DUMMY_FK_ID);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VCSCL', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))", VARIANT_ID, pid("var_", "SCLVAR"), PRODUCT_ID, DUMMY_FK_ID);
                seedOrder(ORDER_M, pid("ord_", "SCLORM"), ORDER_M_NO, "PAID", "2026-03-10 09:00:00", "2026-03-10 09:05:00", "혼합수령인");
                seedOrder(ORDER_N, pid("ord_", "SCLORN"), ORDER_N_NO, "DELIVERED", "2026-04-05 09:00:00", "2026-04-05 09:05:00", "단독수령인");
                seedOrderItem(ITEM_A1, ITEM_A1_PID, ORDER_M, SELLER_A, "혼합상품A", "RETURN_REQUESTED");
                seedOrderItem(ITEM_B1, ITEM_B1_PID, ORDER_M, SELLER_B, "혼합상품B", "CANCEL_REQUESTED");
                seedOrderItem(ITEM_A2, ITEM_A2_PID, ORDER_N, SELLER_A, "단독상품A", "EXCHANGE_REQUESTED");
                seedClaim(CLAIM_C1, CLAIM_C1_PID, ITEM_A1, "RETURN", "PRODUCT_DEFECT", REASON_DETAIL_C1, "REQUESTED", "DELIVERED",
                        "2026-05-01 10:00:00", null, null, null, null);
                seedClaim(CLAIM_C2, CLAIM_C2_PID, ITEM_B1, "CANCEL", "BUYER_CHANGED_MIND", "타셀러사유", "REQUESTED", "PAID",
                        "2026-05-02 10:00:00", null, null, null, null);
                seedClaim(CLAIM_C3, CLAIM_C3_PID, ITEM_A2, "EXCHANGE", "WRONG_PRODUCT", "첫 교환 요청", "REJECTED", "DELIVERED",
                        "2026-04-01 10:00:00", "2026-04-02 10:00:00", "OUT_OF_POLICY", REJECT_MEMO_C3, VARIANT_ID);
                seedClaim(CLAIM_C4, CLAIM_C4_PID, ITEM_A2, "EXCHANGE", "WRONG_PRODUCT", "재청구 교환 요청", "APPROVED", "DELIVERED",
                        "2026-06-01 10:00:00", "2026-06-02 10:00:00", null, null, VARIANT_ID);
                seedAttachment(ATTACHMENT_1, ATTACHMENT_1_PID, CLAIM_C1, ATTACHMENT_1_URL, 0);
                seedAttachment(ATTACHMENT_2, ATTACHMENT_2_PID, CLAIM_C1, ATTACHMENT_2_URL, 1);
                jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, direction, carrier, tracking_no, status, shipped_at, "
                        + "delivered_at, claim_id, created_at, updated_at) VALUES (?, ?, ?, 'OUTBOUND', 'CJ', 'SCL-EXC-TRK', 'SHIPPING', "
                        + "'2026-06-05 10:00:00', NULL, ?, NOW(6), NOW(6))", DELIVERY_EXCHANGE, pid("dlv_", "SCLDLV"), ITEM_A2, CLAIM_C4);
                jdbc.update("INSERT INTO refund (id, public_id, claim_id, payment_id, amount, status, refunded_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'COMPLETED', '2026-06-03 10:00:00', NOW(6), NOW(6))",
                        REFUND_C4, pid("rfn_", "SCLRFN"), CLAIM_C4, PAYMENT_ID, ITEM_PRICE);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedSellerWithOwner(long userId, long sellerId, String userTag, String sellerTag, String companyName) {
        jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                userId, pid("usr_", userTag));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, '대표', 'ACTIVE', NOW(6), NOW(6))", sellerId, pid("slr_", sellerTag), companyName);
        jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'", userId, sellerId);
    }

    private void seedOrder(long id, String publicId, String orderNo, String status, String orderedAt, String paidAt, String recipientName) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                + "ordered_at, paid_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 0, 0, ?, ?, NOW(6), NOW(6))",
                id, publicId, BUYER_ID, orderNo, status, ITEM_PRICE, orderedAt, paidAt);
        jdbc.update("INSERT INTO order_shipping_snapshot (id, order_id, recipient_name, recipient_phone, zonecode, address_road, "
                + "address_detail, created_at, updated_at) VALUES (?, ?, ?, '010-1111-2222', '04524', '서울 클레임로 1', '101호', NOW(6), NOW(6))",
                id, id, recipientName);
    }

    private void seedOrderItem(long id, String publicId, long orderId, long sellerId, String productName, String itemStatus) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                + "total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(6), NOW(6), ?, 1000)",
                id, publicId, orderId, PRODUCT_ID, VARIANT_ID, sellerId, ITEM_PRICE, ITEM_PRICE, itemStatus, productName);
    }

    private void seedClaim(long id, String publicId, long orderItemId, String type, String reasonCode, String reasonDetail, String status,
            String previousStatus, String requestedAt, String processedAt, String rejectReasonCode, String rejectMemo, Long exchangeVariantId) {
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, reason_detail, status, previous_order_item_status, "
                + "requested_by, requested_at, processed_at, reject_reason_code, reject_memo, exchange_variant_id, version, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NOW(6), NOW(6))",
                id, publicId, orderItemId, type, reasonCode, reasonDetail, status, previousStatus, BUYER_ID, requestedAt, processedAt,
                rejectReasonCode, rejectMemo, exchangeVariantId);
    }

    private void seedAttachment(long id, String publicId, long claimId, String url, int displayOrder) {
        jdbc.update("INSERT INTO attachment (id, public_id, target_type, target_id, file_name, file_path, mime_type, file_size, "
                + "display_order, uploaded_by, created_at, updated_at) VALUES (?, ?, 'CLAIM', ?, ?, ?, 'image/png', 1, ?, ?, NOW(6), NOW(6))",
                id, publicId, claimId, BUYER_FILE_NAME, url, displayOrder, BUYER_ID);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM refund WHERE id = ?", REFUND_C4);
                jdbc.update("DELETE FROM delivery WHERE id = ?", DELIVERY_EXCHANGE);
                jdbc.update("DELETE FROM attachment WHERE id IN (?, ?)", ATTACHMENT_1, ATTACHMENT_2);
                jdbc.update("DELETE FROM claim WHERE id IN (?, ?, ?, ?)", CLAIM_C1, CLAIM_C2, CLAIM_C3, CLAIM_C4);
                jdbc.update("DELETE FROM order_item WHERE id IN (?, ?, ?)", ITEM_A1, ITEM_B1, ITEM_A2);
                jdbc.update("DELETE FROM order_shipping_snapshot WHERE order_id IN (?, ?)", ORDER_M, ORDER_N);
                jdbc.update("DELETE FROM `order` WHERE id IN (?, ?)", ORDER_M, ORDER_N);
                jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller_user WHERE user_id IN (?, ?)", USER_A, USER_B);
                jdbc.update("DELETE FROM seller WHERE id IN (?, ?)", SELLER_A, SELLER_B);
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?, ?)", USER_A, USER_B, BUYER_ID);
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
        Set<String> sourceKeys = new LinkedHashSet<>();
        collectRecordKeys(AdminClaimSummaryResponse.class, sourceKeys);
        collectRecordKeys(AdminOrderDetailResponse.ClaimRow.class, sourceKeys);
        collectRecordKeys(ClaimResponse.class, sourceKeys);
        sourceKeys.removeAll(SELLER_ALLOWED_KEYS);
        sourceKeys.addAll(MANUAL_FORBIDDEN_KEYS);
        return Set.copyOf(sourceKeys);
    }

    /** record 컴포넌트명을 모으고, 컴포넌트 타입이 record이거나 List&lt;record&gt;면 재귀한다(중첩 ReturnShipmentResponse 등). */
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
