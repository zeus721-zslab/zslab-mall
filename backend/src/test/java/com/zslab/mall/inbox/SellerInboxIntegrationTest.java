package com.zslab.mall.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import java.util.Set;
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
 * 셀러 운영 인박스 통합 테스트(D-248·실 MariaDB·HTTP 경유). 셀러 인박스는 소속 셀러 범위라 시드 셀러(A·B·C)의 응답을 절대값으로 단언한다.
 *
 * <p><b>시드</b>: 셀러 A(구성원 A1 OWNER·A2 STAFF) / 셀러 B(구성원 B1) / 셀러 C(구성원 C1·발송 대기 201건 — 상한 검사용).
 * A·B는 같은 모양의 대기 항목을 하나씩 가진다(발송 대기 · 장기 배송 · 미답변 질문 · 재고 임박). A에는 제외 경계(숨김 질문 · 답변된 질문 ·
 * 재고 0·6·수동 품절)를 더한다. 시드는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}·? 바인딩(SQL injection 없음).
 */
@AutoConfigureMockMvc
class SellerInboxIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/seller/inbox";
    private static final String SNOOZE_URL = "/api/v1/seller/inbox/snoozes";
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final Set<String> SELLER_TYPES = Set.of("LONG_SHIPPING", "DELIVERY_READY", "QUESTION_UNANSWERED", "LOW_STOCK");
    private static final int CAP = 200;
    private static final int SELLER_C_ITEM_COUNT = CAP + 1;

    private static final long USER_A1 = 947101L;
    private static final long USER_A2 = 947102L;
    private static final long USER_B1 = 947103L;
    private static final long USER_C1 = 947104L;
    private static final long BUYER_ID = 947100L;
    private static final long SELLER_A = 947101L;
    private static final long SELLER_B = 947102L;
    private static final long SELLER_C = 947103L;
    private static final long PRODUCT_A = 947101L;
    private static final long PRODUCT_B = 947102L;
    private static final long PRODUCT_C = 947103L;
    private static final long ORDER_A = 947101L;
    private static final long ORDER_B = 947102L;
    private static final long ORDER_C = 947103L;
    private static final long ORDER_C_LATE = 947104L;
    private static final long CLAIM_A_REQUESTED = 947101L;
    private static final long ITEM_A_PAID = 947101L;
    private static final long ITEM_A_SHIPPING = 947102L;
    private static final long ITEM_B_PAID = 947103L;
    private static final long ITEM_B_SHIPPING = 947104L;
    private static final long ITEM_C_BASE = 947200L;
    private static final long DELIVERY_A = 947101L;
    private static final long DELIVERY_B = 947102L;
    private static final long QUESTION_A = 947101L;
    private static final long QUESTION_A_HIDDEN = 947102L;
    private static final long QUESTION_A_ANSWERED = 947103L;
    private static final long QUESTION_B = 947104L;
    private static final long VARIANT_A_LOW = 947101L;
    private static final long VARIANT_A_EMPTY = 947102L;
    private static final long VARIANT_A_ENOUGH = 947103L;
    private static final long VARIANT_A_MANUAL = 947104L;
    private static final long VARIANT_B_LOW = 947105L;
    private static final long DUMMY_FK_ID = 947100L;

    private static final String ITEM_A_PAID_PID = pid("oit_", "SIBAPAID");
    private static final String ITEM_B_PAID_PID = pid("oit_", "SIBBPAID");
    private static final String ITEM_C_LATEST_PID = pid("oit_", "SIBCLATE");
    private static final String CLAIM_A_REQUESTED_PID = pid("clm_", "SIBAREQ");
    private static final String DELIVERY_A_PID = pid("dlv_", "SIBA");
    private static final String DELIVERY_B_PID = pid("dlv_", "SIBB");
    private static final String QUESTION_A_PID = pid("pqn_", "SIBA");
    private static final String QUESTION_A_HIDDEN_PID = pid("pqn_", "SIBAHID");
    private static final String QUESTION_A_ANSWERED_PID = pid("pqn_", "SIBAANS");
    private static final String QUESTION_B_PID = pid("pqn_", "SIBB");
    private static final String VARIANT_A_LOW_PID = pid("var_", "SIBALOW");
    private static final String VARIANT_A_EMPTY_PID = pid("var_", "SIBAEMP");
    private static final String VARIANT_A_ENOUGH_PID = pid("var_", "SIBAENO");
    private static final String VARIANT_A_MANUAL_PID = pid("var_", "SIBAMAN");
    private static final String VARIANT_B_LOW_PID = pid("var_", "SIBBLOW");

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
    private LocalDateTime tomorrowStart;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        tomorrowStart = LocalDate.now().plusDays(1).atStartOfDay();
        cleanup();
        seedAll();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("T1 인가: 비인증 401 · BUYER 401 · SELLER 200")
    void authorization() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).with(authHeaders.buyer(BUYER_ID))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).with(authHeaders.seller(USER_A1))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T2 소유 범위: 셀러 A는 자기 항목 4종만 보고 같은 모양의 셀러 B 항목은 어느 탭에도 없다 · 건수는 셀러 4유형만")
    void otherSellerItemsHidden() throws Exception {
        List<JsonNode> itemsA = itemsBothTabs(USER_A1);
        assertThat(refsOf(itemsA)).contains(ITEM_A_PAID_PID, DELIVERY_A_PID, QUESTION_A_PID, VARIANT_A_LOW_PID)
                .doesNotContain(ITEM_B_PAID_PID, DELIVERY_B_PID, QUESTION_B_PID, VARIANT_B_LOW_PID);

        List<JsonNode> itemsB = itemsBothTabs(USER_B1);
        assertThat(refsOf(itemsB)).containsExactlyInAnyOrder(ITEM_B_PAID_PID, DELIVERY_B_PID, QUESTION_B_PID, VARIANT_B_LOW_PID);

        List<String> countTypes = new ArrayList<>();
        fetch(USER_A1, "TODAY", null).get("counts").forEach(count -> countTypes.add(count.get("type").asString()));
        assertThat(countTypes).containsExactlyInAnyOrderElementsOf(SELLER_TYPES);
    }

    @Test
    @DisplayName("T3 Q&A 미답변: 공개·미답변만(숨김·답변 완료 제외) · 제목 질문 본문 · 부제 상품명 · 기한 +48h")
    void questionUnanswered() throws Exception {
        List<JsonNode> itemsA = itemsBothTabs(USER_A1);
        assertThat(refsOf(itemsA)).doesNotContain(QUESTION_A_HIDDEN_PID, QUESTION_A_ANSWERED_PID);

        JsonNode question = itemsA.stream().filter(item -> item.get("ref").asString().equals(QUESTION_A_PID)).findFirst()
                .orElseThrow();
        assertThat(question.get("title").asString()).isEqualTo("셀러A 질문");
        assertThat(question.get("subtitle").asString()).isEqualTo("셀러A상품");
        assertThat(question.get("targetKey").asString()).isEqualTo("PRODUCT_QUESTION");
        LocalDateTime createdAt = tomorrowStart.minusHours(49);
        assertThat(question.get("dueAt").asString())
                .isEqualTo(createdAt.plusHours(48).atOffset(KST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    }

    @Test
    @DisplayName("T4 재고 임박: 가용 1~5·수동 품절 제외 · 기한 없음이라 오늘 탭에만 · 예정 탭 건수 0")
    void lowStockTodayOnly() throws Exception {
        JsonNode todayInbox = fetch(USER_A1, "TODAY", "LOW_STOCK");
        JsonNode low = find(todayInbox, VARIANT_A_LOW_PID).orElseThrow();
        // 응답은 null 필드를 생략한다(spring.jackson default-property-inclusion: non_null)
        assertThat(low.has("dueAt")).isFalse();
        assertThat(low.has("baseAt")).isFalse();
        assertThat(low.get("overdue").asBoolean()).isFalse();
        assertThat(refsOf(itemsBothTabs(USER_A1))).doesNotContain(VARIANT_A_EMPTY_PID, VARIANT_A_ENOUGH_PID, VARIANT_A_MANUAL_PID);

        JsonNode upcomingInbox = fetch(USER_A1, "UPCOMING", null);
        assertThat(find(upcomingInbox, VARIANT_A_LOW_PID)).isEmpty();
        assertThat(countOf(upcomingInbox, "LOW_STOCK")).isZero();
    }

    @Test
    @DisplayName("T5 상한: 발송 대기 201건 → 항목 200건 · 기한이 가장 늦은 1건이 잘림 · truncated · 유형 건수 201 · 기한 오름차순")
    void capAndTruncated() throws Exception {
        JsonNode todayInbox = fetch(USER_C1, "TODAY", null);

        assertThat(todayInbox.get("items")).hasSize(CAP);
        assertThat(todayInbox.get("truncated").asBoolean()).isTrue();
        assertThat(countOf(todayInbox, "DELIVERY_READY")).isEqualTo(SELLER_C_ITEM_COUNT);
        assertThat(find(todayInbox, ITEM_C_LATEST_PID)).isEmpty();
        OffsetDateTime previous = OffsetDateTime.MIN;
        for (JsonNode item : todayInbox.get("items")) {
            OffsetDateTime dueAt = OffsetDateTime.parse(item.get("dueAt").asString());
            assertThat(dueAt).isAfterOrEqualTo(previous);
            previous = dueAt;
        }

        JsonNode sellerA = fetch(USER_A1, "TODAY", null);
        assertThat(sellerA.get("truncated").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("T6 관리자 전용 유형 필터 400")
    void adminTypeFilterRejected() throws Exception {
        mockMvc.perform(get(URL).param("type", "CLAIM_REQUESTED").with(authHeaders.seller(USER_A1)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("T7 보류 소유: 타 셀러 항목·관리자 유형(실재하는 접수 클레임) 보류 404 · 자기 항목 보류는 보류한 구성원에게서만 빠진다")
    void snoozeOwnership() throws Exception {
        OffsetDateTime tomorrow = OffsetDateTime.now(KST).plusDays(1);
        snoozeExpect(USER_A1, "DELIVERY_READY", ITEM_B_PAID_PID, tomorrow, 404);
        snoozeExpect(USER_A1, "QUESTION_UNANSWERED", QUESTION_B_PID, tomorrow, 404);
        snoozeExpect(USER_A1, "CLAIM_REQUESTED", CLAIM_A_REQUESTED_PID, tomorrow, 404);
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM inbox_snooze WHERE owner_user_id = ?", Integer.class, USER_A1);
        assertThat(rows).isZero();

        snoozeExpect(USER_A1, "DELIVERY_READY", ITEM_A_PAID_PID, tomorrow, 204);
        assertThat(refsOf(itemsBothTabs(USER_A1))).doesNotContain(ITEM_A_PAID_PID);
        assertThat(refsOf(itemsBothTabs(USER_A2))).contains(ITEM_A_PAID_PID);
    }

    // ---------- helpers ----------

    private JsonNode fetch(long userId, String tab, String type) throws Exception {
        MockHttpServletRequestBuilder request = get(URL).param("tab", tab);
        if (type != null) {
            request.param("type", type);
        }
        String body = mockMvc.perform(request.with(authHeaders.seller(userId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }

    private List<JsonNode> itemsBothTabs(long userId) throws Exception {
        List<JsonNode> items = new ArrayList<>();
        fetch(userId, "TODAY", null).get("items").forEach(items::add);
        fetch(userId, "UPCOMING", null).get("items").forEach(items::add);
        return items;
    }

    private static List<String> refsOf(List<JsonNode> items) {
        return items.stream().map(item -> item.get("ref").asString()).toList();
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

    private void snoozeExpect(long userId, String type, String ref, OffsetDateTime untilAt, int expected) throws Exception {
        String body = objectMapper.writeValueAsString(new SnoozeBody(type, ref, untilAt.toString(), "택배사 확인 중"));
        mockMvc.perform(put(SNOOZE_URL).contentType(MediaType.APPLICATION_JSON).content(body).with(authHeaders.seller(userId)))
                .andExpect(status().is(expected));
    }

    private record SnoozeBody(String type, String ref, String untilAt, String reason) {
    }

    // ---------- seed·cleanup(? 바인딩·정적 SQL·SQL injection 위험 없음) ----------

    private void seedAll() {
        LocalDateTime overdueBase = tomorrowStart.minusHours(49);
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, name, created_at, updated_at) VALUES (?, ?, '인박스구매자', NOW(6), NOW(6))",
                        BUYER_ID, pid("usr_", "SIBBUY"));
                seedSeller(SELLER_A, "SIBSLA", "셀러A");
                seedSeller(SELLER_B, "SIBSLB", "셀러B");
                seedSeller(SELLER_C, "SIBSLC", "셀러C");
                seedMember(USER_A1, SELLER_A, "SIBUA1", "SELLER_OWNER");
                seedMember(USER_A2, SELLER_A, "SIBUA2", "SELLER_STAFF");
                seedMember(USER_B1, SELLER_B, "SIBUB1", "SELLER_OWNER");
                seedMember(USER_C1, SELLER_C, "SIBUC1", "SELLER_OWNER");
                seedProduct(PRODUCT_A, "SIBPA", SELLER_A, "셀러A상품");
                seedProduct(PRODUCT_B, "SIBPB", SELLER_B, "셀러B상품");
                seedProduct(PRODUCT_C, "SIBPC", SELLER_C, "셀러C상품");

                seedOrder(ORDER_A, "SIBORA", overdueBase);
                seedOrder(ORDER_B, "SIBORB", overdueBase);
                seedOrder(ORDER_C, "SIBORC", overdueBase);
                // 기한 = 내일 00:00 − 30분 → 오늘 탭 안에서 가장 늦다
                seedOrder(ORDER_C_LATE, "SIBORCL", overdueBase.plusMinutes(30));
                seedOrderItem(ITEM_A_PAID, ITEM_A_PAID_PID, ORDER_A, SELLER_A, PRODUCT_A, "PAID");
                seedOrderItem(ITEM_A_SHIPPING, pid("oit_", "SIBASHIP"), ORDER_A, SELLER_A, PRODUCT_A, "SHIPPING");
                seedOrderItem(ITEM_B_PAID, ITEM_B_PAID_PID, ORDER_B, SELLER_B, PRODUCT_B, "PAID");
                seedOrderItem(ITEM_B_SHIPPING, pid("oit_", "SIBBSHIP"), ORDER_B, SELLER_B, PRODUCT_B, "SHIPPING");
                // 200건은 같은 결제 시각, 마지막 1건만 30분 늦게 결제 → 기한이 가장 늦어 상한에서 잘려야 한다
                for (int index = 0; index < CAP; index++) {
                    seedOrderItem(ITEM_C_BASE + index, pid("oit_", String.format("SIBC%03d", index)), ORDER_C, SELLER_C, PRODUCT_C,
                            "PAID");
                }
                seedOrderItem(ITEM_C_BASE + CAP, ITEM_C_LATEST_PID, ORDER_C_LATE, SELLER_C, PRODUCT_C, "PAID");
                // 관리자 유형(클레임 접수) 보류 거부 검증용 — 셀러 인박스 수집 대상이 아니다
                jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, reason_detail, status, requested_by, "
                        + "requested_at, previous_order_item_status, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CANCEL', 'CHANGE_MIND', '사유', 'REQUESTED', ?, NOW(6), 'PAID', 0, NOW(6), NOW(6))",
                        CLAIM_A_REQUESTED, CLAIM_A_REQUESTED_PID, ITEM_A_PAID, BUYER_ID);

                seedDelivery(DELIVERY_A, DELIVERY_A_PID, ITEM_A_SHIPPING, tomorrowStart.minusDays(8));
                seedDelivery(DELIVERY_B, DELIVERY_B_PID, ITEM_B_SHIPPING, tomorrowStart.minusDays(8));

                seedQuestion(QUESTION_A, QUESTION_A_PID, PRODUCT_A, "셀러A 질문", "VISIBLE", false, overdueBase);
                seedQuestion(QUESTION_A_HIDDEN, QUESTION_A_HIDDEN_PID, PRODUCT_A, "숨긴 질문", "HIDDEN", false, overdueBase);
                seedQuestion(QUESTION_A_ANSWERED, QUESTION_A_ANSWERED_PID, PRODUCT_A, "답변된 질문", "VISIBLE", true, overdueBase);
                seedQuestion(QUESTION_B, QUESTION_B_PID, PRODUCT_B, "셀러B 질문", "VISIBLE", false, overdueBase);

                seedVariantWithInventory(VARIANT_A_LOW, VARIANT_A_LOW_PID, PRODUCT_A, false, 3);
                seedVariantWithInventory(VARIANT_A_EMPTY, VARIANT_A_EMPTY_PID, PRODUCT_A, false, 0);
                seedVariantWithInventory(VARIANT_A_ENOUGH, VARIANT_A_ENOUGH_PID, PRODUCT_A, false, 6);
                seedVariantWithInventory(VARIANT_A_MANUAL, VARIANT_A_MANUAL_PID, PRODUCT_A, true, 2);
                seedVariantWithInventory(VARIANT_B_LOW, VARIANT_B_LOW_PID, PRODUCT_B, false, 2);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedSeller(long id, String tag, String companyName) {
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, '대표', 'ACTIVE', NOW(6), NOW(6))", id, pid("slr_", tag), companyName);
    }

    private void seedMember(long userId, long sellerId, String tag, String roleCode) {
        jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                userId, pid("usr_", tag));
        jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = ?", userId, sellerId, roleCode);
    }

    private void seedProduct(long id, String tag, long sellerId, String name) {
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, is_soldout_manual, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'SALE', 10000, 0, NOW(6), NOW(6))",
                id, pid("prd_", tag), sellerId, DUMMY_FK_ID, name);
    }

    private void seedOrder(long id, String tag, LocalDateTime paidAt) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                + "ordered_at, paid_at, created_at, updated_at) VALUES (?, ?, ?, ?, 'PAID', 10000, 0, 0, ?, ?, NOW(6), NOW(6))",
                id, pid("ord_", tag), BUYER_ID, "ORD" + tag, paidAt, paidAt);
    }

    private void seedOrderItem(long id, String publicId, long orderId, long sellerId, long productId, String itemStatus) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, product_name, quantity, "
                + "unit_price, total_price, commission_rate, item_status, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, '인박스상품', 1, 10000, 10000, 1000, ?, NOW(6), NOW(6))",
                id, publicId, orderId, productId, DUMMY_FK_ID, sellerId, itemStatus);
    }

    private void seedDelivery(long id, String publicId, long orderItemId, LocalDateTime shippedAt) {
        jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, direction, carrier, tracking_no, status, shipped_at, "
                + "delivered_at, claim_id, created_at, updated_at) VALUES (?, ?, ?, 'OUTBOUND', 'CJ', ?, 'SHIPPING', ?, NULL, NULL, "
                + "NOW(6), NOW(6))", id, publicId, orderItemId, "SIB-TRK-" + id, shippedAt);
    }

    private void seedQuestion(long id, String publicId, long productId, String content, String status, boolean answered,
            LocalDateTime createdAt) {
        jdbc.update("INSERT INTO product_question (id, public_id, product_id, buyer_id, content, status, hidden_reason, "
                + "answer_content, answered_at, answered_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, publicId, productId, BUYER_ID, content, status, "HIDDEN".equals(status) ? "테스트 숨김" : null,
                answered ? "답변" : null, answered ? createdAt.plusHours(1) : null, answered ? USER_A1 : null, createdAt, createdAt);
    }

    private void seedVariantWithInventory(long id, String publicId, long productId, boolean soldoutManual, int available) {
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, 0, 'SALE', ?, 1, ?, NOW(6), NOW(6))",
                id, publicId, productId, "SIB-" + id, soldoutManual, DUMMY_FK_ID);
        jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, "
                + "created_at, updated_at) VALUES (?, ?, ?, 0, ?, NOW(6), NOW(6))", id, id, available, available);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM inbox_snooze WHERE owner_user_id BETWEEN ? AND ?", USER_A1, USER_C1);
                jdbc.update("DELETE FROM inventory WHERE id BETWEEN ? AND ?", VARIANT_A_LOW, VARIANT_B_LOW);
                jdbc.update("DELETE FROM product_variant WHERE id BETWEEN ? AND ?", VARIANT_A_LOW, VARIANT_B_LOW);
                jdbc.update("DELETE FROM product_question WHERE id BETWEEN ? AND ?", QUESTION_A, QUESTION_B);
                jdbc.update("DELETE FROM delivery WHERE id IN (?, ?)", DELIVERY_A, DELIVERY_B);
                jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", ITEM_A_PAID, ITEM_B_SHIPPING);
                jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", ITEM_C_BASE, ITEM_C_BASE + SELLER_C_ITEM_COUNT);
                jdbc.update("DELETE FROM claim WHERE id = ?", CLAIM_A_REQUESTED);
                jdbc.update("DELETE FROM `order` WHERE id BETWEEN ? AND ?", ORDER_A, ORDER_C_LATE);
                jdbc.update("DELETE FROM product WHERE id BETWEEN ? AND ?", PRODUCT_A, PRODUCT_C);
                jdbc.update("DELETE FROM seller_user WHERE user_id BETWEEN ? AND ?", USER_A1, USER_C1);
                jdbc.update("DELETE FROM seller WHERE id BETWEEN ? AND ?", SELLER_A, SELLER_C);
                jdbc.update("DELETE FROM `user` WHERE id BETWEEN ? AND ?", BUYER_ID, USER_C1);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
