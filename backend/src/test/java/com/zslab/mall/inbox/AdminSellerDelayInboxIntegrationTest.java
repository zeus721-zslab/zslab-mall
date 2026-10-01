package com.zslab.mall.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.inbox.collector.SellerDelay;
import com.zslab.mall.inbox.collector.SellerDelayCounter;
import com.zslab.mall.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 관리자 인박스 셀러 지연(SELLER_DELAY · D-252 정의 확정표) 통합 테스트(실 MariaDB·HTTP 경유). 공유 DB에 다른 셀러의 지연이 있을 수 있어 시드 셀러
 * 행만 ref로 골라 단언한다.
 *
 * <p><b>시드</b>: 셀러 A(발송 대기 초과 2 · Q&amp;A 초과 1 · 숨김/답변 질문 · 발송된 품목) / 셀러 B(Q&amp;A 초과 1 · 기한 전 발송 대기 1) / 셀러 C(기한 전 건만)
 * / 셀러 D·E(쿼리 수 비교용 · 테스트 중간에 추가). 시드는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}·? 바인딩(SQL injection 없음).
 */
@AutoConfigureMockMvc
class AdminSellerDelayInboxIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/admin/inbox";
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final long ADMIN_X = 953101L;
    private static final long ADMIN_Y = 953102L;
    private static final long SELLER_USER_A = 953103L;
    private static final long BUYER_ID = 953100L;
    private static final long SELLER_A = 953101L;
    private static final long SELLER_B = 953102L;
    private static final long SELLER_C = 953103L;
    private static final long SELLER_D = 953104L;
    private static final long SELLER_E = 953105L;
    private static final long DUMMY_FK_ID = 953100L;

    private static final String SELLER_A_PID = pid("slr_", "SDLA");
    private static final String SELLER_B_PID = pid("slr_", "SDLB");
    private static final String SELLER_C_PID = pid("slr_", "SDLC");
    private static final String SELLER_D_PID = pid("slr_", "SDLD");
    private static final String SELLER_E_PID = pid("slr_", "SDLE");

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
    private SellerDelayCounter sellerDelayCounter;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private TransactionTemplate tx;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        cleanup();
        seed(() -> {
            jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                    BUYER_ID, pid("usr_", "SDLBUY"));
            jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                    SELLER_USER_A, pid("usr_", "SDLUA"));
            seedSeller(SELLER_A, SELLER_A_PID, "지연셀러A");
            seedSeller(SELLER_B, SELLER_B_PID, "지연셀러B");
            seedSeller(SELLER_C, SELLER_C_PID, "지연셀러C");
            jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                    + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'", SELLER_USER_A, SELLER_A);
            LocalDateTime overdue = now.minusHours(49);
            LocalDateTime notYet = now.minusHours(47);
            seedPaidItem(SELLER_A, 1, overdue.minusHours(1), "PAID");
            seedPaidItem(SELLER_A, 2, overdue, "PAID");
            seedPaidItem(SELLER_A, 3, overdue.minusHours(5), "SHIPPING");
            seedQuestion(SELLER_A, 1, overdue, "VISIBLE", false);
            seedQuestion(SELLER_A, 2, overdue, "HIDDEN", false);
            seedQuestion(SELLER_A, 3, overdue, "VISIBLE", true);
            seedQuestion(SELLER_B, 1, overdue.minusHours(2), "VISIBLE", false);
            seedPaidItem(SELLER_B, 1, notYet, "PAID");
            seedPaidItem(SELLER_C, 1, notYet, "PAID");
            seedQuestion(SELLER_C, 1, notYet, "VISIBLE", false);
        });
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("S1 경계: 기한과 같은 시각은 초과가 아니고 1µs 지나야 초과 · 두 유형 모두 InboxDeadlinePolicy 간격(48h)")
    void boundary() {
        LocalDateTime fixedNow = LocalDateTime.of(2026, 3, 10, 12, 0, 0);
        seed(() -> {
            seedSeller(SELLER_D, SELLER_D_PID, "경계셀러");
            seedPaidItem(SELLER_D, 1, fixedNow.minusHours(48), "PAID");
            seedQuestion(SELLER_D, 1, fixedNow.minusHours(48), "VISIBLE", false);
        });
        assertThat(sellerDelayCounter.countOf(SELLER_D, fixedNow).hasDelay()).isFalse();

        SellerDelay oneMicroLater = sellerDelayCounter.countOf(SELLER_D, fixedNow.plusNanos(1_000));
        assertThat(oneMicroLater.deliveryReadyCount()).isEqualTo(1);
        assertThat(oneMicroLater.questionUnansweredCount()).isEqualTo(1);
        assertThat(oneMicroLater.oldestDueAt()).isEqualTo(fixedNow);
    }

    @Test
    @DisplayName("S2 행: 초과 셀러만(A·B) · 기한 전 건만 있는 C 없음 · 제목 상호 · 부제 유형별 초과 건수(숨김·답변·발송됨 제외) · 기한 = 가장 오래된 초과 건 · 예정 탭 없음")
    void rows() throws Exception {
        JsonNode today = fetch(ADMIN_X, "TODAY", "SELLER_DELAY");
        JsonNode rowA = find(today, SELLER_A_PID).orElseThrow();
        assertThat(rowA.get("type").asString()).isEqualTo("SELLER_DELAY");
        assertThat(rowA.get("title").asString()).isEqualTo("지연셀러A");
        assertThat(rowA.get("subtitle").asString()).isEqualTo("발송 대기 2건 · Q&A 미답변 1건");
        assertThat(rowA.get("targetKey").asString()).isEqualTo("SELLER");
        assertThat(rowA.get("overdue").asBoolean()).isTrue();
        assertThat(OffsetDateTime.parse(rowA.get("dueAt").asString())).isEqualTo(now.minusHours(50).plusHours(48).atOffset(KST));

        JsonNode rowB = find(today, SELLER_B_PID).orElseThrow();
        assertThat(rowB.get("subtitle").asString()).isEqualTo("Q&A 미답변 1건");
        assertThat(find(today, SELLER_C_PID)).isEmpty();

        JsonNode upcoming = fetch(ADMIN_X, "UPCOMING", null);
        for (JsonNode item : upcoming.get("items")) {
            assertThat(item.get("type").asString()).isNotEqualTo("SELLER_DELAY");
        }
        assertThat(countOf(upcoming, "SELLER_DELAY")).isZero();
    }

    @Test
    @DisplayName("S3 보류: 셀러가 자기 품목을 보류해도 지연 건수는 그대로 · 관리자 보류는 보류한 관리자에게서만 행이 빠지고 건수 1 감소")
    void snooze() throws Exception {
        String sellerItemPid = pid("oit_", "SDL" + SELLER_A + "I1");
        snoozeExpect(put("/api/v1/seller/inbox/snoozes"), authHeaders.seller(SELLER_USER_A), "DELIVERY_READY", sellerItemPid, 204);
        assertThat(find(fetch(ADMIN_X, "TODAY", "SELLER_DELAY"), SELLER_A_PID).orElseThrow().get("subtitle").asString())
                .isEqualTo("발송 대기 2건 · Q&A 미답변 1건");

        long before = countOf(fetch(ADMIN_X, "TODAY", null), "SELLER_DELAY");
        snoozeExpect(put(URL + "/snoozes"), authHeaders.admin(ADMIN_X), "SELLER_DELAY", SELLER_A_PID, 204);

        JsonNode afterX = fetch(ADMIN_X, "TODAY", "SELLER_DELAY");
        assertThat(find(afterX, SELLER_A_PID)).isEmpty();
        assertThat(countOf(afterX, "SELLER_DELAY")).isEqualTo(before - 1);
        assertThat(find(fetch(ADMIN_Y, "TODAY", "SELLER_DELAY"), SELLER_A_PID)).isPresent();

        // 지연 없는 셀러(C)·없는 셀러는 대기 항목이 아니라 보류할 수 없다
        snoozeExpect(put(URL + "/snoozes"), authHeaders.admin(ADMIN_X), "SELLER_DELAY", SELLER_C_PID, 404);
        snoozeExpect(put(URL + "/snoozes"), authHeaders.admin(ADMIN_X), "SELLER_DELAY", pid("slr_", "SDLNONE"), 404);
    }

    @Test
    @DisplayName("S4 쿼리 수: 지연 셀러가 2곳 → 4곳으로 늘어도 셀러 지연 조회의 준비문 수가 같다")
    void statementCountIndependentOfSellers() throws Exception {
        long few = countStatements();
        seed(() -> {
            seedSeller(SELLER_D, SELLER_D_PID, "지연셀러D");
            seedSeller(SELLER_E, SELLER_E_PID, "지연셀러E");
            seedPaidItem(SELLER_D, 1, now.minusHours(60), "PAID");
            seedQuestion(SELLER_E, 1, now.minusHours(60), "VISIBLE", false);
        });
        long many = countStatements();

        assertThat(many).isEqualTo(few);
        JsonNode today = fetch(ADMIN_X, "TODAY", "SELLER_DELAY");
        assertThat(find(today, SELLER_D_PID)).isPresent();
        assertThat(find(today, SELLER_E_PID)).isPresent();
    }

    @Test
    @DisplayName("S5 삭제 제외: 삭제 상품의 질문·삭제 질문은 세지 않는다(@SQLRestriction) · 삭제 셀러는 지연이 있어도 행이 없다")
    void softDeletedExcluded() throws Exception {
        LocalDateTime overdue = now.minusHours(60);
        seed(() -> {
            seedSeller(SELLER_D, SELLER_D_PID, "삭제상품셀러");
            seedQuestion(SELLER_D, 1, overdue, "VISIBLE", false);
            jdbc.update("UPDATE product SET deleted_at = NOW(6) WHERE id = ?", SELLER_D);
            seedQuestion(SELLER_A, 4, overdue, "VISIBLE", false);
            jdbc.update("UPDATE product_question SET deleted_at = NOW(6) WHERE id = ?", SELLER_A * 10 + 4);
            seedSeller(SELLER_E, SELLER_E_PID, "삭제셀러");
            seedPaidItem(SELLER_E, 1, overdue, "PAID");
            jdbc.update("UPDATE seller SET deleted_at = NOW(6) WHERE id = ?", SELLER_E);
        });

        assertThat(sellerDelayCounter.countOf(SELLER_D, LocalDateTime.now()).hasDelay()).isFalse();
        JsonNode today = fetch(ADMIN_X, "TODAY", "SELLER_DELAY");
        assertThat(find(today, SELLER_A_PID).orElseThrow().get("subtitle").asString()).isEqualTo("발송 대기 2건 · Q&A 미답변 1건");
        assertThat(find(today, SELLER_D_PID)).isEmpty();
        assertThat(find(today, SELLER_E_PID)).isEmpty();
    }

    // ---------- helpers ----------

    private long countStatements() throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        fetch(ADMIN_X, "TODAY", "SELLER_DELAY");
        long count = statistics.getPrepareStatementCount();
        statistics.setStatisticsEnabled(false);
        return count;
    }

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

    private static Optional<JsonNode> find(JsonNode inbox, String ref) {
        for (JsonNode item : inbox.get("items")) {
            if (item.get("ref").asString().equals(ref)) {
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

    private void snoozeExpect(MockHttpServletRequestBuilder request, RequestPostProcessor auth, String type, String ref,
            int expected) throws Exception {
        String body = objectMapper.writeValueAsString(
                new SnoozeBody(type, ref, OffsetDateTime.now(KST).plusDays(1).toString(), "셀러 연락 대기"));
        mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body).with(auth))
                .andExpect(status().is(expected));
    }

    private record SnoozeBody(String type, String ref, String untilAt, String reason) {
    }

    // ---------- seed·cleanup(? 바인딩·정적 SQL·SQL injection 위험 없음) ----------

    private void seed(Runnable body) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                body.run();
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedSeller(long sellerId, String publicId, String companyName) {
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, '대표', 'ACTIVE', NOW(6), NOW(6))", sellerId, publicId, companyName);
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, is_soldout_manual, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, '지연상품', 'SALE', 10000, 0, NOW(6), NOW(6))",
                sellerId, pid("prd_", "SDL" + sellerId), sellerId, DUMMY_FK_ID);
    }

    /** 셀러 품목 1건 + 그 품목만의 주문(결제 시각 = paidAt). id = 셀러 id * 10 + 순번. */
    private void seedPaidItem(long sellerId, int index, LocalDateTime paidAt, String itemStatus) {
        long id = sellerId * 10 + index;
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                + "ordered_at, paid_at, created_at, updated_at) VALUES (?, ?, ?, ?, 'PAID', 10000, 0, 0, ?, ?, NOW(6), NOW(6))",
                id, pid("ord_", "SDL" + id), BUYER_ID, "SDL" + id, paidAt, paidAt);
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, product_name, quantity, "
                + "unit_price, total_price, commission_rate, item_status, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, '지연상품', 1, 10000, 10000, 1000, ?, NOW(6), NOW(6))",
                id, pid("oit_", "SDL" + sellerId + "I" + index), id, sellerId, DUMMY_FK_ID, sellerId, itemStatus);
    }

    private void seedQuestion(long sellerId, int index, LocalDateTime createdAt, String status, boolean answered) {
        long id = sellerId * 10 + index;
        jdbc.update("INSERT INTO product_question (id, public_id, product_id, buyer_id, content, status, hidden_reason, "
                + "answer_content, answered_at, answered_by, created_at, updated_at) VALUES (?, ?, ?, ?, '질문', ?, ?, ?, ?, ?, ?, ?)",
                id, pid("pqn_", "SDL" + id), sellerId, BUYER_ID, status, "HIDDEN".equals(status) ? "테스트 숨김" : null,
                answered ? "답변" : null, answered ? createdAt.plusHours(1) : null, answered ? SELLER_USER_A : null, createdAt,
                createdAt);
    }

    private void cleanup() {
        long minRowId = SELLER_A * 10;
        long maxRowId = SELLER_E * 10 + 9;
        seed(() -> {
            jdbc.update("DELETE FROM inbox_snooze WHERE owner_user_id IN (?, ?, ?)", ADMIN_X, ADMIN_Y, SELLER_USER_A);
            jdbc.update("DELETE FROM product_question WHERE id BETWEEN ? AND ?", minRowId, maxRowId);
            jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", minRowId, maxRowId);
            jdbc.update("DELETE FROM `order` WHERE id BETWEEN ? AND ?", minRowId, maxRowId);
            jdbc.update("DELETE FROM product WHERE id BETWEEN ? AND ?", SELLER_A, SELLER_E);
            jdbc.update("DELETE FROM seller_user WHERE user_id = ?", SELLER_USER_A);
            jdbc.update("DELETE FROM seller WHERE id BETWEEN ? AND ?", SELLER_A, SELLER_E);
            jdbc.update("DELETE FROM `user` WHERE id IN (?, ?)", BUYER_ID, SELLER_USER_A);
        });
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
