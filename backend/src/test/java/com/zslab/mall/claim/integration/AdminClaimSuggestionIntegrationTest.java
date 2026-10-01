package com.zslab.mall.claim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.claim.service.ClaimSuggestionService;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.inventory.service.InventoryService;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 클레임 단건 GET + 처리 제안 규칙(D-250 §2 R0~R5) 통합 테스트. 실 MariaDB·MockMvc로 규칙 입력(유형·사유·첨부 수·요청 시 품목 상태·
 * 교환 옵션 판매 여부·가용 재고)이 DB에서 모여 규칙 키·제안·근거 문구로 나오는지 본다. 단일 트랜잭션 롤백(AdminClaimIntegrationTest 패턴).
 */
@AutoConfigureMockMvc
@Transactional
class AdminClaimSuggestionIntegrationTest extends AbstractIntegrationTest {

    private static final long ADMIN = 7001L;
    private static final long SELLER_USER = 7002L;
    private static final long BUYER = 9521L;
    private static final long SELLER = 9021L;
    private static final long PRODUCT_ID = 9531L;
    private static final long VARIANT_ORIGINAL = 9532L;
    private static final long VARIANT_EXCHANGE = 9533L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthHeaders authHeaders;

    @Autowired
    private ClaimRepository claimRepository;

    @Autowired
    private ClaimSuggestionService claimSuggestionService;

    @Autowired
    private InventoryService inventoryService;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("R2 취소 · 요청 시 PAID → APPROVE·UNSHIPPED_CANCEL·근거 문구 · 목록 행 필드 함께")
    void cancelUnshipped_suggestsApprove() throws Exception {
        String claimPid = seedClaimCase(1, ClaimType.CANCEL, "ORDER_MISTAKE", OrderItemStatus.PAID, ClaimStatus.REQUESTED, 0);

        expectSuggestion(claimPid, "APPROVE", "UNSHIPPED_CANCEL", "미출고 취소 요청")
                .andExpect(jsonPath("$.claim.claimId").value(claimPid))
                .andExpect(jsonPath("$.claim.type").value("CANCEL"))
                .andExpect(jsonPath("$.claim.productName").value("테스트 상품"))
                .andExpect(jsonPath("$.claim.amount").value(10000))
                .andExpect(jsonPath("$.claim.availableActions[0]").value("APPROVE"));
    }

    @Test
    @DisplayName("R3 반품 · 상품불량 · 첨부 1 → APPROVE·DEFECT_WITH_EVIDENCE")
    void returnDefectWithAttachment_suggestsApprove() throws Exception {
        String claimPid = seedClaimCase(2, ClaimType.RETURN, "PRODUCT_DEFECT", OrderItemStatus.DELIVERED, ClaimStatus.REQUESTED, 1);

        expectSuggestion(claimPid, "APPROVE", "DEFECT_WITH_EVIDENCE", "증빙 첨부");
    }

    @Test
    @DisplayName("R4 반품 · 오배송 · 첨부 없음 → REVIEW·DEFECT_WITHOUT_EVIDENCE")
    void returnWrongProductWithoutAttachment_suggestsReview() throws Exception {
        String claimPid = seedClaimCase(3, ClaimType.RETURN, "WRONG_PRODUCT", OrderItemStatus.DELIVERED, ClaimStatus.REQUESTED, 0);

        expectSuggestion(claimPid, "REVIEW", "DEFECT_WITHOUT_EVIDENCE", "증빙 없음");
    }

    @Test
    @DisplayName("R5 반품 · 단순 변심 → APPROVE·CHANGE_OF_MIND")
    void returnChangeOfMind_suggestsApprove() throws Exception {
        String claimPid = seedClaimCase(4, ClaimType.RETURN, "BUYER_CHANGED_MIND", OrderItemStatus.DELIVERED, ClaimStatus.REQUESTED, 0);

        expectSuggestion(claimPid, "APPROVE", "CHANGE_OF_MIND", "기한 내 단순 변심");
    }

