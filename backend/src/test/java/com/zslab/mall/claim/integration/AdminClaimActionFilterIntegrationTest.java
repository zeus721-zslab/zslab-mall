package com.zslab.mall.claim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 클레임 "필요 액션" 필터 매트릭스(Track 96-4 D-205·C-02). 액션별 경계 상태를 native seed로 적재하고, {@code action} 필터
 * 결과 집합이 같은 데이터를 action 없이 조회했을 때 {@code availableActions}(Java 계산)에 해당 액션을 가진 행 집합과 항상 같음을
 * 강제한다 — availableActions 규칙이 바뀌면 Specification·대시보드 카운트가 함께 바뀌지 않는 한 본 테스트가 깨진다.
 *
 * <p>각 행의 기대 액션도 하드코딩 단언한다(시드가 의도한 경계를 실제로 만드는지 이중 고정). 방향별 Delivery·Refund 복수 행은
 * MAX(id) 판정(D4)을 검증한다. 시드 품목 상품명을 검색어로 걸어 다른 테스트 잔여 행과 격리한다(AdminClaimIntegrationTest 시드 패턴 1:1).
 */
@AutoConfigureMockMvc
@Transactional
class AdminClaimActionFilterIntegrationTest extends AbstractIntegrationTest {

    private static final String CLAIMS_URL = "/api/v1/admin/claims";
    private static final String DASHBOARD_URL = "/api/v1/admin/dashboard";
    private static final long ADMIN = 7001L;
    private static final long BUYER = 9601L;
    private static final long SELLER = 9001L;
    private static final long ORDER_ID = 96_400L;
    private static final long ID_BASE = 96_400L;
    /** 시드 품목 상품명 = 검색어(부분일치)·다른 테스트 잔여 행 격리용. */
    private static final String PRODUCT_NAME = "매트릭스96-4";
    private static final List<String> FOLLOWUP_ACTIONS = List.of("CONFIRM_PICKUP", "INSPECT", "REGISTER_EXCHANGE_SHIPMENT",
            "MARK_EXCHANGE_DELIVERED", "INITIATE_REFUND");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthHeaders authHeaders;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    /** 케이스 태그 → 기대 액션 목록(빈 목록 = 필터 비대상). */
    private final Map<String, List<String>> expectedByTag = new LinkedHashMap<>();
    private final Map<String, String> claimPidByTag = new HashMap<>();
    private long nextId = ID_BASE;
    private JsonNode dashboardBefore;

