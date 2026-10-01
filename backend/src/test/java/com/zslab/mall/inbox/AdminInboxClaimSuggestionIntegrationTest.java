package com.zslab.mall.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인박스 클레임 접수 행의 유형·제안(D-250) 통합 테스트. 클레임 접수 행에만 두 필드가 실리고, 제안 계산이 행 목록 단위 배치라
 * 클레임 행 수가 늘어도 쿼리 수가 같다(N+1 없음). 단일 트랜잭션 롤백.
 */
@AutoConfigureMockMvc
@Transactional
class AdminInboxClaimSuggestionIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/admin/inbox";
    private static final long ADMIN = 7001L;
    private static final long BUYER = 9651L;
    private static final long SELLER = 9651L;
    private static final long PRODUCT_ID = 9651L;
    private static final long VARIANT_ORIGINAL = 9651L;
    private static final long VARIANT_EXCHANGE = 9652L;
    /** 케이스별 id = BASE + case*10 (주문 +0 · 품목 +1 · 클레임 +2). */
    private static final long BASE = 96500L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("클레임 접수 행: claimType·suggestion 실림(취소 미출고 APPROVE · 반품 증빙 없음 REVIEW) · 다른 유형 행에는 없음")
    void claimRequestedRows_carryTypeAndSuggestion() throws Exception {
        String cancel = seedRequestedClaim(1, "CANCEL", "ORDER_MISTAKE", "PAID");
        String returnNoEvidence = seedRequestedClaim(2, "RETURN", "PRODUCT_DEFECT", "DELIVERED");
        String followup = seedApprovedCancelWithoutRefund(3);

        mockMvc.perform(get(URL).param("tab", "TODAY").with(authHeaders.admin(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.ref == '" + cancel + "')].claimType").value("CANCEL"))
                .andExpect(jsonPath("$.items[?(@.ref == '" + cancel + "')].suggestion").value("APPROVE"))
                .andExpect(jsonPath("$.items[?(@.ref == '" + returnNoEvidence + "')].claimType").value("RETURN"))
                .andExpect(jsonPath("$.items[?(@.ref == '" + returnNoEvidence + "')].suggestion").value("REVIEW"))
                .andExpect(jsonPath("$.items[?(@.ref == '" + followup + ":REFUND')].type").value("CLAIM_FOLLOWUP"))
                .andExpect(jsonPath("$.items[?(@.ref == '" + followup + ":REFUND')].claimType").isEmpty())
                .andExpect(jsonPath("$.items[?(@.ref == '" + followup + ":REFUND')].suggestion").isEmpty());
    }

    @Test
    @DisplayName("배치 조회: 클레임 접수 행 2건(교환 포함) → 6건(교환 2)이어도 인박스 조회 쿼리 수가 같다")
    void suggestionsAreBatched() throws Exception {
        seedCatalog();
        seedRequestedClaim(1, "CANCEL", "ORDER_MISTAKE", "PAID");
        seedRequestedClaim(2, "EXCHANGE", "BUYER_CHANGED_MIND", "DELIVERED");
        long few = countInboxQueries(2);

        seedRequestedClaim(3, "RETURN", "PRODUCT_DEFECT", "DELIVERED");
        seedRequestedClaim(4, "RETURN", "BUYER_CHANGED_MIND", "DELIVERED");
        seedRequestedClaim(5, "CANCEL", "ORDER_MISTAKE", "PREPARING");
        seedRequestedClaim(6, "EXCHANGE", "PRODUCT_DEFECT", "DELIVERED");
        long many = countInboxQueries(6);

        assertThat(many).isEqualTo(few);
    }

    private long countInboxQueries(int expectedClaimRows) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        mockMvc.perform(get(URL).param("tab", "TODAY").param("type", "CLAIM_REQUESTED").with(authHeaders.admin(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.ref =~ /clm_SGI.*/)].suggestion").value(hasSize(expectedClaimRows)));
        long count = statistics.getPrepareStatementCount();
        statistics.setStatisticsEnabled(false);
        return count;
    }

    // ===== 시드(FK 끔 · 모든 INSERT는 ?n 바인딩 + 정적 SQL — SQL injection 위험 없음) =====

    /** 이틀 전 접수(오늘 탭 · 기한 경과) 클레임. */
    private String seedRequestedClaim(int caseNo, String type, String reasonCode, String previousStatus) {
        String claimPid = pid("clm_", "SGI" + caseNo);
        seedOrderAndItem(caseNo, previousStatus);
        seed(() -> execute("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, previous_order_item_status, "
                        + "exchange_variant_id, requested_by, requested_at, created_at, updated_at) "
                        + "VALUES (?1, ?2, ?3, ?4, ?5, 'REQUESTED', ?6, ?7, ?8, NOW(6) - INTERVAL 2 DAY, NOW(6), NOW(6))",
                claimId(caseNo), claimPid, BASE + caseNo * 10L + 1, type, reasonCode, previousStatus,
                "EXCHANGE".equals(type) ? VARIANT_EXCHANGE : null, BUYER));
        return claimPid;
    }

    /** 교환 제안 입력(옵션 판매 여부·가용 재고)용 상품·옵션 2·재고. */
    private void seedCatalog() {
        seed(() -> {
            execute("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                    + "VALUES (?1, ?2, ?3, 1, '인박스제안상품', 'SALE', 10000, NOW(6), NOW(6))", PRODUCT_ID, pid("prd_", "SGIPRD"), SELLER);
            for (long variantId : new long[] {VARIANT_ORIGINAL, VARIANT_EXCHANGE}) {
                execute("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, is_soldout_manual, "
                        + "display_order, option1_value_id, created_at, updated_at) VALUES (?1, ?2, ?3, ?4, 0, 'SALE', 0, 1, 1, NOW(6), NOW(6))",
                        variantId, pid("var_", "SGI" + variantId), PRODUCT_ID, "VC" + variantId);
                execute("INSERT INTO inventory (variant_id, quantity_on_hand, quantity_reserved, quantity_available, created_at, updated_at) "
                        + "VALUES (?1, 10, 0, 10, NOW(6), NOW(6))", variantId);
            }
        });
    }

    /** 승인됐지만 환불 행이 없는 취소(클레임 후속 · 환불 개시 단계). */
    private String seedApprovedCancelWithoutRefund(int caseNo) {
        String claimPid = pid("clm_", "SGI" + caseNo);
        seedOrderAndItem(caseNo, "PAID");
        seed(() -> execute("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, previous_order_item_status, "
                        + "requested_by, requested_at, processed_at, created_at, updated_at) "
                        + "VALUES (?1, ?2, ?3, 'CANCEL', 'ORDER_MISTAKE', 'APPROVED', 'PAID', ?4, NOW(6) - INTERVAL 4 DAY, "
                        + "NOW(6) - INTERVAL 3 DAY, NOW(6), NOW(6))",
                claimId(caseNo), claimPid, BASE + caseNo * 10L + 1, BUYER));
        return claimPid;
    }

    private void seedOrderAndItem(int caseNo, String itemStatus) {
        long orderId = BASE + caseNo * 10L;
        seed(() -> {
            execute("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                    + "created_at, updated_at) VALUES (?1, ?2, ?3, ?4, 'PAID', 10000, 0, 0, NOW(6), NOW(6))",
                    orderId, pid("ord_", "SGI" + caseNo), BUYER, "ORDSGI" + orderId);
            execute("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                    + "total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                    + "VALUES (?1, ?2, ?3, ?4, ?5, ?6, 1, 10000, 10000, ?7, NOW(6), NOW(6), '인박스제안상품', 1000)",
                    orderId + 1, pid("oit_", "SGI" + caseNo), orderId, PRODUCT_ID, VARIANT_ORIGINAL, SELLER, itemStatus);
        });
    }

    private static long claimId(int caseNo) {
        return BASE + caseNo * 10L + 2;
    }

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

    private void execute(String sql, Object... parameters) {
        Query query = entityManager.createNativeQuery(sql);
        for (int index = 0; index < parameters.length; index++) {
            query.setParameter(index + 1, parameters[index]);
        }
        query.executeUpdate();
    }

    /** prefix + 26자 본문(tag 대문자 + '0' 패딩)으로 30자 public_id를 만든다. */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
