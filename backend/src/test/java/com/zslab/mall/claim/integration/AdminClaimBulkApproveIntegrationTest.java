package com.zslab.mall.claim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 클레임 일괄 승인(D-250) 통합 테스트. 항목별 커밋을 실측해야 하므로 클래스에 {@code @Transactional}을 두지 않고,
 * 시드·정리는 {@link TransactionTemplate} + FOREIGN_KEY_CHECKS=0으로 한다(ClaimExchangeIntegrationTest 패턴).
 * 커밋 경계 케이스의 성공 항목은 반품(커밋 후 처리가 알림뿐)으로 두고, 돈 경로는 취소 2건 케이스에서 Mock PG 자동 콜백까지 실측한다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "zslab.order.auto-cancel.enabled=false",
        "zslab.refund.recovery.enabled=false",
        "zslab.order.expired-cleanup.enabled=false",
        "zslab.order.auto-confirm.enabled=false"
})
class AdminClaimBulkApproveIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/admin/claims/bulk/approve";
    private static final long ADMIN = 7001L;
    private static final long SELLER_USER = 7002L;
    private static final long BUYER = 9601L;
    private static final long SELLER = 9601L;
    private static final long PRODUCT_ID = 9601L;
    private static final long VARIANT_ORIGINAL = 9601L;
    private static final long VARIANT_EXCHANGE = 9602L;
    /** 케이스별 id = BASE + case*10 (주문 +0 · 품목 +1 · 클레임 +2). */
    private static final long BASE = 96000L;
    /** 취소 2건 케이스(주문 1 · 품목 2 · 클레임 2 · 결제 1)가 쓰는 id 구간 = BASE + 70 ~ 79. */
    private static final int CANCEL_CASE = 7;
    private static final int CASE_COUNT = 7;

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
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("부분 실패: 입력 순서대로 성공·제안 불일치·상태 422·재고 422·미존재·중복 → 200·집계 · 앞 항목 커밋 유지 · 성공만 기록")
    void partialFailure_keepsCommittedItemsAndOrder() throws Exception {
        String changeOfMind = seedCase(1, "RETURN", "BUYER_CHANGED_MIND", "REQUESTED", 0);
        String noEvidence = seedCase(2, "RETURN", "WRONG_PRODUCT", "REQUESTED", 0);
        String alreadyApproved = seedCase(3, "RETURN", "BUYER_CHANGED_MIND", "APPROVED", 0);
        String stockBroken = seedCase(4, "EXCHANGE", "BUYER_CHANGED_MIND", "REQUESTED", 0);
        String withEvidence = seedCase(5, "RETURN", "PRODUCT_DEFECT", "REQUESTED", 1);
        String unknown = pid("clm_", "BKNONE");

        mockMvc.perform(post(URL).with(authHeaders.admin(ADMIN)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(changeOfMind, noEvidence, alreadyApproved, stockBroken, unknown, changeOfMind, withEvidence)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(2))
                .andExpect(jsonPath("$.failureCount").value(5))
                .andExpect(jsonPath("$.results[0].claimPublicId").value(changeOfMind))
                .andExpect(jsonPath("$.results[0].success").value(true))
                .andExpect(jsonPath("$.results[0].code").doesNotExist())
                .andExpect(jsonPath("$.results[1].code").value("CLAIM_SUGGESTION_MISMATCH"))
                .andExpect(jsonPath("$.results[2].code").value("CLAIM_STATE_INVALID"))
                .andExpect(jsonPath("$.results[3].code").value("INVENTORY_INVARIANT_VIOLATION"))
                .andExpect(jsonPath("$.results[4].code").value("CLAIM_NOT_FOUND"))
                .andExpect(jsonPath("$.results[5].claimPublicId").value(changeOfMind))
                .andExpect(jsonPath("$.results[5].code").value("CLAIM_STATE_INVALID"))
                .andExpect(jsonPath("$.results[6].claimPublicId").value(withEvidence))
                .andExpect(jsonPath("$.results[6].success").value(true));

        // 앞 항목(1)은 뒤 항목(4)의 실패·롤백과 무관하게 커밋돼 있다
        assertThat(claimStatus(1)).isEqualTo("APPROVED");
        assertThat(claimStatus(5)).isEqualTo("APPROVED");
        assertThat(claimStatus(2)).isEqualTo("REQUESTED");
        assertThat(claimStatus(4)).isEqualTo("REQUESTED");
        assertThat(exchangeReserved()).isZero();
        assertThat(recordCount(1)).isEqualTo(1);
        assertThat(recordCount(5)).isEqualTo(1);
        assertThat(recordCount(2) + recordCount(3) + recordCount(4)).isZero();
        assertThat(jdbc.queryForObject("SELECT rule_key FROM claim_suggestion_record WHERE claim_id = ?", String.class,
                claimId(5))).isEqualTo("DEFECT_WITH_EVIDENCE");
    }

    @Test
    @DisplayName("돈: 같은 주문 취소 2건 일괄 승인 → 항목마다 커밋 후 환불 체인 수렴 · 환불 2행 COMPLETED(각 품목 금액) · 클레임 COMPLETED · 품목 CANCELLED · 재고 복구")
    void cancelBulk_refundsEachItem() throws Exception {
        long orderId = BASE + CANCEL_CASE * 10L;
        seed(() -> {
            seedCatalog();
            // 환불 완료 콜백이 FK 검사를 켠 채 품목·주문을 갱신하므로 부모 행(구매자·셀러)이 실제로 있어야 한다
            jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))", BUYER, pid("usr_", "BKBUY"));
            jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                    + "VALUES (?, ?, '일괄셀러', '대표', 'ACTIVE', NOW(6), NOW(6))", SELLER, pid("slr_", "BKSLR"));
            jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                    + "ordered_at, paid_at, created_at, updated_at) VALUES (?, ?, ?, ?, 'PAID', 20000, 0, 0, NOW(6), NOW(6), NOW(6), NOW(6))",
                    orderId, pid("ord_", "BKCAN"), BUYER, "ORDBK" + orderId);
            jdbc.update("INSERT INTO payment (id, public_id, order_id, method, amount, status, pg_provider, pg_tid, payment_attempt_key, "
                    + "paid_at, created_at, updated_at) VALUES (?, ?, ?, 'CARD', 20000, 'PAID', 'MOCK_PG', 'tid_bk_cancel', 'pat_bk_cancel', "
                    + "NOW(6), NOW(6), NOW(6))", orderId, pid("pay_", "BKCAN"), orderId);
            for (int index = 1; index <= 2; index++) {
                long orderItemId = orderId + index * 2 - 1;
                jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                        + "total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, 10000, 10000, 'CANCEL_REQUESTED', NOW(6), NOW(6), '테스트 상품', 1000)",
                        orderItemId, pid("oit_", "BKCAN" + index), orderId, PRODUCT_ID, VARIANT_ORIGINAL, SELLER);
                jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, previous_order_item_status, "
                        + "requested_by, requested_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CANCEL', 'ORDER_MISTAKE', 'REQUESTED', 'PAID', ?, NOW(6), NOW(6), NOW(6))",
                        orderItemId + 1, pid("clm_", "BKCAN" + index), orderItemId, BUYER);
            }
        });

        mockMvc.perform(post(URL).with(authHeaders.admin(ADMIN)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(pid("clm_", "BKCAN1"), pid("clm_", "BKCAN2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(2))
                .andExpect(jsonPath("$.failureCount").value(0));

        List<String> refunds = jdbc.queryForList("SELECT CONCAT(r.status, ':', r.amount) FROM refund r JOIN claim c ON c.id = r.claim_id "
                + "JOIN order_item oi ON oi.id = c.order_item_id WHERE oi.order_id = ? ORDER BY r.id", String.class, orderId);
        assertThat(refunds).containsExactly("COMPLETED:10000", "COMPLETED:10000");
        assertThat(jdbc.queryForList("SELECT c.status FROM claim c JOIN order_item oi ON oi.id = c.order_item_id WHERE oi.order_id = ?",
                String.class, orderId)).containsOnly("COMPLETED");
        assertThat(jdbc.queryForList("SELECT item_status FROM order_item WHERE order_id = ?", String.class, orderId))
                .containsOnly("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM inventory WHERE variant_id = ?", Integer.class, VARIANT_ORIGINAL))
                .isEqualTo(12);
    }

    @Test
    @DisplayName("서버 가드: 검토 제안(R4 증빙 없음)만 보내면 전이 없이 CLAIM_SUGGESTION_MISMATCH · 기록 0")
    void reviewSuggestion_isNotApproved() throws Exception {
        String noEvidence = seedCase(1, "RETURN", "PRODUCT_DEFECT", "REQUESTED", 0);

        mockMvc.perform(post(URL).with(authHeaders.admin(ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body(noEvidence)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(0))
                .andExpect(jsonPath("$.results[0].success").value(false))
                .andExpect(jsonPath("$.results[0].code").value("CLAIM_SUGGESTION_MISMATCH"));

        assertThat(claimStatus(1)).isEqualTo("REQUESTED");
        assertThat(recordCount(1)).isZero();
    }

    @Test
    @DisplayName("검증: 20건 200(상한 포함) · 21건 400 · 빈 목록 400")
    void sizeLimits() throws Exception {
        List<String> twenty = unknownIds(20);
        mockMvc.perform(post(URL).with(authHeaders.admin(ADMIN)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(twenty.toArray(String[]::new))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCount").value(20));

        List<String> twentyOne = unknownIds(21);
        mockMvc.perform(post(URL).with(authHeaders.admin(ADMIN)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(twentyOne.toArray(String[]::new))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post(URL).with(authHeaders.admin(ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("인증: 관리자 쿠키 200(엔드포인트 존재) → 쿠키 없음 401 · 셀러 쿠키 401 · 클레임 불변")
    void authorization() throws Exception {
        String changeOfMind = seedCase(1, "RETURN", "BUYER_CHANGED_MIND", "REQUESTED", 0);
        String other = seedCase(2, "RETURN", "BUYER_CHANGED_MIND", "REQUESTED", 0);

        mockMvc.perform(post(URL).with(authHeaders.admin(ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body(changeOfMind)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1));
        mockMvc.perform(post(URL).with(authHeaders.csrf()).contentType(MediaType.APPLICATION_JSON).content(body(other)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL).with(authHeaders.seller(SELLER_USER)).contentType(MediaType.APPLICATION_JSON).content(body(other)))
                .andExpect(status().isUnauthorized());

        assertThat(claimStatus(2)).isEqualTo("REQUESTED");
    }

    // ===== 시드·정리(모든 SQL은 ? 바인딩 + 정적 문자열 — SQL injection 위험 없음) =====

    /**
     * 주문·품목(수량 1)·클레임 + 첨부 n개. 교환은 교환 옵션의 가용 재고 칸은 10이지만 실물 0이라 제안(가용 재고 기준)은 승인,
     * 예약(실물 − 예약 기준)은 INV-1 위반이 된다 — 제안 판정 이후 재고 예약 실패를 결정적으로 만든다.
     */
    private String seedCase(int caseNo, String type, String reasonCode, String claimStatus, int attachments) {
        long orderId = BASE + caseNo * 10L;
        long orderItemId = orderId + 1;
        long claimId = orderId + 2;
        String claimPid = pid("clm_", "BK" + caseNo);
        seed(() -> {
            if ("EXCHANGE".equals(type)) {
                seedCatalog();
            }
            jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                    + "created_at, updated_at) VALUES (?, ?, ?, ?, 'DELIVERED', 10000, 0, 0, NOW(6), NOW(6))",
                    orderId, pid("ord_", "BK" + caseNo), BUYER, "ORDBK" + orderId);
            jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                    + "total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                    + "VALUES (?, ?, ?, ?, ?, ?, 1, 10000, 10000, ?, NOW(6), NOW(6), '테스트 상품', 1000)",
                    orderItemId, pid("oit_", "BK" + caseNo), orderId, PRODUCT_ID, VARIANT_ORIGINAL, SELLER,
                    "REQUESTED".equals(claimStatus) ? type + "_REQUESTED" : "DELIVERED");
            jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, previous_order_item_status, "
                    + "exchange_variant_id, requested_by, requested_at, processed_at, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, 'DELIVERED', ?, ?, NOW(6), ?, NOW(6), NOW(6))",
                    claimId, claimPid, orderItemId, type, reasonCode, claimStatus,
                    "EXCHANGE".equals(type) ? VARIANT_EXCHANGE : null, BUYER,
                    "REQUESTED".equals(claimStatus) ? null : Timestamp.valueOf(LocalDateTime.now()));
            for (int index = 0; index < attachments; index++) {
                jdbc.update("INSERT INTO attachment (public_id, target_type, target_id, file_name, file_path, mime_type, file_size, "
                        + "display_order, uploaded_by, created_at, updated_at) "
                        + "VALUES (?, 'CLAIM', ?, 'a.png', '/claims/a.png', 'image/png', 1, ?, ?, NOW(6), NOW(6))",
                        pid("att_", "BK" + caseNo + "N" + index), claimId, index + 1, BUYER);
            }
        });
        return claimPid;
    }

    private void seedCatalog() {
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                + "VALUES (?, ?, ?, 1, '테스트 상품', 'SALE', 10000, NOW(6), NOW(6))", PRODUCT_ID, pid("prd_", "BKPRD"), SELLER);
        seedVariant(VARIANT_ORIGINAL, 10, 10);
        seedVariant(VARIANT_EXCHANGE, 0, 10);
    }

    private void seedVariant(long id, int onHand, int available) {
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, is_soldout_manual, "
                + "display_order, option1_value_id, created_at, updated_at) VALUES (?, ?, ?, ?, 0, 'SALE', 0, 1, 1, NOW(6), NOW(6))",
                id, pid("var_", "BK" + id), PRODUCT_ID, "VC" + id);
        jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, created_at, updated_at) "
                + "VALUES (?, ?, ?, 0, ?, NOW(6), NOW(6))", id, id, onHand, available);
    }

    private void seed(Runnable seedingWork) {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedingWork.run();
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        long firstClaimId = BASE + 12;
        long lastClaimId = BASE + CASE_COUNT * 10L + 9;
        long firstOrderId = BASE;
        long lastOrderId = BASE + CASE_COUNT * 10L + 9;
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM refund WHERE claim_id BETWEEN ? AND ?", firstClaimId, lastClaimId);
                jdbc.update("DELETE FROM payment WHERE order_id BETWEEN ? AND ?", firstOrderId, lastOrderId);
                jdbc.update("DELETE FROM reconciliation_issue WHERE order_id BETWEEN ? AND ?", firstOrderId, lastOrderId);
                jdbc.update("DELETE FROM inventory_history WHERE inventory_id IN (?, ?)", VARIANT_ORIGINAL, VARIANT_EXCHANGE);
                jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", BUYER);
                jdbc.update("DELETE FROM claim_suggestion_record WHERE claim_id BETWEEN ? AND ?", firstClaimId, lastClaimId);
                jdbc.update("DELETE FROM audit_log WHERE target_type = 'CLAIM' AND target_id BETWEEN ? AND ?", firstClaimId, lastClaimId);
                jdbc.update("DELETE FROM notification_log WHERE target_type = 'CLAIM' AND target_id BETWEEN ? AND ?",
                        firstClaimId, lastClaimId);
                jdbc.update("DELETE FROM attachment WHERE target_type = 'CLAIM' AND target_id BETWEEN ? AND ?", firstClaimId, lastClaimId);
                jdbc.update("DELETE FROM claim WHERE id BETWEEN ? AND ?", firstClaimId, lastClaimId);
                jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", firstOrderId, lastOrderId);
                jdbc.update("DELETE FROM `order` WHERE id BETWEEN ? AND ?", firstOrderId, lastOrderId);
                jdbc.update("DELETE FROM inventory WHERE variant_id IN (?, ?)", VARIANT_ORIGINAL, VARIANT_EXCHANGE);
                jdbc.update("DELETE FROM product_variant WHERE id IN (?, ?)", VARIANT_ORIGINAL, VARIANT_EXCHANGE);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER);
                jdbc.update("DELETE FROM `user` WHERE id = ?", BUYER);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private long claimId(int caseNo) {
        return BASE + caseNo * 10L + 2;
    }

    private String claimStatus(int caseNo) {
        return jdbc.queryForObject("SELECT status FROM claim WHERE id = ?", String.class, claimId(caseNo));
    }

    private int recordCount(int caseNo) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM claim_suggestion_record WHERE claim_id = ?", Integer.class,
                claimId(caseNo));
        return count == null ? 0 : count;
    }

    private int exchangeReserved() {
        Integer reserved = jdbc.queryForObject("SELECT quantity_reserved FROM inventory WHERE variant_id = ?", Integer.class,
                VARIANT_EXCHANGE);
        return reserved == null ? 0 : reserved;
    }

    private static List<String> unknownIds(int count) {
        return IntStream.range(0, count).mapToObj(index -> pid("clm_", "BKUNK" + index)).collect(Collectors.toCollection(ArrayList::new));
    }

    private static String body(String... claimPublicIds) {
        String ids = Arrays.stream(claimPublicIds).map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
        return "{\"claimPublicIds\":[" + ids + "]}";
    }

    /** prefix + 26자 본문(tag 대문자 + '0' 패딩)으로 30자 public_id를 만든다. */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