    @BeforeEach
    void seedMatrix() throws Exception {
        dashboardBefore = fetchDashboard();
        seed(() -> {
            seedOrder();
            // --- REQUESTED: 필터 비대상(APPROVE·REJECT는 기존 status 필터) ---
            claim("M01", ClaimType.CANCEL, ClaimStatus.REQUESTED, false, null, List.of("APPROVE", "REJECT"));
            claim("M23", ClaimType.EXCHANGE, ClaimStatus.REQUESTED, false, null, List.of("APPROVE", "REJECT"));
            // --- CONFIRM_PICKUP: 회수 송장 있고 미회수 / 송장 없으면 빈 목록(구매자 대기) ---
            claim("M02", ClaimType.RETURN, ClaimStatus.APPROVED, false, null, List.of());
            long m03 = claim("M03", ClaimType.RETURN, ClaimStatus.APPROVED, false, null, List.of("CONFIRM_PICKUP"));
            delivery(m03, "RETURN", "SHIPPING");
            long m04 = claim("M04", ClaimType.EXCHANGE, ClaimStatus.APPROVED, false, null, List.of("CONFIRM_PICKUP"));
            delivery(m04, "RETURN", "SHIPPING");
            // --- INSPECT: 회수 후 미검수 ---
            long m05 = claim("M05", ClaimType.RETURN, ClaimStatus.APPROVED, true, null, List.of("INSPECT"));
            delivery(m05, "RETURN", "DELIVERED");
            long m06 = claim("M06", ClaimType.EXCHANGE, ClaimStatus.APPROVED, true, null, List.of("INSPECT"));
            delivery(m06, "RETURN", "DELIVERED");
            // --- 교환 발송·배송완료: OUTBOUND 없음 / SHIPPING / DELIVERED(핸들러 유실·빈 목록) / 복수 행 MAX(id) ---
            long m07 = claim("M07", ClaimType.EXCHANGE, ClaimStatus.APPROVED, true, "PASS", List.of("REGISTER_EXCHANGE_SHIPMENT"));
            delivery(m07, "RETURN", "DELIVERED");
            long m08 = claim("M08", ClaimType.EXCHANGE, ClaimStatus.APPROVED, true, "PASS", List.of("MARK_EXCHANGE_DELIVERED"));
            delivery(m08, "RETURN", "DELIVERED");
            delivery(m08, "OUTBOUND", "SHIPPING");
            long m09 = claim("M09", ClaimType.EXCHANGE, ClaimStatus.APPROVED, true, "PASS", List.of());
            delivery(m09, "RETURN", "DELIVERED");
            delivery(m09, "OUTBOUND", "DELIVERED");
            long m10 = claim("M10", ClaimType.EXCHANGE, ClaimStatus.APPROVED, true, "PASS", List.of("MARK_EXCHANGE_DELIVERED"));
            delivery(m10, "OUTBOUND", "DELIVERED"); // 낮은 id
            delivery(m10, "OUTBOUND", "SHIPPING"); // 높은 id = 최신 → SHIPPING
            long m11 = claim("M11", ClaimType.EXCHANGE, ClaimStatus.APPROVED, true, "PASS", List.of());
            delivery(m11, "OUTBOUND", "SHIPPING"); // 낮은 id(EXISTS SHIPPING이면 오판)
            delivery(m11, "OUTBOUND", "DELIVERED"); // 최신 = DELIVERED
            // EXCHANGE는 환불 개시 대상 아님(FAILED 환불이 있어도 배송완료만)
            long m25 = claim("M25", ClaimType.EXCHANGE, ClaimStatus.APPROVED, true, "PASS", List.of("MARK_EXCHANGE_DELIVERED"));
            delivery(m25, "OUTBOUND", "SHIPPING");
            refund(m25, "FAILED");
            // --- INITIATE_REFUND: CANCEL APPROVED / RETURN PASS · "환불된 금액" 행(PENDING·COMPLETED·PG 성공 FAILED) 없음 · 품목 잔여 있음 ---
            claim("M12", ClaimType.CANCEL, ClaimStatus.APPROVED, false, null, List.of("INITIATE_REFUND"));
            long m13 = claim("M13", ClaimType.CANCEL, ClaimStatus.APPROVED, false, null, List.of("INITIATE_REFUND"));
            refund(m13, "FAILED");
            long m14 = claim("M14", ClaimType.CANCEL, ClaimStatus.APPROVED, false, null, List.of());
            refund(m14, "COMPLETED");
            long m15 = claim("M15", ClaimType.CANCEL, ClaimStatus.APPROVED, false, null, List.of());
            refund(m15, "PENDING");
            long m16 = claim("M16", ClaimType.CANCEL, ClaimStatus.APPROVED, false, null, List.of());
            refund(m16, "FAILED"); // 낮은 id
            refund(m16, "PENDING"); // 최신 = 활성
            // Track 104-3a: 최신이 FAILED여도 완료 행이 있으면 개시 불가 — initiate 게이트가 완료 행을 돌려줘(no-op) 버튼과 어긋나던 조합
            long m17 = claim("M17", ClaimType.CANCEL, ClaimStatus.APPROVED, false, null, List.of());
            refund(m17, "COMPLETED"); // 낮은 id
            refund(m17, "FAILED"); // 최신 = FAILED
            // Track 104-3a: 실패 처리했지만 PG가 성공을 통지한 환불 → 이미 환불된 금액이라 개시 불가
            long m28 = claim("M28", ClaimType.CANCEL, ClaimStatus.APPROVED, false, null, List.of());
            refundPgSucceeded(m28);
            long m18 = claim("M18", ClaimType.RETURN, ClaimStatus.APPROVED, true, "PASS", List.of("INITIATE_REFUND"));
            delivery(m18, "RETURN", "DELIVERED");
            long m19 = claim("M19", ClaimType.RETURN, ClaimStatus.APPROVED, true, "PASS", List.of());
            delivery(m19, "RETURN", "DELIVERED");
            refund(m19, "COMPLETED");
            // RETURN PASS인데 미회수(비정상 데이터): Java는 회수 분기에서 끝나 환불 개시가 아니다 → Spec도 picked_up_at 조건 필수
            claim("M20", ClaimType.RETURN, ClaimStatus.APPROVED, false, "PASS", List.of());
            // --- 종결 상태 ---
            claim("M21", ClaimType.CANCEL, ClaimStatus.REJECTED, false, null, List.of());
            long m22 = claim("M22", ClaimType.RETURN, ClaimStatus.COMPLETED, true, "PASS", List.of());
            refund(m22, "COMPLETED");
            long m27 = claim("M27", ClaimType.RETURN, ClaimStatus.COMPLETED, true, "PASS", List.of());
            refund(m27, "FAILED");
        });
    }

