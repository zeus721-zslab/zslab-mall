package com.zslab.mall.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 관리자 운영 인박스 통합 테스트(D-248·실 MariaDB·HTTP 경유). 관리자 인박스는 전역 범위라 다른 테스트의 잔여 행이 섞일 수 있어, 시드 항목의
 * 포함·제외와 필드 값만 단언하고 전체 건수는 단언하지 않는다(건수는 같은 요청 안의 차이로만 본다). 시드 기준값은 "내일 00:00 − 기한 ± 1시간"
 * 으로 잡아 실행 시각과 무관하게 탭이 정해진다.
 *
 * <p>시드는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}·? 바인딩(SQL injection 없음).
 */
@AutoConfigureMockMvc
class AdminInboxIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/admin/inbox";
    private static final String SNOOZE_URL = "/api/v1/admin/inbox/snoozes";
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private static final long ADMIN_A = 948190L;
    private static final long ADMIN_B = 948191L;
    private static final long BUYER_ID = 948100L;
    private static final long SELLER_ACTIVE = 948101L;
    private static final long SELLER_PENDING = 948102L;
    private static final long PRODUCT_PENDING = 948101L;
    private static final long ORDER_ID = 948101L;
    private static final long DUMMY_FK_ID = 948100L;

    private static final long ITEM_REQUESTED = 948101L;
    private static final long ITEM_INSPECT = 948102L;
    private static final long ITEM_REFUND = 948103L;
    private static final long ITEM_PICKUP = 948104L;
    private static final long ITEM_EXCHANGE = 948105L;
    private static final long ITEM_SHIP_OVERDUE = 948106L;
    private static final long ITEM_SHIP_UPCOMING = 948107L;
    private static final long ITEM_SHIP_RECENT = 948108L;
    private static final long ITEM_APPROVED_IDLE = 948109L;

    private static final long CLAIM_REQUESTED = 948101L;
    private static final long CLAIM_INSPECT = 948102L;
    private static final long CLAIM_REFUND = 948103L;
    private static final long CLAIM_PICKUP = 948104L;
    private static final long CLAIM_EXCHANGE = 948105L;
    private static final long CLAIM_APPROVED_IDLE = 948106L;

    private static final long DELIVERY_PICKUP = 948101L;
    private static final long DELIVERY_EXCHANGE_OUT = 948102L;
    private static final long DELIVERY_SHIP_OVERDUE = 948103L;
    private static final long DELIVERY_SHIP_UPCOMING = 948104L;
    private static final long DELIVERY_SHIP_RECENT = 948105L;
    private static final long DELIVERY_EXCHANGE_RETURN = 948106L;
    private static final long REFUND_FAILED = 948101L;

    private static final long INQUIRY_TODAY = 948101L;
    private static final long INQUIRY_BOUNDARY = 948102L;
    private static final long INQUIRY_ANSWERED = 948103L;

    private static final long SETTLEMENT_CONFIRM_TODAY = 948101L;
    private static final long SETTLEMENT_CONFIRM_UPCOMING = 948102L;
    private static final long SETTLEMENT_PAYOUT_TODAY = 948103L;
    private static final long SETTLEMENT_NO_PAY_DATE = 948104L;
    private static final long ISSUE_OPEN = 948101L;

    private static final String CLAIM_REQUESTED_PID = pid("clm_", "AIBREQ");
    private static final String CLAIM_INSPECT_PID = pid("clm_", "AIBINS");
    private static final String CLAIM_REFUND_PID = pid("clm_", "AIBRFN");
    private static final String CLAIM_PICKUP_PID = pid("clm_", "AIBPCK");
    private static final String CLAIM_EXCHANGE_PID = pid("clm_", "AIBEXC");
    private static final String CLAIM_APPROVED_IDLE_PID = pid("clm_", "AIBIDL");
    private static final String DELIVERY_EXCHANGE_OUT_PID = pid("dlv_", "AIBEXO");
    private static final String DELIVERY_SHIP_OVERDUE_PID = pid("dlv_", "AIBSOD");
    private static final String DELIVERY_SHIP_UPCOMING_PID = pid("dlv_", "AIBSUP");
    private static final String DELIVERY_SHIP_RECENT_PID = pid("dlv_", "AIBSRC");
    private static final String INQUIRY_TODAY_PID = pid("inq_", "AIBTOD");
    private static final String INQUIRY_BOUNDARY_PID = pid("inq_", "AIBBND");
    private static final String INQUIRY_ANSWERED_PID = pid("inq_", "AIBANS");
    private static final String SELLER_PENDING_PID = pid("slr_", "AIBPND");
    private static final String SELLER_ACTIVE_PID = pid("slr_", "AIBACT");
    private static final String PRODUCT_PENDING_PID = pid("prd_", "AIBPND");

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
    private LocalDate today;
    private LocalDateTime tomorrowStart;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        today = LocalDate.now();
        tomorrowStart = today.plusDays(1).atStartOfDay();
        cleanup();
        seedAll();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("T1 인가: 비인증 401 · BUYER 401 · ADMIN 200")
    void authorization() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).with(authHeaders.buyer(BUYER_ID))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).with(authHeaders.admin(ADMIN_A))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T2 클레임 접수: REQUESTED만 · 기준 requestedAt · 기한 +24h · 시각은 +09:00 · 후속 조건 없는 APPROVED는 어느 유형에도 없음")
    void claimRequested() throws Exception {
        JsonNode todayInbox = fetch(ADMIN_A, "TODAY", "CLAIM_REQUESTED");
        JsonNode item = find(todayInbox, "CLAIM_REQUESTED", CLAIM_REQUESTED_PID).orElseThrow();
        // 기한 = 어제 23:00 — 실행 시각과 무관하게 경과
        LocalDateTime requestedAt = tomorrowStart.minusHours(49);
        assertThat(item.get("baseAt").asString()).isEqualTo(kst(requestedAt)).endsWith("+09:00");
        assertThat(item.get("dueAt").asString()).isEqualTo(kst(requestedAt.plusHours(24)));
        assertThat(item.get("overdue").asBoolean()).isTrue();
        assertThat(item.get("targetKey").asString()).isEqualTo("CLAIM");
        assertThat(item.get("title").asString()).isEqualTo("인박스상품");
        assertThat(item.get("subtitle").asString()).isEqualTo("ORDAIB948101");

        assertThat(findAnyTab(ADMIN_A, "CLAIM_REQUESTED", CLAIM_APPROVED_IDLE_PID)).isEmpty();
        assertThat(findFollowup(fetch(ADMIN_A, "TODAY", "CLAIM_FOLLOWUP"), CLAIM_APPROVED_IDLE_PID)).isEmpty();
        assertThat(findFollowup(fetch(ADMIN_A, "UPCOMING", "CLAIM_FOLLOWUP"), CLAIM_APPROVED_IDLE_PID)).isEmpty();
    }

    @Test
    @DisplayName("T3 클레임 후속: 단계별 기준 시각(검수=회수 시각 · 환불 실패 재시도=승인 시각 · 회수 확인=회수 송장 등록 · 교환 배송완료=교환 발송)")
    void claimFollowupStepBaseAt() throws Exception {
        LocalDateTime todayBase = tomorrowStart.minusHours(49);
        JsonNode todayInbox = fetch(ADMIN_A, "TODAY", "CLAIM_FOLLOWUP");
        assertThat(find(todayInbox, "CLAIM_FOLLOWUP", CLAIM_INSPECT_PID + ":INSPECT").orElseThrow().get("baseAt").asString())
                .isEqualTo(kst(todayBase));
        assertThat(find(todayInbox, "CLAIM_FOLLOWUP", CLAIM_REFUND_PID + ":REFUND").orElseThrow().get("baseAt").asString())
                .isEqualTo(kst(todayBase.minusHours(1)));
        assertThat(find(todayInbox, "CLAIM_FOLLOWUP", CLAIM_PICKUP_PID + ":PICKUP").orElseThrow().get("baseAt").asString())
                .isEqualTo(kst(todayBase.minusHours(2)));

        LocalDateTime exchangeShippedAt = tomorrowStart.minusDays(8);
        JsonNode exchange = find(todayInbox, "CLAIM_FOLLOWUP", CLAIM_EXCHANGE_PID + ":EXCH_DLVD").orElseThrow();
        assertThat(exchange.get("baseAt").asString()).isEqualTo(kst(exchangeShippedAt));
        assertThat(exchange.get("dueAt").asString()).isEqualTo(kst(exchangeShippedAt.plusHours(48)));
        assertThat(exchange.get("targetKey").asString()).isEqualTo("CLAIM");
        assertThat(findFollowup(fetch(ADMIN_A, "UPCOMING", "CLAIM_FOLLOWUP"), CLAIM_EXCHANGE_PID)).isEmpty();
    }

    @Test
    @DisplayName("T14 클레임 후속 단계 전이: 검수 단계에서 한 보류는 환불 개시 단계로 넘어가면 적용되지 않는다(ref에 단계 포함)")
    void followupSnoozeDoesNotCarryToNextStep() throws Exception {
        String inspectRef = findFollowup(fetch(ADMIN_A, "TODAY", "CLAIM_FOLLOWUP"), CLAIM_INSPECT_PID).orElseThrow()
                .get("ref").asString();
        snooze(ADMIN_A, "CLAIM_FOLLOWUP", inspectRef, OffsetDateTime.now(KST).plusDays(7), "검수 일정 조율");
        assertThat(findFollowup(fetch(ADMIN_A, "TODAY", "CLAIM_FOLLOWUP"), CLAIM_INSPECT_PID)).isEmpty();

        // 검수 PASS → 같은 클레임이 환불 개시 단계로 넘어간다(원천 전이)
        jdbc.update("UPDATE claim SET inspection_result = 'PASS', inspected_at = ? WHERE id = ?",
                tomorrowStart.minusHours(49), CLAIM_INSPECT);

        JsonNode refundStep = findFollowup(fetch(ADMIN_A, "TODAY", "CLAIM_FOLLOWUP"), CLAIM_INSPECT_PID).orElseThrow();
        assertThat(refundStep.get("ref").asString()).isEqualTo(CLAIM_INSPECT_PID + ":REFUND").isNotEqualTo(inspectRef);
    }

    @Test
    @DisplayName("T4 장기 배송중: 원 주문 발송만 · 발송 3일 경과부터 · 기한 +7일 경계로 탭 분리 · 교환 발송(claim_id)은 제외")
    void longShipping() throws Exception {
        JsonNode overdue = find(fetch(ADMIN_A, "TODAY", "LONG_SHIPPING"), "LONG_SHIPPING", DELIVERY_SHIP_OVERDUE_PID)
                .orElseThrow();
        assertThat(overdue.get("overdue").asBoolean()).isTrue();
        assertThat(overdue.get("targetKey").asString()).isEqualTo("DELIVERY");

        assertThat(find(fetch(ADMIN_A, "UPCOMING", "LONG_SHIPPING"), "LONG_SHIPPING", DELIVERY_SHIP_UPCOMING_PID)).isPresent();
        assertThat(findAnyTab(ADMIN_A, "LONG_SHIPPING", DELIVERY_SHIP_RECENT_PID)).isEmpty();
        assertThat(findAnyTab(ADMIN_A, "LONG_SHIPPING", DELIVERY_EXCHANGE_OUT_PID)).isEmpty();
    }

    @Test
    @DisplayName("T5 오늘/예정 KST 경계: 기한 = 내일 00:00 − 1ms는 오늘 · 기한 = 내일 00:00은 예정 · 답변된 문의는 없음")
    void todayUpcomingBoundary() throws Exception {
        JsonNode todayInbox = fetch(ADMIN_A, "TODAY", "INQUIRY_UNANSWERED");
        JsonNode upcomingInbox = fetch(ADMIN_A, "UPCOMING", "INQUIRY_UNANSWERED");

        assertThat(find(todayInbox, "INQUIRY_UNANSWERED", INQUIRY_TODAY_PID)).isPresent();
        assertThat(find(upcomingInbox, "INQUIRY_UNANSWERED", INQUIRY_TODAY_PID)).isEmpty();
        assertThat(find(upcomingInbox, "INQUIRY_UNANSWERED", INQUIRY_BOUNDARY_PID).orElseThrow().get("dueAt").asString())
                .isEqualTo(kst(tomorrowStart));
        assertThat(find(todayInbox, "INQUIRY_UNANSWERED", INQUIRY_BOUNDARY_PID)).isEmpty();
        assertThat(findAnyTab(ADMIN_A, "INQUIRY_UNANSWERED", INQUIRY_ANSWERED_PID)).isEmpty();
    }

    @Test
    @DisplayName("T6 셀러 심사(PENDING만)·상품 승인(updatedAt 기준·부제 셀러명)·불일치(OPEN·id 식별자)")
    void sellerProductReconciliation() throws Exception {
        assertThat(findAnyTab(ADMIN_A, "SELLER_REVIEW", SELLER_PENDING_PID)).isPresent();
        assertThat(findAnyTab(ADMIN_A, "SELLER_REVIEW", SELLER_ACTIVE_PID)).isEmpty();

        JsonNode product = findAnyTab(ADMIN_A, "PRODUCT_APPROVAL", PRODUCT_PENDING_PID).orElseThrow();
        assertThat(product.get("baseAt").asString()).isEqualTo(kst(tomorrowStart.minusHours(25)));
        assertThat(product.get("subtitle").asString()).isEqualTo("인박스셀러");

        JsonNode issue = findAnyTab(ADMIN_A, "RECONCILIATION_OPEN", String.valueOf(ISSUE_OPEN)).orElseThrow();
        assertThat(issue.get("title").asString()).isEqualTo("ITEM_STATE_DRIFT");
        assertThat(issue.get("targetKey").asString()).isEqualTo("RECONCILIATION");
    }

    @Test
    @DisplayName("T7 정산: 확정 기한 = 예정일 3일 전 하루 끝 · 지급 기한 = 예정일 하루 끝(당일 미경과) · 예정일 없음은 기한 없음으로 오늘 탭 맨 뒤")
    void settlementDeadlines() throws Exception {
        JsonNode todayInbox = fetch(ADMIN_A, "TODAY", null);
        JsonNode confirm = find(todayInbox, "SETTLEMENT_CONFIRM", String.valueOf(SETTLEMENT_CONFIRM_TODAY)).orElseThrow();
        assertThat(confirm.get("dueAt").asString()).isEqualTo(kst(today.atTime(23, 59, 59, 999_000_000)));
        assertThat(confirm.get("overdue").asBoolean()).isFalse();
        assertThat(confirm.get("title").asString()).isEqualTo("인박스셀러");

        JsonNode payout = find(todayInbox, "SETTLEMENT_PAYOUT", String.valueOf(SETTLEMENT_PAYOUT_TODAY)).orElseThrow();
        assertThat(payout.get("dueAt").asString()).isEqualTo(kst(today.atTime(23, 59, 59, 999_000_000)));
        assertThat(payout.get("overdue").asBoolean()).isFalse();

        JsonNode noPayDate = find(todayInbox, "SETTLEMENT_CONFIRM", String.valueOf(SETTLEMENT_NO_PAY_DATE)).orElseThrow();
        // 응답은 null 필드를 생략한다(spring.jackson default-property-inclusion: non_null)
        assertThat(noPayDate.has("dueAt")).isFalse();
        assertDueAtNullsLast(todayInbox);

        JsonNode upcomingInbox = fetch(ADMIN_A, "UPCOMING", "SETTLEMENT_CONFIRM");
        assertThat(find(upcomingInbox, "SETTLEMENT_CONFIRM", String.valueOf(SETTLEMENT_CONFIRM_UPCOMING))).isPresent();
        assertThat(find(todayInbox, "SETTLEMENT_CONFIRM", String.valueOf(SETTLEMENT_CONFIRM_UPCOMING))).isEmpty();
    }

    @Test
    @DisplayName("T8 type 필터: 셀러 유형 400 · 관리자 유형은 그 유형 항목만 + 건수는 관리자 9유형 전부")
    void typeFilter() throws Exception {
        mockMvc.perform(get(URL).param("type", "DELIVERY_READY").with(authHeaders.admin(ADMIN_A)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        JsonNode filtered = fetch(ADMIN_A, "TODAY", "INQUIRY_UNANSWERED");
        for (JsonNode item : filtered.get("items")) {
            assertThat(item.get("type").asString()).isEqualTo("INQUIRY_UNANSWERED");
        }
        List<String> countTypes = new ArrayList<>();
        filtered.get("counts").forEach(count -> countTypes.add(count.get("type").asString()));
        assertThat(countTypes).containsExactly("CLAIM_REQUESTED", "CLAIM_FOLLOWUP", "LONG_SHIPPING", "INQUIRY_UNANSWERED",
                "SELLER_REVIEW", "PRODUCT_APPROVAL", "SETTLEMENT_CONFIRM", "SETTLEMENT_PAYOUT", "RECONCILIATION_OPEN");
    }

    @Test
    @DisplayName("T9 보류 제외: 보류한 관리자에게서만 빠지고 건수도 1 줄어든다 · 다른 관리자는 그대로 본다")
    void snoozeExcludesOnlyForOwner() throws Exception {
        long before = countOf(fetch(ADMIN_A, "TODAY", "INQUIRY_UNANSWERED"), "INQUIRY_UNANSWERED");

        snooze(ADMIN_A, "INQUIRY_UNANSWERED", INQUIRY_TODAY_PID, OffsetDateTime.now(KST).plusDays(1), "고객 회신 대기");

        JsonNode ownerView = fetch(ADMIN_A, "TODAY", "INQUIRY_UNANSWERED");
        assertThat(find(ownerView, "INQUIRY_UNANSWERED", INQUIRY_TODAY_PID)).isEmpty();
        assertThat(countOf(ownerView, "INQUIRY_UNANSWERED")).isEqualTo(before - 1);

        JsonNode otherView = fetch(ADMIN_B, "TODAY", "INQUIRY_UNANSWERED");
        assertThat(find(otherView, "INQUIRY_UNANSWERED", INQUIRY_TODAY_PID)).isPresent();
        assertThat(countOf(otherView, "INQUIRY_UNANSWERED")).isEqualTo(before);
    }

    @Test
    @DisplayName("T10 보류 만료: until_at이 지난 보류는 무시돼 항목이 다시 보인다 · 해제(DELETE)도 다시 보이게 한다")
    void snoozeExpiryAndRelease() throws Exception {
        snooze(ADMIN_A, "INQUIRY_UNANSWERED", INQUIRY_TODAY_PID, OffsetDateTime.now(KST).plusDays(1), "고객 회신 대기");
        assertThat(find(fetch(ADMIN_A, "TODAY", "INQUIRY_UNANSWERED"), "INQUIRY_UNANSWERED", INQUIRY_TODAY_PID)).isEmpty();

        jdbc.update("UPDATE inbox_snooze SET until_at = ? WHERE owner_user_id = ?", LocalDateTime.now().minusMinutes(1), ADMIN_A);
        assertThat(find(fetch(ADMIN_A, "TODAY", "INQUIRY_UNANSWERED"), "INQUIRY_UNANSWERED", INQUIRY_TODAY_PID)).isPresent();

        snooze(ADMIN_A, "INQUIRY_UNANSWERED", INQUIRY_TODAY_PID, OffsetDateTime.now(KST).plusDays(2), "다시 보류");
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM inbox_snooze WHERE owner_user_id = ?", Integer.class, ADMIN_A);
        assertThat(rows).isEqualTo(1);
        assertThat(find(fetch(ADMIN_A, "TODAY", "INQUIRY_UNANSWERED"), "INQUIRY_UNANSWERED", INQUIRY_TODAY_PID)).isEmpty();

        mockMvc.perform(delete(SNOOZE_URL).param("type", "INQUIRY_UNANSWERED").param("ref", INQUIRY_TODAY_PID)
                        .with(authHeaders.admin(ADMIN_A)))
                .andExpect(status().isNoContent());
        assertThat(find(fetch(ADMIN_A, "TODAY", "INQUIRY_UNANSWERED"), "INQUIRY_UNANSWERED", INQUIRY_TODAY_PID)).isPresent();
    }

    @Test
    @DisplayName("T11 원천 해소: 보류 중에 답변되면 보류 해제·만료와 관계없이 항목이 사라진다")
    void resolvedSourceDisappearsRegardlessOfSnooze() throws Exception {
        snooze(ADMIN_A, "INQUIRY_UNANSWERED", INQUIRY_TODAY_PID, OffsetDateTime.now(KST).plusDays(1), "고객 회신 대기");
        jdbc.update("UPDATE inquiry SET answer_content = '답변', answered_at = NOW(6), answered_by = ? WHERE id = ?",
                ADMIN_A, INQUIRY_TODAY);

        jdbc.update("UPDATE inbox_snooze SET until_at = ? WHERE owner_user_id = ?", LocalDateTime.now().minusMinutes(1), ADMIN_A);
        assertThat(findAnyTab(ADMIN_A, "INQUIRY_UNANSWERED", INQUIRY_TODAY_PID)).isEmpty();
        mockMvc.perform(delete(SNOOZE_URL).param("type", "INQUIRY_UNANSWERED").param("ref", INQUIRY_TODAY_PID)
                        .with(authHeaders.admin(ADMIN_A)))
                .andExpect(status().isNoContent());
        assertThat(findAnyTab(ADMIN_A, "INQUIRY_UNANSWERED", INQUIRY_TODAY_PID)).isEmpty();
        assertThat(findAnyTab(ADMIN_B, "INQUIRY_UNANSWERED", INQUIRY_TODAY_PID)).isEmpty();
    }

    @Test
    @DisplayName("T15 원천 해소: 유효한 보류 행이 남아 있어도 답변된 문의는 보류하지 않은 관리자의 목록·건수에서도 빠진다")
    void resolvedSourceLeavesListAndCountWhileSnoozeRowRemains() throws Exception {
        long before = countOf(fetch(ADMIN_B, "TODAY", "INQUIRY_UNANSWERED"), "INQUIRY_UNANSWERED");
        snooze(ADMIN_A, "INQUIRY_UNANSWERED", INQUIRY_TODAY_PID, OffsetDateTime.now(KST).plusDays(1), "고객 회신 대기");

        jdbc.update("UPDATE inquiry SET answer_content = '답변', answered_at = NOW(6), answered_by = ? WHERE id = ?",
                ADMIN_A, INQUIRY_TODAY);

        Integer activeRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_snooze WHERE owner_user_id = ? AND item_ref = ? AND until_at > NOW(6)",
                Integer.class, ADMIN_A, INQUIRY_TODAY_PID);
        assertThat(activeRows).isEqualTo(1);
        JsonNode otherView = fetch(ADMIN_B, "TODAY", "INQUIRY_UNANSWERED");
        assertThat(find(otherView, "INQUIRY_UNANSWERED", INQUIRY_TODAY_PID)).isEmpty();
        assertThat(countOf(otherView, "INQUIRY_UNANSWERED")).isEqualTo(before - 1);
        assertThat(findAnyTab(ADMIN_A, "INQUIRY_UNANSWERED", INQUIRY_TODAY_PID)).isEmpty();
    }

    @Test
    @DisplayName("T12 보류 대상 검증: 대기 아님(답변됨)·없는 ref 404 · 과거 시각·DATETIME 범위 초과·빈 사유·허용 외 유형 400 · 해제 빈 ref 400")
    void snoozeValidation() throws Exception {
        OffsetDateTime tomorrow = OffsetDateTime.now(KST).plusDays(1);
        snoozeExpect(ADMIN_A, "INQUIRY_UNANSWERED", INQUIRY_ANSWERED_PID, tomorrow, "사유", 404);
        snoozeExpect(ADMIN_A, "CLAIM_REQUESTED", pid("clm_", "AIBNONE"), tomorrow, "사유", 404);
        snoozeExpect(ADMIN_A, "INQUIRY_UNANSWERED", INQUIRY_TODAY_PID, OffsetDateTime.now(KST).minusMinutes(1), "사유", 400);
        // KST로 바꾸면 10000-01-01 — DATETIME(6) 범위 밖
        snoozeExpect(ADMIN_A, "INQUIRY_UNANSWERED", INQUIRY_TODAY_PID,
                OffsetDateTime.of(9999, 12, 31, 23, 0, 0, 0, ZoneOffset.ofHours(-5)), "사유", 400);
        snoozeExpect(ADMIN_A, "INQUIRY_UNANSWERED", INQUIRY_TODAY_PID, tomorrow, " ", 400);
        snoozeExpect(ADMIN_A, "NOT_A_TYPE", INQUIRY_TODAY_PID, tomorrow, "사유", 400);
        mockMvc.perform(delete(SNOOZE_URL).param("type", "INQUIRY_UNANSWERED").param("ref", "").with(authHeaders.admin(ADMIN_A)))
                .andExpect(status().isBadRequest());

        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM inbox_snooze WHERE owner_user_id = ?", Integer.class, ADMIN_A);
        assertThat(rows).isZero();
    }

    @Test
    @DisplayName("T13 id 식별자 유형(정산 지급) 보류: 숫자 대조로 제외·다른 관리자는 그대로·해제 후 복귀 · 비정규 숫자 ref 404")
    void snoozeNumericIdType() throws Exception {
        String payoutRef = String.valueOf(SETTLEMENT_PAYOUT_TODAY);
        snoozeExpect(ADMIN_A, "SETTLEMENT_PAYOUT", "0" + payoutRef, OffsetDateTime.now(KST).plusDays(1), "사유", 404);

        snooze(ADMIN_A, "SETTLEMENT_PAYOUT", payoutRef, OffsetDateTime.now(KST).plusDays(1), "지급 계좌 확인 중");
        assertThat(find(fetch(ADMIN_A, "TODAY", "SETTLEMENT_PAYOUT"), "SETTLEMENT_PAYOUT", payoutRef)).isEmpty();
        assertThat(find(fetch(ADMIN_B, "TODAY", "SETTLEMENT_PAYOUT"), "SETTLEMENT_PAYOUT", payoutRef)).isPresent();

        mockMvc.perform(delete(SNOOZE_URL).param("type", "SETTLEMENT_PAYOUT").param("ref", payoutRef)
                        .with(authHeaders.admin(ADMIN_A)))
                .andExpect(status().isNoContent());
        assertThat(find(fetch(ADMIN_A, "TODAY", "SETTLEMENT_PAYOUT"), "SETTLEMENT_PAYOUT", payoutRef)).isPresent();
    }

    // ---------- helpers ----------

    private JsonNode fetch(long adminId, String tab, String type) throws Exception {
        MockHttpServletRequestBuilder request = get(URL).param("tab", tab);
        if (type != null) {
            request.param("type", type);
        }
        String body = mockMvc.perform(request.with(authHeaders.admin(adminId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }

    private Optional<JsonNode> findAnyTab(long adminId, String type, String ref) throws Exception {
        Optional<JsonNode> today = find(fetch(adminId, "TODAY", type), type, ref);
        return today.isPresent() ? today : find(fetch(adminId, "UPCOMING", type), type, ref);
    }

    private static Optional<JsonNode> find(JsonNode inbox, String type, String ref) {
        for (JsonNode item : inbox.get("items")) {
            if (item.get("type").asString().equals(type) && item.get("ref").asString().equals(ref)) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    /** 클레임 후속 항목을 클레임 publicId로 찾는다(ref = "publicId:단계 코드" — 단계와 무관하게 매칭). */
    private static Optional<JsonNode> findFollowup(JsonNode inbox, String claimPublicId) {
        for (JsonNode item : inbox.get("items")) {
            String ref = item.get("ref").asString();
            if (item.get("type").asString().equals("CLAIM_FOLLOWUP")
                    && (ref.equals(claimPublicId) || ref.startsWith(claimPublicId + ":"))) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    private static long countOf(JsonNode inbox, String type) {
        for (JsonNode count : inbox.get("counts")) {
            if (count.get("type").asString().equals(type)) {
                return count.get("count").asLong();
            }
        }
        throw new AssertionError("건수 없음: " + type);
    }

    private static void assertDueAtNullsLast(JsonNode inbox) {
        boolean seenNull = false;
        for (JsonNode item : inbox.get("items")) {
            boolean dueNull = !item.has("dueAt");
            assertThat(seenNull && !dueNull).as("기한 없음 뒤에 기한 있는 항목: %s", item).isFalse();
            seenNull = seenNull || dueNull;
        }
    }

    private void snooze(long adminId, String type, String ref, OffsetDateTime untilAt, String reason) throws Exception {
        snoozeExpect(adminId, type, ref, untilAt, reason, 204);
    }

    private void snoozeExpect(long adminId, String type, String ref, OffsetDateTime untilAt, String reason, int expected)
            throws Exception {
        String body = objectMapper.writeValueAsString(new SnoozeBody(type, ref, untilAt.toString(), reason));
        mockMvc.perform(put(SNOOZE_URL).contentType(MediaType.APPLICATION_JSON).content(body).with(authHeaders.admin(adminId)))
                .andExpect(status().is(expected));
    }

    private record SnoozeBody(String type, String ref, String untilAt, String reason) {
    }

    private static String kst(LocalDateTime value) {
        return value.atOffset(KST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    // ---------- seed·cleanup(? 바인딩·정적 SQL·SQL injection 위험 없음) ----------

    private void seedAll() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, name, created_at, updated_at) VALUES (?, ?, '인박스구매자', NOW(6), NOW(6))",
                        BUYER_ID, pid("usr_", "AIBBUY"));
                seedSeller(SELLER_ACTIVE, SELLER_ACTIVE_PID, "인박스셀러", "ACTIVE", LocalDateTime.now());
                seedSeller(SELLER_PENDING, SELLER_PENDING_PID, "심사셀러", "PENDING", tomorrowStart.minusHours(73));
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, is_soldout_manual, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, '승인대기상품', 'PENDING', 10000, 0, NOW(6), ?)",
                        PRODUCT_PENDING, PRODUCT_PENDING_PID, SELLER_ACTIVE, DUMMY_FK_ID, tomorrowStart.minusHours(25));
                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                        + "ordered_at, paid_at, created_at, updated_at) VALUES (?, ?, ?, 'ORDAIB948101', 'PAID', 90000, 0, 0, NOW(6), "
                        + "NOW(6), NOW(6), NOW(6))", ORDER_ID, pid("ord_", "AIBORD"), BUYER_ID);
                for (long itemId = ITEM_REQUESTED; itemId <= ITEM_APPROVED_IDLE; itemId++) {
                    seedOrderItem(itemId);
                }

                LocalDateTime followupToday = tomorrowStart.minusHours(49);
                seedClaim(CLAIM_REQUESTED, CLAIM_REQUESTED_PID, ITEM_REQUESTED, "CANCEL", "REQUESTED", tomorrowStart.minusHours(49),
                        null, null, null, null);
                seedClaim(CLAIM_INSPECT, CLAIM_INSPECT_PID, ITEM_INSPECT, "RETURN", "APPROVED", followupToday.minusDays(3),
                        followupToday.minusDays(2), followupToday, null, null);
                seedClaim(CLAIM_REFUND, CLAIM_REFUND_PID, ITEM_REFUND, "CANCEL", "APPROVED", followupToday.minusDays(1),
                        followupToday.minusHours(1), null, null, null);
                seedClaim(CLAIM_PICKUP, CLAIM_PICKUP_PID, ITEM_PICKUP, "RETURN", "APPROVED", followupToday.minusDays(2),
                        followupToday.minusDays(1), null, null, null);
                seedClaim(CLAIM_EXCHANGE, CLAIM_EXCHANGE_PID, ITEM_EXCHANGE, "EXCHANGE", "APPROVED", followupToday.minusDays(5),
                        followupToday.minusDays(4), followupToday.minusDays(3), followupToday.minusDays(2), "PASS");
                // 반품 APPROVED + 회수 송장 없음 → 후속 5단계 어디에도 해당하지 않는다(구매자 발송 대기)
                seedClaim(CLAIM_APPROVED_IDLE, CLAIM_APPROVED_IDLE_PID, ITEM_APPROVED_IDLE, "RETURN", "APPROVED",
                        followupToday.minusDays(1), followupToday.minusHours(5), null, null, null);
                jdbc.update("INSERT INTO refund (id, public_id, claim_id, payment_id, amount, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 10000, 'FAILED', NOW(6), NOW(6))", REFUND_FAILED, pid("rfn_", "AIBFAIL"), CLAIM_REFUND,
                        DUMMY_FK_ID);

                seedDelivery(DELIVERY_PICKUP, pid("dlv_", "AIBPCK"), ITEM_PICKUP, "RETURN", "SHIPPING", null, CLAIM_PICKUP,
                        followupToday.minusHours(2));
                seedDelivery(DELIVERY_EXCHANGE_RETURN, pid("dlv_", "AIBEXR"), ITEM_EXCHANGE, "RETURN", "DELIVERED", null,
                        CLAIM_EXCHANGE, followupToday.minusDays(4));
                // 교환 발송: 발송 8일 전(장기 배송 조건 충족)이지만 claim_id가 있어 장기 배송중에서는 빠지고 클레임 후속(교환 배송완료)으로만 잡힌다
                seedDelivery(DELIVERY_EXCHANGE_OUT, DELIVERY_EXCHANGE_OUT_PID, ITEM_EXCHANGE, "OUTBOUND", "SHIPPING",
                        tomorrowStart.minusDays(8), CLAIM_EXCHANGE, tomorrowStart.minusDays(8));
                seedDelivery(DELIVERY_SHIP_OVERDUE, DELIVERY_SHIP_OVERDUE_PID, ITEM_SHIP_OVERDUE, "OUTBOUND", "SHIPPING",
                        tomorrowStart.minusDays(8), null, tomorrowStart.minusDays(8));
                seedDelivery(DELIVERY_SHIP_UPCOMING, DELIVERY_SHIP_UPCOMING_PID, ITEM_SHIP_UPCOMING, "OUTBOUND", "SHIPPING",
                        tomorrowStart.minusDays(7).plusHours(1), null, tomorrowStart.minusDays(7));
                seedDelivery(DELIVERY_SHIP_RECENT, DELIVERY_SHIP_RECENT_PID, ITEM_SHIP_RECENT, "OUTBOUND", "SHIPPING",
                        LocalDateTime.now().minusDays(2), null, LocalDateTime.now().minusDays(2));

                seedInquiry(INQUIRY_TODAY, INQUIRY_TODAY_PID, tomorrowStart.minusHours(24).minusNanos(1_000_000), false);
                seedInquiry(INQUIRY_BOUNDARY, INQUIRY_BOUNDARY_PID, tomorrowStart.minusHours(24), false);
                seedInquiry(INQUIRY_ANSWERED, INQUIRY_ANSWERED_PID, tomorrowStart.minusHours(30), true);

                seedSettlement(SETTLEMENT_CONFIRM_TODAY, "PENDING", "2026-01", today.plusDays(3));
                seedSettlement(SETTLEMENT_CONFIRM_UPCOMING, "PENDING", "2026-02", today.plusDays(4));
                seedSettlement(SETTLEMENT_PAYOUT_TODAY, "CONFIRMED", "2026-03", today);
                seedSettlement(SETTLEMENT_NO_PAY_DATE, "PENDING", "2026-04", null);

                jdbc.update("INSERT INTO reconciliation_issue (id, issue_type, dedupe_key, order_id, status, detected_at, created_at) "
                        + "VALUES (?, 'ITEM_STATE_DRIFT', 'aib:948101', ?, 'OPEN', ?, NOW(6))", ISSUE_OPEN, ORDER_ID,
                        tomorrowStart.minusHours(25));
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedSeller(long id, String publicId, String companyName, String status, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, '대표', ?, ?, NOW(6))", id, publicId, companyName, status, createdAt);
    }

    private void seedOrderItem(long id) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, product_name, quantity, "
                + "unit_price, total_price, commission_rate, item_status, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, '인박스상품', 1, 10000, 10000, 1000, 'SHIPPING', NOW(6), NOW(6))",
                id, pid("oit_", "AIB" + id), ORDER_ID, PRODUCT_PENDING, DUMMY_FK_ID, SELLER_ACTIVE);
    }

    private void seedClaim(long id, String publicId, long orderItemId, String type, String status, LocalDateTime requestedAt,
            LocalDateTime processedAt, LocalDateTime pickedUpAt, LocalDateTime inspectedAt, String inspectionResult) {
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, reason_detail, status, requested_by, "
                + "requested_at, processed_at, picked_up_at, inspected_at, inspection_result, previous_order_item_status, version, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, 'CHANGE_MIND', '인박스사유', ?, ?, ?, ?, ?, ?, ?, 'PAID', 0, NOW(6), NOW(6))",
                id, publicId, orderItemId, type, status, BUYER_ID, requestedAt, processedAt, pickedUpAt, inspectedAt,
                inspectionResult);
    }

    private void seedDelivery(long id, String publicId, long orderItemId, String direction, String status, LocalDateTime shippedAt,
            Long claimId, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, direction, carrier, tracking_no, status, shipped_at, "
                + "delivered_at, claim_id, created_at, updated_at) VALUES (?, ?, ?, ?, 'CJ', ?, ?, ?, NULL, ?, ?, NOW(6))",
                id, publicId, orderItemId, direction, "AIB-TRK-" + id, status, shippedAt, claimId, createdAt);
    }

    private void seedInquiry(long id, String publicId, LocalDateTime createdAt, boolean answered) {
        jdbc.update("INSERT INTO inquiry (id, public_id, buyer_id, category, content, answer_content, answered_at, answered_by, "
                + "created_at, updated_at) VALUES (?, ?, ?, 'DELIVERY', '인박스 문의', ?, ?, ?, ?, ?)",
                id, publicId, BUYER_ID, answered ? "답변" : null, answered ? createdAt.plusHours(1) : null,
                answered ? ADMIN_A : null, createdAt, createdAt);
    }

    private void seedSettlement(long id, String status, String yearMonth, LocalDate scheduledPayDate) {
        LocalDate periodStart = LocalDate.parse(yearMonth + "-01");
        jdbc.update("INSERT INTO settlement (id, seller_id, bank_account_id, period_start, period_end, gross_amount, fee_amount, "
                + "refund_amount, net_amount, commission_rate, status, scheduled_pay_date, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, 20000, 2000, 0, 18000, 1000, ?, ?, NOW(6), NOW(6))",
                id, SELLER_ACTIVE, DUMMY_FK_ID, periodStart.atStartOfDay(),
                periodStart.plusMonths(1).atStartOfDay().minusSeconds(1), status, scheduledPayDate);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM inbox_snooze WHERE owner_user_id IN (?, ?)", ADMIN_A, ADMIN_B);
                jdbc.update("DELETE FROM reconciliation_issue WHERE id = ?", ISSUE_OPEN);
                jdbc.update("DELETE FROM settlement WHERE id BETWEEN ? AND ?", SETTLEMENT_CONFIRM_TODAY, SETTLEMENT_NO_PAY_DATE);
                jdbc.update("DELETE FROM inquiry WHERE id BETWEEN ? AND ?", INQUIRY_TODAY, INQUIRY_ANSWERED);
                jdbc.update("DELETE FROM delivery WHERE id BETWEEN ? AND ?", DELIVERY_PICKUP, DELIVERY_EXCHANGE_RETURN);
                jdbc.update("DELETE FROM refund WHERE id = ?", REFUND_FAILED);
                jdbc.update("DELETE FROM claim WHERE id BETWEEN ? AND ?", CLAIM_REQUESTED, CLAIM_APPROVED_IDLE);
                jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", ITEM_REQUESTED, ITEM_APPROVED_IDLE);
                jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_PENDING);
                jdbc.update("DELETE FROM seller WHERE id IN (?, ?)", SELLER_ACTIVE, SELLER_PENDING);
                jdbc.update("DELETE FROM `user` WHERE id = ?", BUYER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
