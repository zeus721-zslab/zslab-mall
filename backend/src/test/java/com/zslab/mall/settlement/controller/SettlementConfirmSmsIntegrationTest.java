package com.zslab.mall.settlement.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.notification.adapter.SmsSender;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 정산 정상처리(confirm) 셀러 SMS E2E 통합 테스트(Track 85). AFTER_COMMIT 핸들러 → NotificationService → SmsSender 체인을 실 커밋으로
 * 구동해 본문 원문·수신처 3단 fallback(seller.contact_phone → SELLER_OWNER user.phone → 없음)·발송 실패 시 CONFIRMED 유지를 검증한다.
 * {@link SmsSender}는 MockitoBean으로 대체한다(Track80CancelFlowIntegrationTest 패턴).
 */
@AutoConfigureMockMvc
class SettlementConfirmSmsIntegrationTest extends AbstractIntegrationTest {

    private static final long ADMIN_ID = 9485L;
    private static final long SELLER_ID = 9485L;
    private static final long OWNER_USER_ID = 9485L;
    private static final long STL_ID = 9485L;
    private static final String CONTACT_PHONE = "010-8500-0001";
    private static final String OWNER_PHONE = "010-8500-0002";
    private static final String EXPECTED_BODY = "[zslab-mall] 2026년 6월 정산이 확정되었습니다. 정산금액 13,500원, 지급예정일 7월 20일.";
    private static final String URL = "/api/v1/admin/settlements/" + STL_ID + "/confirm";

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

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        doNothing().when(smsSender).send(any(), any());
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("SMS1 seller.contact_phone 있음 → 그 번호로 원문 발송·notification_log SETTLEMENT/SENT(recipient null)")
    void confirm_sendsToContactPhone() throws Exception {
        seed(CONTACT_PHONE, OWNER_PHONE);

        mockMvc.perform(post(URL).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        verify(smsSender).send(eq(CONTACT_PHONE), eq(EXPECTED_BODY));
        Map<String, Object> log = notificationLog();
        assertThat(log.get("status")).isEqualTo("SENT");
        assertThat(log.get("template_code")).isEqualTo("TPL_SETTLEMENT_CONFIRMED");
        assertThat(log.get("recipient_user_id")).isNull();
        assertThat((String) log.get("content")).isEqualTo(EXPECTED_BODY);
    }

    @Test
    @DisplayName("SMS2 contact_phone 없음 → SELLER_OWNER 구성원 user.phone으로 발송·recipient_user_id=OWNER")
    void confirm_fallsBackToOwnerPhone() throws Exception {
        seed(null, OWNER_PHONE);

        mockMvc.perform(post(URL).headers(authHeaders.admin(ADMIN_ID))).andExpect(status().isOk());

        verify(smsSender).send(eq(OWNER_PHONE), eq(EXPECTED_BODY));
        assertThat(notificationLog().get("recipient_user_id")).isEqualTo(OWNER_USER_ID);
    }

    @Test
    @DisplayName("SMS3 contact_phone·OWNER phone 모두 없음 → 발송 없음·로그 없음·CONFIRMED 유지")
    void confirm_skipsWhenNoRecipient() throws Exception {
        seed(null, null);

        mockMvc.perform(post(URL).headers(authHeaders.admin(ADMIN_ID))).andExpect(status().isOk());

        verify(smsSender, never()).send(any(), any());
        assertThat(jdbc.queryForObject("SELECT status FROM settlement WHERE id = ?", String.class, STL_ID)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_log WHERE target_type = 'SETTLEMENT' AND target_id = ?",
                Integer.class, STL_ID)).isZero();
    }

    @Test
    @DisplayName("SMS4 발송 실패(예외) → 로그 FAILED·전이는 CONFIRMED 유지(AFTER_COMMIT 격리)")
    void confirm_keepsConfirmedWhenSendFails() throws Exception {
        seed(CONTACT_PHONE, null);
        doThrow(new IllegalStateException("SMS 게이트웨이 장애")).when(smsSender).send(any(), any());

        mockMvc.perform(post(URL).headers(authHeaders.admin(ADMIN_ID))).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT status FROM settlement WHERE id = ?", String.class, STL_ID)).isEqualTo("CONFIRMED");
        Map<String, Object> log = notificationLog();
        assertThat(log.get("status")).isEqualTo("FAILED");
        assertThat((String) log.get("failed_reason")).contains("SMS 게이트웨이 장애");
    }

    // ---------- seed·helpers(바인딩 파라미터·정적 SQL·SQL injection 위험 없음) ----------

    private Map<String, Object> notificationLog() {
        return jdbc.queryForMap("SELECT status, template_code, recipient_user_id, content, failed_reason FROM notification_log "
                + "WHERE target_type = 'SETTLEMENT' AND target_id = ?", STL_ID);
    }

    /** 셀러(contact_phone 지정)·OWNER 구성원 user(phone 지정·null 허용)·PENDING 정산(6월·net 13500·지급예정 7/20)을 시드한다. */
    private void seed(String contactPhone, String ownerPhone) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, contact_phone, status, commission_rate, "
                        + "created_at, updated_at) VALUES (?, ?, '정산셀러', '대표', ?, 'ACTIVE', NULL, NOW(6), NOW(6))",
                        SELLER_ID, "slr_STL85SMSSELLER000000000000", contactPhone);
                jdbc.update("INSERT INTO `user` (id, public_id, email, name, phone, created_at, updated_at) "
                        + "VALUES (?, ?, ?, '셀러대표', ?, NOW(6), NOW(6))",
                        OWNER_USER_ID, "usr_STL85SMSOWNER0000000000000", "stl85owner@example.com", ownerPhone);
                jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                        + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'", OWNER_USER_ID, SELLER_ID);
                jdbc.update("INSERT INTO settlement (id, seller_id, bank_account_id, period_start, period_end, gross_amount, "
                        + "fee_amount, refund_amount, net_amount, status, scheduled_pay_date, created_at, updated_at) "
                        + "VALUES (?, ?, NULL, ?, ?, 15000, 1500, 0, 13500, 'PENDING', '2026-07-20', NOW(6), NOW(6))",
                        STL_ID, SELLER_ID, LocalDateTime.of(2026, 6, 1, 0, 0),
                        LocalDateTime.of(2026, 6, 30, 23, 59, 59, 999_999_000));
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM notification_log WHERE target_type = 'SETTLEMENT' AND target_id = ?", STL_ID);
                jdbc.update("DELETE FROM audit_log WHERE target_type = 'SETTLEMENT' AND target_id = ?", STL_ID);
                jdbc.update("DELETE FROM settlement WHERE id = ?", STL_ID);
                jdbc.update("DELETE FROM seller_user WHERE user_id = ?", OWNER_USER_ID);
                jdbc.update("DELETE FROM `user` WHERE id = ?", OWNER_USER_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }
}