    @Test
    @DisplayName("R1 교환 · 옵션 가용 재고 0 < 수량 1 → REVIEW·EXCHANGE_STOCK_SHORT(사유가 R5여도 R1이 먼저)")
    void exchangeStockShort_suggestsReview() throws Exception {
        seedCatalog("SALE", 0);
        String claimPid = seedClaimCase(5, ClaimType.EXCHANGE, "BUYER_CHANGED_MIND", OrderItemStatus.DELIVERED, ClaimStatus.REQUESTED, 0);

        expectSuggestion(claimPid, "REVIEW", "EXCHANGE_STOCK_SHORT", "교환 옵션 재고 부족");
    }

    @Test
    @DisplayName("R1 교환 · 옵션 판매중지(재고는 충분) → REVIEW·EXCHANGE_STOCK_SHORT")
    void exchangeOptionStopped_suggestsReview() throws Exception {
        seedCatalog("STOPPED", 10);
        String claimPid = seedClaimCase(6, ClaimType.EXCHANGE, "BUYER_CHANGED_MIND", OrderItemStatus.DELIVERED, ClaimStatus.REQUESTED, 0);

        expectSuggestion(claimPid, "REVIEW", "EXCHANGE_STOCK_SHORT", "교환 옵션 재고 부족");
    }

    @Test
    @DisplayName("R1 통과 교환 · 판매 중·재고 충분 · 단순 변심 → R5 APPROVE")
    void exchangeStockSufficient_fallsThroughToReasonRules() throws Exception {
        seedCatalog("SALE", 10);
        String claimPid = seedClaimCase(7, ClaimType.EXCHANGE, "BUYER_CHANGED_MIND", OrderItemStatus.DELIVERED, ClaimStatus.REQUESTED, 0);

        expectSuggestion(claimPid, "APPROVE", "CHANGE_OF_MIND", "기한 내 단순 변심");
    }

    @Test
    @DisplayName("R0 취소 · 요청 시 품목 SHIPPING(미출고 아님) → REVIEW·NO_MATCH")
    void cancelNotUnshipped_suggestsReviewNoMatch() throws Exception {
        String claimPid = seedClaimCase(8, ClaimType.CANCEL, "ORDER_MISTAKE", OrderItemStatus.SHIPPING, ClaimStatus.REQUESTED, 0);

        expectSuggestion(claimPid, "REVIEW", "NO_MATCH", "규칙 해당 없음");
    }

