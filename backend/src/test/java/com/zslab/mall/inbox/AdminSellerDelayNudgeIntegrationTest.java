package com.zslab.mall.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.notification.adapter.SmsSender;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 셀러 지연 독촉(D-252) 통합 테스트(실 MariaDB·HTTP 경유·SmsSender만 목). 독촉은 요청 스레드에서 동기 발송하므로 응답 직후 notification_log를
 * 단언한다(클래스 {@code @Transactional} 없음 — 셀러별 기록이 실제로 남는지 본다).
 *
 * <p><b>시드</b>(모두 발송 대기 초과 1 + Q&amp;A 초과 1, 결제·작성 49시간 전): 셀러 1 연락처 있음 / 셀러 2 연락처 없음·활성 OWNER 휴대폰 /
 * 셀러 3 연락처·OWNER 휴대폰 없음 / 셀러 4 연락처 있음(발송 실패 주입용) / 셀러 5 기한 전 건만(지연 없음). 시드는 ? 바인딩·정적 SQL(SQL injection 없음).
 */
@AutoConfigureMockMvc
class AdminSellerDelayNudgeIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/admin/inbox/seller-delays/nudge";
    private static final long ADMIN = 954100L;
    private static final long BUYER_ID = 954100L;
    private static final long OWNER_2 = 954102L;
    private static final long OWNER_3 = 954103L;
    private static final long SELLER_USER_1 = 954101L;
    private static final long SELLER_1 = 954101L;
    private static final long SELLER_2 = 954102L;
    private static final long SELLER_3 = 954103L;
    private static final long SELLER_4 = 954104L;
    private static final long SELLER_5 = 954105L;
    private static final long DUMMY_FK_ID = 954100L;
    private static final String PHONE_1 = "010-9541-0001";
    private static final String PHONE_OWNER_2 = "010-9541-0002";
    private static final String PHONE_4 = "010-9541-0004";
    private static final String EXPECTED_BODY =
            "[zslab-mall] 처리 기한이 지난 건이 있습니다. 발송 대기 1건, 상품 Q&A 미답변 1건. 셀러센터 인박스에서 확인해 주세요.";

    @MockitoBean
    private SmsSender smsSender;

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
        doNothing().when(smsSender).send(any(), any());
        cleanup();
        LocalDateTime overdue = LocalDateTime.now().minusHours(49);
        seed(() -> {
            jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                    BUYER_ID, pid("usr_", "SDNBUY"));
            seedSeller(SELLER_1, PHONE_1);
            seedSeller(SELLER_2, null);
            seedSeller(SELLER_3, null);
            seedSeller(SELLER_4, PHONE_4);
            seedSeller(SELLER_5, PHONE_1);
            seedMember(SELLER_USER_1, SELLER_1, null);
            seedMember(OWNER_2, SELLER_2, PHONE_OWNER_2);
            seedMember(OWNER_3, SELLER_3, null);
            for (long sellerId = SELLER_1; sellerId <= SELLER_4; sellerId++) {
                seedPaidItem(sellerId, overdue);
                seedQuestion(sellerId, overdue);
            }
            seedPaidItem(SELLER_5, LocalDateTime.now().minusHours(47));
        });
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("N1 셀러별 결과: 연락처 SENT · OWNER fallback SENT · 수신처 없음 NO_RECIPIENT(로그 없음) · 발송 실패 FAILED · 지연 없음·없는 셀러 NO_DELAY · 건수 집계 · 입력 순서")
    void resultsPerSeller() throws Exception {
        doThrow(new IllegalStateException("SMS 게이트웨이 장애")).when(smsSender).send(eq(PHONE_4), anyString());

        nudge(sellerPid(SELLER_1), sellerPid(SELLER_2), sellerPid(SELLER_3), sellerPid(SELLER_4), sellerPid(SELLER_5),
                pid("slr_", "SDNNONE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].sellerPublicId").value(sellerPid(SELLER_1)))
                .andExpect(jsonPath("$.results[0].result").value("SENT"))
                .andExpect(jsonPath("$.results[1].result").value("SENT"))
                .andExpect(jsonPath("$.results[2].result").value("NO_RECIPIENT"))
                .andExpect(jsonPath("$.results[3].result").value("FAILED"))
                .andExpect(jsonPath("$.results[4].result").value("NO_DELAY"))
                .andExpect(jsonPath("$.results[5].result").value("NO_DELAY"))
                .andExpect(jsonPath("$.sentCount").value(2))
                .andExpect(jsonPath("$.failedCount").value(1))
                .andExpect(jsonPath("$.noRecipientCount").value(1))
                .andExpect(jsonPath("$.cooldownCount").value(0))
                .andExpect(jsonPath("$.noDelayCount").value(2));

        verify(smsSender).send(PHONE_1, EXPECTED_BODY);
        verify(smsSender).send(PHONE_OWNER_2, EXPECTED_BODY);

        Map<String, Object> log1 = logOf(SELLER_1);
        assertThat(log1.get("status")).isEqualTo("SENT");
        assertThat(log1.get("channel")).isEqualTo("SMS");
        assertThat(log1.get("template_code")).isEqualTo("TPL_SELLER_DELAY_NUDGE");
        assertThat(log1.get("recipient_user_id")).isNull();
        assertThat(log1.get("content")).isEqualTo(EXPECTED_BODY);
        assertThat(recipientUserIdOf(SELLER_2)).isEqualTo(OWNER_2);
        assertThat(logCount(SELLER_3)).isZero();
        Map<String, Object> log4 = logOf(SELLER_4);
        assertThat(log4.get("status")).isEqualTo("FAILED");
        assertThat(failedReasonOf(SELLER_4)).contains("SMS 게이트웨이 장애");
        assertThat(logCount(SELLER_5)).isZero();
    }

    @Test
    @DisplayName("N2 쿨다운: 24시간 안 SENT가 있으면 COOLDOWN(발송 없음) · FAILED는 쿨다운이 아님 · SENT가 24시간을 넘기면 다시 SENT")
    void cooldown() throws Exception {
        nudge(sellerPid(SELLER_1)).andExpect(jsonPath("$.results[0].result").value("SENT"));
        nudge(sellerPid(SELLER_1)).andExpect(jsonPath("$.results[0].result").value("COOLDOWN"))
                .andExpect(jsonPath("$.cooldownCount").value(1));
        verify(smsSender, times(1)).send(eq(PHONE_1), anyString());
        assertThat(logCount(SELLER_1)).isEqualTo(1);

        doThrow(new IllegalStateException("장애")).when(smsSender).send(eq(PHONE_4), anyString());
        nudge(sellerPid(SELLER_4)).andExpect(jsonPath("$.results[0].result").value("FAILED"));
        doNothing().when(smsSender).send(eq(PHONE_4), anyString());
        nudge(sellerPid(SELLER_4)).andExpect(jsonPath("$.results[0].result").value("SENT"));

        jdbc.update("UPDATE notification_log SET sent_at = ? WHERE target_type = 'SELLER' AND target_id = ?",
                LocalDateTime.now().minusHours(24).minusMinutes(1), SELLER_1);
        nudge(sellerPid(SELLER_1)).andExpect(jsonPath("$.results[0].result").value("SENT"));
        assertThat(logCount(SELLER_1)).isEqualTo(2);
    }

    @Test
    @DisplayName("N3 검증: 20건 200(상한 포함) · 21건 400 · 빈 목록 400 · 발송 없음")
    void sizeLimits() throws Exception {
        nudge(unknownIds(20)).andExpect(status().isOk()).andExpect(jsonPath("$.noDelayCount").value(20));
        nudge(unknownIds(21)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        nudge().andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verify(smsSender, never()).send(any(), any());
    }

    @Test
    @DisplayName("N4 인증: 관리자 쿠키 200(엔드포인트 존재) → 쿠키 없음 401 · 셀러 쿠키 401 · 거부된 요청은 발송·기록 없음")
    void authorization() throws Exception {
        nudge(sellerPid(SELLER_1)).andExpect(status().isOk()).andExpect(jsonPath("$.sentCount").value(1));

        String body = body(sellerPid(SELLER_4));
        mockMvc.perform(post(URL).with(authHeaders.csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL).with(authHeaders.seller(SELLER_USER_1)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        assertThat(logCount(SELLER_4)).isZero();
        verify(smsSender, never()).send(eq(PHONE_4), anyString());
    }

    @Test
    @DisplayName("N5 패널 정보: 200 유형별 초과 건수 · 독촉 전 마지막 독촉 시각 없음 → 독촉 후 SENT 시각(+09:00) · 지연 없는 셀러 200 0건 · 없는 셀러 404 · 무쿠키·셀러 쿠키 401")
    void panelInfo() throws Exception {
        String url = "/api/v1/admin/inbox/seller-delays/";
        mockMvc.perform(get(url + sellerPid(SELLER_1)).with(authHeaders.admin(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerPublicId").value(sellerPid(SELLER_1)))
                .andExpect(jsonPath("$.companyName").value("독촉셀러" + SELLER_1))
                .andExpect(jsonPath("$.deliveryReadyOverdueCount").value(1))
                .andExpect(jsonPath("$.questionUnansweredOverdueCount").value(1))
                .andExpect(jsonPath("$.lastNudgedAt").doesNotExist());

        nudge(sellerPid(SELLER_1)).andExpect(jsonPath("$.results[0].result").value("SENT"));
        LocalDateTime sentAt = jdbc.queryForObject(
                "SELECT sent_at FROM notification_log WHERE target_type = 'SELLER' AND target_id = ?", LocalDateTime.class, SELLER_1);
        String lastNudgedAt = JsonPath.read(mockMvc.perform(get(url + sellerPid(SELLER_1)).with(authHeaders.admin(ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.lastNudgedAt");
        assertThat(lastNudgedAt).endsWith("+09:00");
        assertThat(OffsetDateTime.parse(lastNudgedAt)).isEqualTo(sentAt.atOffset(ZoneOffset.ofHours(9)));

        mockMvc.perform(get(url + sellerPid(SELLER_5)).with(authHeaders.admin(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryReadyOverdueCount").value(0))
                .andExpect(jsonPath("$.questionUnansweredOverdueCount").value(0));
        mockMvc.perform(get(url + pid("slr_", "SDNNONE")).with(authHeaders.admin(ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SELLER_NOT_FOUND"));

        // 인가: 관리자 200(위)으로 엔드포인트 존재를 확인한 뒤 쿠키 없음·셀러 쿠키 401
        mockMvc.perform(get(url + sellerPid(SELLER_1))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(url + sellerPid(SELLER_1)).with(authHeaders.seller(SELLER_USER_1))).andExpect(status().isUnauthorized());
    }

    // ---------- helpers ----------

    private ResultActions nudge(String... sellerPublicIds) throws Exception {
        return mockMvc.perform(post(URL).with(authHeaders.admin(ADMIN)).contentType(MediaType.APPLICATION_JSON)
                .content(body(sellerPublicIds)));
    }

    private ResultActions nudge(List<String> sellerPublicIds) throws Exception {
        return nudge(sellerPublicIds.toArray(String[]::new));
    }

    private String body(String... sellerPublicIds) {
        return objectMapper.writeValueAsString(Map.of("sellerPublicIds", List.of(sellerPublicIds)));
    }

    private static List<String> unknownIds(int count) {
        return IntStream.range(0, count).mapToObj(index -> pid("slr_", "SDNUNK" + index)).toList();
    }

    private Map<String, Object> logOf(long sellerId) {
        return jdbc.queryForMap("SELECT status, channel, template_code, recipient_user_id, content, failed_reason "
                + "FROM notification_log WHERE target_type = 'SELLER' AND target_id = ?", sellerId);
    }

    private Long recipientUserIdOf(long sellerId) {
        return jdbc.queryForObject("SELECT recipient_user_id FROM notification_log WHERE target_type = 'SELLER' AND target_id = ?",
                Long.class, sellerId);
    }

    private String failedReasonOf(long sellerId) {
        return jdbc.queryForObject("SELECT failed_reason FROM notification_log WHERE target_type = 'SELLER' AND target_id = ?",
                String.class, sellerId);
    }

    private int logCount(long sellerId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM notification_log WHERE target_type = 'SELLER' AND target_id = ?",
                Integer.class, sellerId);
        return count == null ? 0 : count;
    }

    private static String sellerPid(long sellerId) {
        return pid("slr_", "SDN" + sellerId);
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

    private void seedSeller(long sellerId, String contactPhone) {
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, contact_phone, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, '대표', ?, 'ACTIVE', NOW(6), NOW(6))", sellerId, sellerPid(sellerId), "독촉셀러" + sellerId,
                contactPhone);
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, is_soldout_manual, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, '독촉상품', 'SALE', 10000, 0, NOW(6), NOW(6))",
                sellerId, pid("prd_", "SDN" + sellerId), sellerId, DUMMY_FK_ID);
    }

    private void seedMember(long userId, long sellerId, String phone) {
        jdbc.update("INSERT INTO `user` (id, public_id, phone, created_at, updated_at) VALUES (?, ?, ?, NOW(6), NOW(6))",
                userId, pid("usr_", "SDN" + userId), phone);
        jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'", userId, sellerId);
    }

    /** 셀러당 품목·주문·질문 1건(id = 셀러 id). */
    private void seedPaidItem(long sellerId, LocalDateTime paidAt) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                + "ordered_at, paid_at, created_at, updated_at) VALUES (?, ?, ?, ?, 'PAID', 10000, 0, 0, ?, ?, NOW(6), NOW(6))",
                sellerId, pid("ord_", "SDN" + sellerId), BUYER_ID, "SDN" + sellerId, paidAt, paidAt);
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, product_name, quantity, "
                + "unit_price, total_price, commission_rate, item_status, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, '독촉상품', 1, 10000, 10000, 1000, 'PAID', NOW(6), NOW(6))",
                sellerId, pid("oit_", "SDN" + sellerId), sellerId, sellerId, DUMMY_FK_ID, sellerId);
    }

    private void seedQuestion(long sellerId, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO product_question (id, public_id, product_id, buyer_id, content, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, '질문', 'VISIBLE', ?, ?)", sellerId, pid("pqn_", "SDN" + sellerId), sellerId, BUYER_ID,
                createdAt, createdAt);
    }

    private void cleanup() {
        seed(() -> {
            jdbc.update("DELETE FROM notification_log WHERE target_type = 'SELLER' AND target_id BETWEEN ? AND ?", SELLER_1, SELLER_5);
            jdbc.update("DELETE FROM product_question WHERE id BETWEEN ? AND ?", SELLER_1, SELLER_5);
            jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", SELLER_1, SELLER_5);
            jdbc.update("DELETE FROM `order` WHERE id BETWEEN ? AND ?", SELLER_1, SELLER_5);
            jdbc.update("DELETE FROM product WHERE id BETWEEN ? AND ?", SELLER_1, SELLER_5);
            jdbc.update("DELETE FROM seller_user WHERE user_id BETWEEN ? AND ?", SELLER_USER_1, OWNER_3);
            jdbc.update("DELETE FROM seller WHERE id BETWEEN ? AND ?", SELLER_1, SELLER_5);
            jdbc.update("DELETE FROM `user` WHERE id BETWEEN ? AND ?", BUYER_ID, OWNER_3);
        });
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