    @Test
    @DisplayName("M1 매트릭스: 각 행 availableActions == 기대 · action 필터 결과 집합 == 해당 액션 보유 행 집합 · FOLLOWUP == 5종 합집합 · 대시보드 카운트 == FOLLOWUP 건수")
    void actionFilter_matchesAvailableActions() throws Exception {
        Map<String, List<String>> actualByPid = actionsByClaimId(list());
        assertThat(actualByPid).hasSize(expectedByTag.size());
        Map<String, Set<String>> claimsByAction = new HashMap<>();
        for (Map.Entry<String, List<String>> expected : expectedByTag.entrySet()) {
            String pid = claimPidByTag.get(expected.getKey());
            assertThat(actualByPid.get(pid)).as("availableActions of %s", expected.getKey()).isEqualTo(expected.getValue());
            for (String action : expected.getValue()) {
                claimsByAction.computeIfAbsent(action, key -> new HashSet<>()).add(pid);
            }
        }

        Set<String> followup = new HashSet<>();
        for (String action : FOLLOWUP_ACTIONS) {
            Set<String> expectedIds = claimsByAction.getOrDefault(action, Set.of());
            assertThat(expectedIds).as("matrix must cover %s", action).isNotEmpty();
            JsonNode filtered = list("action", action);
            assertThat(actionsByClaimId(filtered).keySet()).as("action=%s", action).isEqualTo(expectedIds);
            assertThat(filtered.get("totalCount").asLong()).isEqualTo(expectedIds.size());
            followup.addAll(expectedIds);
        }
        JsonNode followupPage = list("action", "FOLLOWUP");
        assertThat(actionsByClaimId(followupPage).keySet()).isEqualTo(followup);
        assertThat(followupPage.get("totalCount").asLong()).isEqualTo(followup.size());

        JsonNode dashboardAfter = fetchDashboard();
        long delta = dashboardAfter.at("/pending/claimFollowup").asLong() - dashboardBefore.at("/pending/claimFollowup").asLong();
        assertThat(delta).isEqualTo(followup.size());
    }