    @Test
    @DisplayName("REQUESTED가 아닌 클레임 → 200·목록 행 필드·제안 없음")
    void notRequested_hasNoSuggestion() throws Exception {
        String claimPid = seedClaimCase(9, ClaimType.CANCEL, "ORDER_MISTAKE", OrderItemStatus.PAID, ClaimStatus.APPROVED, 0);

        mockMvc.perform(get("/api/v1/admin/claims/" + claimPid).with(authHeaders.admin(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claim.status").value("APPROVED"))
                .andExpect(jsonPath("$.suggestion").doesNotExist());
    }

    @Test
    @DisplayName("미존재 claimPublicId → 404 CLAIM_NOT_FOUND")
    void unknown_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/admin/claims/" + pid("clm_", "SGNONE")).with(authHeaders.admin(ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
    }

    @Test
    @DisplayName("인증: 관리자 쿠키 200(엔드포인트 존재) → 쿠키 없음 401 · 셀러·구매자 쿠키 401")
    void authorization() throws Exception {
        String claimPid = seedClaimCase(10, ClaimType.CANCEL, "ORDER_MISTAKE", OrderItemStatus.PAID, ClaimStatus.REQUESTED, 0);
        String url = "/api/v1/admin/claims/" + claimPid;

        mockMvc.perform(get(url).with(authHeaders.admin(ADMIN))).andExpect(status().isOk());
        mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(url).with(authHeaders.seller(SELLER_USER))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(url).with(authHeaders.buyer(BUYER))).andExpect(status().isUnauthorized());
    }

    // ===== 제안 기록(V45 · 승인·거부와 같은 트랜잭션 1행) =====

    @Test
    @DisplayName("기록: 관리자 승인 → 1행(APPROVE·UNSHIPPED_CANCEL·결정 APPROVE·결정자·입력 스냅샷 JSON)")
    void approve_recordsSuggestionAndDecision() throws Exception {
        String claimPid = seedClaimCase(11, ClaimType.CANCEL, "ORDER_MISTAKE", OrderItemStatus.PAID, ClaimStatus.REQUESTED, 0);

        mockMvc.perform(post("/api/v1/admin/claims/" + claimPid + "/approve").with(authHeaders.admin(ADMIN)))
                .andExpect(status().isOk());

        assertThat(recordRows(claimPid)).singleElement().satisfies(row -> {
            assertThat(row[0]).isEqualTo("APPROVE");
            assertThat(row[1]).isEqualTo("UNSHIPPED_CANCEL");
            assertThat(row[2]).isEqualTo("APPROVE");
            assertThat(((Number) row[3]).longValue()).isEqualTo(ADMIN);
            assertThat((String) row[4]).contains("\"type\":\"CANCEL\"").contains("\"previousItemStatus\":\"PAID\"")
                    .contains("\"reasonCode\":\"ORDER_MISTAKE\"")
                    // 기록은 null 키도 남긴다(전역 non_null 직렬화와 무관)
                    .contains("\"exchangeOptionOnSale\":null");
        });
    }

    @Test
    @DisplayName("기록: 관리자 거부 → 1행(REVIEW·DEFECT_WITHOUT_EVIDENCE·결정 REJECT)")
    void reject_recordsSuggestionAndDecision() throws Exception {
        String claimPid = seedClaimCase(12, ClaimType.RETURN, "WRONG_PRODUCT", OrderItemStatus.DELIVERED, ClaimStatus.REQUESTED, 0);

        mockMvc.perform(post("/api/v1/admin/claims/" + claimPid + "/reject").with(authHeaders.admin(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reasonCode\":\"OUT_OF_POLICY\"}"))
                .andExpect(status().isOk());

        assertThat(recordRows(claimPid)).singleElement().satisfies(row -> {
            assertThat(row[0]).isEqualTo("REVIEW");
            assertThat(row[1]).isEqualTo("DEFECT_WITHOUT_EVIDENCE");
            assertThat(row[2]).isEqualTo("REJECT");
        });
    }

    @Test
    @DisplayName("기록: 승인 실패(교환 옵션 재고 부족 422) → 전이 롤백·기록 0행 · 단건 조회도 기록하지 않음")
    void failedApproveAndRead_recordNothing() throws Exception {
        seedCatalog("SALE", 0);
        String claimPid = seedClaimCase(13, ClaimType.EXCHANGE, "BUYER_CHANGED_MIND", OrderItemStatus.DELIVERED,
                ClaimStatus.REQUESTED, 0);

        mockMvc.perform(get("/api/v1/admin/claims/" + claimPid).with(authHeaders.admin(ADMIN))).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/claims/" + claimPid + "/approve").with(authHeaders.admin(ADMIN)))
                .andExpect(status().isUnprocessableContent());

        assertThat(recordRows(claimPid)).isEmpty();
    }

    @Test
    @DisplayName("재고 캐시: 제안 계산이 재고를 영속 엔티티로 올리지 않는다 — 그 뒤 다른 커밋이 바꾼 예약 수를 승인 예약이 덮어쓰지 않음")
    void suggestionDoesNotCacheInventory() {
        seedCatalog("SALE", 10);
        String claimPid = seedClaimCase(14, ClaimType.EXCHANGE, "BUYER_CHANGED_MIND", OrderItemStatus.DELIVERED,
                ClaimStatus.REQUESTED, 0);
        Claim claim = claimRepository.findByPublicId(claimPid).orElseThrow();

        claimSuggestionService.suggest(claim);
        // 다른 주문의 예약 커밋을 같은 연결에서 흉내 낸다(?n 바인딩)
        execute("UPDATE inventory SET quantity_reserved = quantity_reserved + 1, quantity_available = quantity_available - 1 "
                + "WHERE variant_id = ?1", VARIANT_EXCHANGE);
        inventoryService.reserve(VARIANT_EXCHANGE, 1);
        entityManager.flush();

        Number reserved = (Number) entityManager.createNativeQuery("SELECT quantity_reserved FROM inventory WHERE variant_id = ?1")
                .setParameter(1, VARIANT_EXCHANGE).getSingleResult();
        assertThat(reserved.intValue()).isEqualTo(2);
    }

    /** suggestion·rule_key·decision·decided_by·input_snapshot. 모든 변수는 ?n 바인딩(SQL injection 위험 없음). */
    @SuppressWarnings("unchecked")
    private List<Object[]> recordRows(String claimPid) {
        entityManager.flush();
        return entityManager.createNativeQuery("SELECT r.suggestion, r.rule_key, r.decision, r.decided_by, r.input_snapshot "
                        + "FROM claim_suggestion_record r JOIN claim c ON c.id = r.claim_id WHERE c.public_id = ?1")
                .setParameter(1, claimPid)
                .getResultList();
    }

    private ResultActions expectSuggestion(String claimPid, String suggestion,
            String ruleKey, String reason) throws Exception {
        return mockMvc.perform(get("/api/v1/admin/claims/" + claimPid).with(authHeaders.admin(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestion.suggestion").value(suggestion))
                .andExpect(jsonPath("$.suggestion.ruleKey").value(ruleKey))
                .andExpect(jsonPath("$.suggestion.reason").value(reason));
    }

    // ===== 시드(FK 끔 · 모든 INSERT는 ?n 바인딩 + 정적 SQL — SQL injection 위험 없음) =====

    /** 주문·품목(수량 1)·클레임(교환이면 교환 옵션 지정)·첨부 n개. case 번호로 id·public_id를 나눈다. */
    private String seedClaimCase(int caseNo, ClaimType type, String reasonCode, OrderItemStatus previousStatus,
            ClaimStatus claimStatus, int attachments) {
        long orderId = 95400L + caseNo * 10;
        long orderItemId = orderId + 1;
        long claimId = orderId + 2;
        String claimPid = pid("clm_", "SG" + caseNo);
        seed(() -> {
            execute("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                    + "created_at, updated_at) VALUES (?1, ?2, ?3, ?4, 'PAID', 10000, 0, 0, NOW(6), NOW(6))",
                    orderId, pid("ord_", "SG" + caseNo), BUYER, "ORDSG" + orderId);
            execute("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                    + "total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                    + "VALUES (?1, ?2, ?3, ?4, ?5, ?6, 1, 10000, 10000, ?7, NOW(6), NOW(6), '테스트 상품', 1000)",
                    orderItemId, pid("oit_", "SG" + caseNo), orderId, PRODUCT_ID, VARIANT_ORIGINAL, SELLER, previousStatus.name());
            execute("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, previous_order_item_status, "
                    + "exchange_variant_id, requested_by, requested_at, created_at, updated_at) "
                    + "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, NOW(6), NOW(6), NOW(6))",
                    claimId, claimPid, orderItemId, type.name(), reasonCode, claimStatus.name(), previousStatus.name(),
                    type == ClaimType.EXCHANGE ? VARIANT_EXCHANGE : null, BUYER);
            for (int index = 0; index < attachments; index++) {
                execute("INSERT INTO attachment (public_id, target_type, target_id, file_name, file_path, mime_type, file_size, "
                        + "display_order, uploaded_by, created_at, updated_at) "
                        + "VALUES (?1, 'CLAIM', ?2, 'a.png', '/claims/a.png', 'image/png', 1, ?3, ?4, NOW(6), NOW(6))",
                        pid("att_", "SG" + caseNo + "N" + index), claimId, index + 1, BUYER);
            }
        });
        return claimPid;
    }

    /** 원·교환 옵션(같은 상품·같은 가격). 교환 옵션의 상태와 가용 재고만 바꾼다. */
    private void seedCatalog(String exchangeVariantStatus, int exchangeAvailable) {
        seed(() -> {
            execute("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                    + "VALUES (?1, ?2, ?3, 1, '테스트 상품', 'SALE', 10000, NOW(6), NOW(6))", PRODUCT_ID, pid("prd_", "SGPRD"), SELLER);
            seedVariant(VARIANT_ORIGINAL, "SALE", 10);
            seedVariant(VARIANT_EXCHANGE, exchangeVariantStatus, exchangeAvailable);
        });
    }

    private void seedVariant(long id, String variantStatus, int available) {
        execute("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, is_soldout_manual, "
                + "display_order, option1_value_id, created_at, updated_at) VALUES (?1, ?2, ?3, ?4, 0, ?5, 0, 1, 1, NOW(6), NOW(6))",
                id, pid("var_", "SG" + id), PRODUCT_ID, "VC" + id, variantStatus);
        execute("INSERT INTO inventory (variant_id, quantity_on_hand, quantity_reserved, quantity_available, created_at, updated_at) "
                + "VALUES (?1, ?2, 0, ?2, NOW(6), NOW(6))", id, available);
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