    @Test
    @DisplayName("M2 허용 외 값 400(APPROVE·REJECT·미지 값) · 다른 필터와 AND · 페이징 총건수 유지")
    void actionFilter_validation_and_combination() throws Exception {
        for (String invalid : List.of("APPROVE", "REJECT", "FOO")) {
            mockMvc.perform(get(CLAIMS_URL).headers(authHeaders.admin(ADMIN)).param("action", invalid))
                    .andExpect(status().isBadRequest());
        }

        Set<String> exchangeFollowup = Set.of(pidOf("M04"), pidOf("M06"), pidOf("M07"), pidOf("M08"), pidOf("M10"), pidOf("M25"));
        JsonNode exchange = list("action", "FOLLOWUP", "type", "EXCHANGE");
        assertThat(actionsByClaimId(exchange).keySet()).isEqualTo(exchangeFollowup);

        mockMvc.perform(get(CLAIMS_URL).headers(authHeaders.admin(ADMIN))
                        .param("keyword", PRODUCT_NAME).param("action", "FOLLOWUP").param("status", "REQUESTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0));
        mockMvc.perform(get(CLAIMS_URL).headers(authHeaders.admin(ADMIN))
                        .param("keyword", PRODUCT_NAME).param("action", "INITIATE_REFUND").param("refundStatus", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1)); // M13(M17은 완료 행·M28은 PG 성공으로 제외·Track 104-3a)

        long followupTotal = list("action", "FOLLOWUP").get("totalCount").asLong();
        JsonNode page = list("action", "FOLLOWUP", "size", "2");
        assertThat(page.get("totalCount").asLong()).isEqualTo(followupTotal);
        assertThat(page.get("items").size()).isEqualTo(2);
        assertThat(page.get("hasNext").asBoolean()).isTrue();
    }

    // ===== 조회 헬퍼 =====

    /** 시드 상품명 검색어 + size 100(≤ MAX_PAGE_SIZE·params에 size가 있으면 그 값)으로 매트릭스 행만 조회한다. */
    private JsonNode list(String... params) throws Exception {
        var request = get(CLAIMS_URL).headers(authHeaders.admin(ADMIN)).param("keyword", PRODUCT_NAME);
        boolean sizeGiven = false;
        for (int index = 0; index < params.length; index += 2) {
            request = request.param(params[index], params[index + 1]);
            sizeGiven |= "size".equals(params[index]);
        }
        if (!sizeGiven) {
            request = request.param("size", "100");
        }
        String body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode fetchDashboard() throws Exception {
        String body = mockMvc.perform(get(DASHBOARD_URL).headers(authHeaders.admin(ADMIN)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static Map<String, List<String>> actionsByClaimId(JsonNode page) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (JsonNode item : page.get("items")) {
            List<String> actions = new java.util.ArrayList<>();
            item.get("availableActions").forEach(action -> actions.add(action.asText()));
            result.put(item.get("claimId").asText(), actions);
        }
        return result;
    }

    private String pidOf(String tag) {
        return claimPidByTag.get(tag);
    }

    // ===== 시드 헬퍼(AdminClaimIntegrationTest 패턴·?n 바인딩·정적 SQL) =====

    private void seed(Runnable seedingWork) {
        try {
            execute("SET FOREIGN_KEY_CHECKS = 0");
            seedingWork.run();
        } finally {
            execute("SET FOREIGN_KEY_CHECKS = 1");
        }
        entityManager.flush();
        entityManager.clear();
    }

    private void seedOrder() {
        entityManager.createNativeQuery(
                        "INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                                + "discount_amount, shipping_fee, created_at, updated_at) "
                                + "VALUES (?1, ?2, ?3, ?4, 'PAID', 10000, 0, 0, NOW(6), NOW(6))")
                .setParameter(1, ORDER_ID)
                .setParameter(2, pid("ord_", "M964ORD"))
                .setParameter(3, BUYER)
                .setParameter(4, "ORDM964")
                .executeUpdate();
    }

    /** 품목 1 + 클레임 1을 적재하고 클레임 id를 돌려준다. requested_at은 id 순으로 증가시켜 정렬을 결정적으로 둔다. */
    private long claim(String tag, ClaimType type, ClaimStatus status, boolean pickedUp, String inspectionResult,
            List<String> expectedActions) {
        long itemId = nextId++;
        long claimId = nextId++;
        entityManager.createNativeQuery(
                        "INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                                + "quantity, unit_price, total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                                + "VALUES (?1, ?2, ?3, 1, 1, ?4, 1, 10000, 10000, 'DELIVERED', NOW(6), NOW(6), ?5, 1000)")
                .setParameter(1, itemId)
                .setParameter(2, pid("oit_", "M964" + tag))
                .setParameter(3, ORDER_ID)
                .setParameter(4, SELLER)
                .setParameter(5, PRODUCT_NAME)
                .executeUpdate();
        String claimPid = pid("clm_", "M964" + tag);
        entityManager.createNativeQuery(
                        "INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, previous_order_item_status, "
                                + "requested_by, requested_at, processed_at, picked_up_at, inspected_at, inspection_result, restock, "
                                + "created_at, updated_at) "
                                + "VALUES (?1, ?2, ?3, ?4, 'BUYER_CHANGED_MIND', ?5, 'DELIVERED', ?6, "
                                + "TIMESTAMPADD(SECOND, ?1, '2026-01-01 00:00:00'), NOW(6), "
                                + "CASE WHEN ?7 = 1 THEN NOW(6) ELSE NULL END, "
                                + "CASE WHEN ?8 = '' THEN NULL ELSE NOW(6) END, NULLIF(?8, ''), "
                                + "CASE WHEN ?8 = 'PASS' THEN 1 ELSE NULL END, NOW(6), NOW(6))")
                .setParameter(1, claimId)
                .setParameter(2, claimPid)
                .setParameter(3, itemId)
                .setParameter(4, type.name())
                .setParameter(5, status.name())
                .setParameter(6, BUYER)
                .setParameter(7, pickedUp ? 1 : 0)
                .setParameter(8, inspectionResult == null ? "" : inspectionResult) // null 바인딩 회피(빈 문자열 = 미검수)
                .executeUpdate();
        expectedByTag.put(tag, expectedActions);
        claimPidByTag.put(tag, claimPid);
        return claimId;
    }

    private void delivery(long claimId, String direction, String status) {
        long id = nextId++;
        entityManager.createNativeQuery(
                        "INSERT INTO delivery (id, public_id, order_item_id, direction, carrier, tracking_no, status, shipped_at, "
                                + "delivered_at, claim_id, created_at, updated_at) "
                                + "SELECT ?1, ?2, c.order_item_id, ?3, 'CJ', ?4, ?5, NOW(6), "
                                + "CASE WHEN ?5 = 'DELIVERED' THEN NOW(6) ELSE NULL END, c.id, NOW(6), NOW(6) FROM claim c WHERE c.id = ?6")
                .setParameter(1, id)
                .setParameter(2, pid("dlv_", "M964D" + id))
                .setParameter(3, direction)
                .setParameter(4, "M964-TRK-" + id)
                .setParameter(5, status)
                .setParameter(6, claimId)
                .executeUpdate();
    }

    private void refund(long claimId, String status) {
        long id = nextId++;
        entityManager.createNativeQuery(
                        "INSERT INTO refund (id, public_id, claim_id, payment_id, amount, status, refunded_at, created_at, updated_at) "
                                + "VALUES (?1, ?2, ?3, 1, 10000, ?4, CASE WHEN ?4 = 'COMPLETED' THEN NOW(6) ELSE NULL END, NOW(6), NOW(6))")
                .setParameter(1, id)
                .setParameter(2, pid("rfn_", "M964R" + id))
                .setParameter(3, claimId)
                .setParameter(4, status)
                .executeUpdate();
    }

    /** 실패 처리했지만 PG 성공 통지가 기록된 환불(Track 104-3a·pg_refund_succeeded_at). */
    private void refundPgSucceeded(long claimId) {
        long id = nextId++;
        entityManager.createNativeQuery(
                        "INSERT INTO refund (id, public_id, claim_id, payment_id, amount, status, pg_refund_succeeded_at, created_at, updated_at) "
                                + "VALUES (?1, ?2, ?3, 1, 10000, 'FAILED', NOW(6), NOW(6), NOW(6))")
                .setParameter(1, id)
                .setParameter(2, pid("rfn_", "M964R" + id))
                .setParameter(3, claimId)
                .executeUpdate();
    }

    private void execute(String sql) {
        entityManager.createNativeQuery(sql).executeUpdate();
    }

    /** prefix + 26자 본문(tag 대문자 + '0' 패딩·[0-9A-Z])으로 30자 public_id를 만든다(@Pattern 정합). */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
