package com.zslab.mall.category.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.settlement.service.CommissionRateResolver;
import com.zslab.mall.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 카테고리 목록·수정·삭제·정렬 통합 테스트(Track 89-C D-185·실 MariaDB·HTTP 경유). 기존 {@link AdminCategoryControllerIntegrationTest}
 * (생성)는 건드리지 않고 별도 클래스로 둔다.
 *
 * <p><b>격리</b>: 로컬 DB에 데모 카테고리 6건이 상주하므로 목록 단언은 "이 테스트가 만든 행"만 골라 검증하고, 정렬 변경은 현재 활성 루트
 * 전체를 읽어 이 테스트 행만 자리를 바꾼 뒤 원래 순서로 되돌린다(다른 테스트·라이브 데이터 순서 불변).
 *
 * <p><b>정산 무영향(T8)</b>: 6월 CONFIRMED 품목(스냅샷 1000 bp)으로 정산을 만든 뒤 카테고리 율을 2000 bp로 바꾸고 PENDING 재생성해도
 * fee가 그대로임을 박제한다 — 율 참조는 체크아웃 1곳(CommissionRateResolver)뿐이고 정산은 order_item 스냅샷만 읽는다. 동시에
 * resolver가 새 율을 돌려주는지도 확인해 "변경 이후 신규 주문부터 적용"을 함께 고정한다.
 *
 * <p>트랜잭션: 커밋·감사 행을 JdbcTemplate로 검증하므로 클래스 {@code @Transactional} 없음. 시드/정리는 {@link TransactionTemplate} +
 * {@code FOREIGN_KEY_CHECKS=0}(try-finally 복원). 모든 SQL은 ? positional 바인딩·정적 SQL이다.
 */
@AutoConfigureMockMvc
class AdminCategoryManagementControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/admin/categories";
    private static final String PUBLIC_URL = "/api/v1/categories";
    private static final String SETTLEMENT_URL = "/api/v1/admin/settlements";

    private static final long ADMIN_ID = 8950L;
    private static final long BUYER_ID = 8951L;
    private static final long SELLER_ID = 8950L;
    private static final long PRODUCT_ID = 8950L;
    private static final long ORDER_ID = 8950L;
    private static final long ORDER_ITEM_ID = 8950L;
    private static final long ITEM_PRICE = 10_000L;
    private static final int SNAPSHOT_RATE = 1000;
    private static final int NEW_RATE = 2000;

    private static final String NAME_A = "트랙89C카테고리A";
    private static final String NAME_B = "트랙89C카테고리B";
    private static final String NAME_RENAMED = "트랙89C카테고리A수정";
    private static final String NAME_LINKED = "트랙89C상품연결";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private CommissionRateResolver commissionRateResolver;

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
    @DisplayName("T1 인가: 목록 비인증 401 · 구매자 403 · 관리자 200(defaultCommissionRate·items)")
    void authorization() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).headers(authHeaders.buyer(BUYER_ID))).andExpect(status().isForbidden());
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultCommissionRate").value(commissionRateResolver.getDefaultCommissionRate()))
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    @DisplayName("T2 목록: 활성 상품 수(삭제 상품 제외)·sort_order 순·미설정 율 키 생략·삭제 카테고리 제외")
    void list_productCount_order_excludesDeleted() throws Exception {
        long categoryA = createCategory(NAME_A, 902);
        long categoryB = createCategory(NAME_B, 901);
        seedSeller();
        seedProduct(PRODUCT_ID, categoryA, false);
        seedProduct(PRODUCT_ID + 1, categoryA, false);
        seedProduct(PRODUCT_ID + 2, categoryA, true); // 삭제 상품은 집계 제외

        JsonNode items = listItems();
        JsonNode rowA = findRow(items, categoryA);
        JsonNode rowB = findRow(items, categoryB);
        assertThat(rowA.get("productCount").asLong()).isEqualTo(2);
        assertThat(rowB.get("productCount").asLong()).isZero();
        assertThat(rowA.has("commissionRate")).isFalse(); // NULL → non_null 정책으로 키 생략
        assertThat(rowA.get("createdAt").asText()).isNotBlank();
        // sort_order 901(B)가 902(A)보다 앞
        assertThat(indexOf(items, categoryB)).isLessThan(indexOf(items, categoryA));

        jdbc.update("UPDATE category SET deleted_at = NOW(6) WHERE id = ?", categoryB);
        assertThat(findRow(listItems(), categoryB)).isNull();
    }

    @Test
    @DisplayName("T3 수정: 3필드 반영·감사 UPDATE 1행(reason)·값 무변경 재요청 감사 0·미설정(null) 환원")
    void update_fields_audit_noop() throws Exception {
        long categoryId = createCategory(NAME_A, 903);

        mockMvc.perform(put(URL + "/" + categoryId).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(NAME_RENAMED, 950, 525, "카테고리 수수료 계약 반영")))
                .andExpect(status().isNoContent());
        assertThat(count("SELECT COUNT(*) FROM category WHERE id=? AND display_name=? AND sort_order=950 AND commission_rate=525",
                categoryId, NAME_RENAMED)).isEqualTo(1);
        assertThat(auditCount("UPDATE", categoryId)).isEqualTo(1);
        String diff = jdbc.queryForObject("SELECT diff_json FROM audit_log WHERE target_type='CATEGORY' AND action='UPDATE' "
                + "AND target_id=?", String.class, categoryId);
        assertThat(diff).contains("\"commissionRate\"").contains("525").contains("카테고리 수수료 계약 반영").contains(NAME_RENAMED);

        // 같은 값 재요청 → 204·감사 불변
        mockMvc.perform(put(URL + "/" + categoryId).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(NAME_RENAMED, 950, 525, "재요청")))
                .andExpect(status().isNoContent());
        assertThat(auditCount("UPDATE", categoryId)).isEqualTo(1);

        // commissionRate null → 미설정 환원(사유 필수)
        mockMvc.perform(put(URL + "/" + categoryId).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(NAME_RENAMED, 950, null, "기본율로 환원")))
                .andExpect(status().isNoContent());
        assertThat(count("SELECT COUNT(*) FROM category WHERE id=? AND commission_rate IS NULL", categoryId)).isEqualTo(1);
        assertThat(auditCount("UPDATE", categoryId)).isEqualTo(2);
    }

    @Test
    @DisplayName("T4 수정 사유: 표시명·정렬만 변경은 사유 없이 204 · 수수료율 변경에 사유 공백 → 400 MALFORMED_REQUEST·값 불변")
    void update_reasonPolicy() throws Exception {
        long categoryId = createCategory(NAME_A, 903);

        mockMvc.perform(put(URL + "/" + categoryId).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(NAME_RENAMED, 904, null, null)))
                .andExpect(status().isNoContent());
        assertThat(auditCount("UPDATE", categoryId)).isEqualTo(1);

        mockMvc.perform(put(URL + "/" + categoryId).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(NAME_RENAMED, 904, 300, "   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        assertThat(count("SELECT COUNT(*) FROM category WHERE id=? AND commission_rate IS NULL", categoryId)).isEqualTo(1);
        assertThat(auditCount("UPDATE", categoryId)).isEqualTo(1);
    }

    @Test
    @DisplayName("T5 수정 검증: 율 경계 0·10000 허용 / -1·10001 400 VALIDATION_FAILED / 표시명 중복 409 / 미존재 404")
    void update_validation() throws Exception {
        long categoryA = createCategory(NAME_A, 903);
        createCategory(NAME_B, 904);

        for (int boundary : new int[] {0, 10_000}) {
            mockMvc.perform(put(URL + "/" + categoryA).headers(authHeaders.admin(ADMIN_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody(NAME_A, 903, boundary, "경계값")))
                    .andExpect(status().isNoContent());
            assertThat(count("SELECT COUNT(*) FROM category WHERE id=? AND commission_rate=?", categoryA, boundary)).isEqualTo(1);
        }
        for (int outOfRange : new int[] {-1, 10_001}) {
            mockMvc.perform(put(URL + "/" + categoryA).headers(authHeaders.admin(ADMIN_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody(NAME_A, 903, outOfRange, "범위 밖")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("commissionRate"));
        }
        assertThat(count("SELECT COUNT(*) FROM category WHERE id=? AND commission_rate=10000", categoryA)).isEqualTo(1);

        mockMvc.perform(put(URL + "/" + categoryA).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(NAME_B, 903, 10_000, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_DUPLICATE"));
        assertThat(count("SELECT COUNT(*) FROM category WHERE id=? AND display_name=?", categoryA, NAME_A)).isEqualTo(1);

        mockMvc.perform(put(URL + "/999999999").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(NAME_A, 0, null, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
    }

    @Test
    @DisplayName("T6 생성 경로: 생성 요청의 commissionRate는 계약에 없어 무시·NULL 저장(범위 밖 유입 경로 없음)")
    void create_ignoresCommissionRate() throws Exception {
        mockMvc.perform(post(URL).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"" + NAME_A + "\",\"sortOrder\":903,\"commissionRate\":99999}"))
                .andExpect(status().isCreated());
        assertThat(count("SELECT COUNT(*) FROM category WHERE display_name=? AND commission_rate IS NULL", NAME_A)).isEqualTo(1);
    }

    @Test
    @DisplayName("T7 삭제: 상품 0건 → 204·soft-delete·감사 DELETE·목록/공개 목록 제외 / 상품 1건 → 409 CATEGORY_HAS_PRODUCTS(건수) / 미존재 404")
    void delete_guard() throws Exception {
        long emptyCategory = createCategory(NAME_A, 903);
        long linkedCategory = createCategory(NAME_LINKED, 904);
        seedSeller();
        seedProduct(PRODUCT_ID, linkedCategory, false);
        seedProduct(PRODUCT_ID + 1, linkedCategory, true); // 삭제 상품은 가드 집계 제외 → 활성 1건

        mockMvc.perform(delete(URL + "/" + emptyCategory).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isNoContent());
        assertThat(count("SELECT COUNT(*) FROM category WHERE id=? AND deleted_at IS NOT NULL", emptyCategory)).isEqualTo(1);
        assertThat(auditCount("DELETE", emptyCategory)).isEqualTo(1);
        assertThat(findRow(listItems(), emptyCategory)).isNull();
        MvcResult publicList = mockMvc.perform(get(PUBLIC_URL)).andExpect(status().isOk()).andReturn();
        assertThat(publicList.getResponse().getContentAsString()).doesNotContain(NAME_A);

        mockMvc.perform(delete(URL + "/" + linkedCategory).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_HAS_PRODUCTS"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("productCount=1")));
        assertThat(count("SELECT COUNT(*) FROM category WHERE id=? AND deleted_at IS NULL", linkedCategory)).isEqualTo(1);
        assertThat(auditCount("DELETE", linkedCategory)).isZero();

        mockMvc.perform(delete(URL + "/" + emptyCategory).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isNotFound()); // 이미 삭제된 행은 @SQLRestriction으로 미존재
    }

    @Test
    @DisplayName("T8 정산 무영향: 6월 정산 fee=1000bp 스냅샷 → 카테고리 율 2000bp 변경 → PENDING 재생성해도 fee·order_item 불변·resolver는 새 율")
    void commissionRateChange_doesNotAffectExistingSettlement() throws Exception {
        long categoryId = createCategory(NAME_A, 903);
        seedSeller();
        seedProduct(PRODUCT_ID, categoryId, false);
        seedConfirmedOrderItem();
        assertThat(commissionRateResolver.resolve(SELLER_ID, categoryId))
                .isEqualTo(commissionRateResolver.getDefaultCommissionRate());

        long expectedFee = ITEM_PRICE * SNAPSHOT_RATE / 10_000;
        mockMvc.perform(post(SETTLEMENT_URL).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"year\":2026,\"month\":6}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdCount").value(1))
                .andExpect(jsonPath("$.settlements[0].feeAmount").value(expectedFee));
        Long originalId = jdbc.queryForObject("SELECT id FROM settlement WHERE seller_id = ?", Long.class, SELLER_ID);

        mockMvc.perform(put(URL + "/" + categoryId).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(NAME_A, 903, NEW_RATE, "수수료 인상")))
                .andExpect(status().isNoContent());
        assertThat(commissionRateResolver.resolve(SELLER_ID, categoryId)).isEqualTo(NEW_RATE); // 신규 주문부터 적용

        // 기존 정산 헤더·품목 스냅샷·order_item 스냅샷 전부 불변
        assertThat(jdbc.queryForObject("SELECT fee_amount FROM settlement WHERE id = ?", Long.class, originalId)).isEqualTo(expectedFee);
        assertThat(jdbc.queryForObject("SELECT commission_rate FROM order_item WHERE id = ?", Integer.class, ORDER_ITEM_ID))
                .isEqualTo(SNAPSHOT_RATE);

        // PENDING 재생성도 order_item 스냅샷만 읽으므로 fee 동일
        mockMvc.perform(post(SETTLEMENT_URL + "/" + originalId + "/regenerate").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"율 변경 후 재생성 검증\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedOnly").value(false))
                .andExpect(jsonPath("$.feeAmount").value(expectedFee));
        Long regeneratedId = jdbc.queryForObject("SELECT id FROM settlement WHERE seller_id = ?", Long.class, SELLER_ID);
        assertThat(jdbc.queryForObject("SELECT commission_rate FROM settlement_item WHERE settlement_id = ?", Integer.class,
                regeneratedId)).isEqualTo(SNAPSHOT_RATE);
    }

    @Test
    @DisplayName("T9 정렬: 전체 배열로 순서 반영(이 테스트 행 2건만 자리 교환) / 누락·중복·미존재 id 400·순서 불변")
    void reorder() throws Exception {
        long categoryA = createCategory(NAME_A, 990);
        long categoryB = createCategory(NAME_B, 991);
        List<Long> original = currentOrder();
        assertThat(original).containsSubsequence(categoryA, categoryB);

        List<Long> swapped = new ArrayList<>(original);
        int indexA = swapped.indexOf(categoryA);
        int indexB = swapped.indexOf(categoryB);
        swapped.set(indexA, categoryB);
        swapped.set(indexB, categoryA);
        mockMvc.perform(patch(URL + "/order").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(swapped)))
                .andExpect(status().isNoContent());
        assertThat(currentOrder()).isEqualTo(swapped);
        assertThat(count("SELECT COUNT(*) FROM category WHERE id=? AND sort_order=?", categoryA, indexB)).isEqualTo(1);

        // 누락(categoryB 제외)·중복·미존재 → 400·순서 불변
        List<Long> missing = new ArrayList<>(swapped);
        missing.remove(categoryB);
        List<Long> duplicated = new ArrayList<>(swapped);
        duplicated.set(duplicated.indexOf(categoryB), categoryA);
        List<Long> unknown = new ArrayList<>(swapped);
        unknown.set(unknown.indexOf(categoryB), 999_999_999L);
        for (List<Long> invalid : List.of(missing, duplicated, unknown)) {
            mockMvc.perform(patch(URL + "/order").headers(authHeaders.admin(ADMIN_ID))
                            .contentType(MediaType.APPLICATION_JSON).content(orderBody(invalid)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
            assertThat(currentOrder()).isEqualTo(swapped);
        }
        mockMvc.perform(patch(URL + "/order").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"categoryIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 원래 순서로 복원(다른 테스트·라이브 데이터 순서 보존)
        mockMvc.perform(patch(URL + "/order").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(original)))
                .andExpect(status().isNoContent());
        assertThat(currentOrder()).isEqualTo(original);
    }

    // ---------- helpers ----------

    private long createCategory(String displayName, int sortOrder) throws Exception {
        MvcResult result = mockMvc.perform(post(URL).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"" + displayName + "\",\"sortOrder\":" + sortOrder + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("categoryId").asLong();
    }

    private static String updateBody(String displayName, int sortOrder, Integer commissionRate, String reason) {
        return "{\"displayName\":\"" + displayName + "\",\"sortOrder\":" + sortOrder
                + ",\"commissionRate\":" + commissionRate
                + ",\"reason\":" + (reason == null ? "null" : "\"" + reason + "\"") + "}";
    }

    private static String orderBody(List<Long> ids) {
        return "{\"categoryIds\":" + ids + "}";
    }

    private JsonNode listItems() throws Exception {
        MvcResult result = mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("items");
    }

    private List<Long> currentOrder() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (JsonNode row : listItems()) {
            ids.add(row.get("categoryId").asLong());
        }
        return ids;
    }

    private static JsonNode findRow(JsonNode items, long categoryId) {
        for (JsonNode row : items) {
            if (row.get("categoryId").asLong() == categoryId) {
                return row;
            }
        }
        return null;
    }

    private static int indexOf(JsonNode items, long categoryId) {
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).get("categoryId").asLong() == categoryId) {
                return index;
            }
        }
        return -1;
    }

    private int auditCount(String action, long categoryId) {
        return count("SELECT COUNT(*) FROM audit_log WHERE target_type='CATEGORY' AND action=? AND target_id=?", action, categoryId);
    }

    private void seedSeller() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                // commission_rate NULL = 셀러 개별 계약 없음 → 카테고리율이 실효(3단 판정 2단계)
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, commission_rate, created_at, updated_at) "
                                + "VALUES (?, ?, '트랙89C셀러', '대표', 'ACTIVE', NULL, NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "C89SLR"));
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedProduct(long productId, long categoryId, boolean deleted) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, "
                                + "created_at, updated_at, deleted_at) VALUES (?, ?, ?, ?, '트랙89C상품', 'SALE', ?, NOW(6), NOW(6), "
                                + (deleted ? "NOW(6)" : "NULL") + ")",
                        productId, pid("prd_", "C89P" + (productId - PRODUCT_ID)), SELLER_ID, categoryId, ITEM_PRICE);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    // confirmed_at은 집계 쿼리와 같은 Hibernate 바인딩 경로로 넣는다(AdminSettlementControllerIntegrationTest 트랩 정합).
    private void seedConfirmedOrderItem() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, "
                                + "shipping_fee, created_at, updated_at) VALUES (?, ?, 1, ?, 'CONFIRMED', ?, 0, 0, NOW(6), NOW(6))",
                        ORDER_ID, pid("ord_", "C89ORD"), "C89-" + ORDER_ID, ITEM_PRICE);
                entityManager.createNativeQuery(
                        "INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, "
                        + "unit_price, total_price, commission_rate, item_status, confirmed_at, created_at, updated_at, product_name) "
                        + "VALUES (?1, ?2, ?3, ?4, 1, ?5, 1, ?6, ?6, ?7, 'CONFIRMED', ?8, NOW(6), NOW(6), '트랙89C상품')")
                        .setParameter(1, ORDER_ITEM_ID)
                        .setParameter(2, pid("oit_", "C89OI1"))
                        .setParameter(3, ORDER_ID)
                        .setParameter(4, PRODUCT_ID)
                        .setParameter(5, SELLER_ID)
                        .setParameter(6, ITEM_PRICE)
                        .setParameter(7, SNAPSHOT_RATE)
                        .setParameter(8, LocalDateTime.of(2026, 6, 15, 12, 0, 0))
                        .executeUpdate();
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM audit_log WHERE target_type = 'SETTLEMENT' AND target_id IN "
                        + "(SELECT id FROM settlement WHERE seller_id = ?)", SELLER_ID);
                jdbc.update("DELETE FROM settlement_item WHERE settlement_id IN (SELECT id FROM settlement WHERE seller_id = ?)",
                        SELLER_ID);
                jdbc.update("DELETE FROM settlement WHERE seller_id = ?", SELLER_ID);
                jdbc.update("DELETE FROM order_item WHERE id = ?", ORDER_ITEM_ID);
                jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
                jdbc.update("DELETE FROM product WHERE seller_id = ?", SELLER_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM audit_log WHERE target_type = 'CATEGORY' AND target_id IN "
                        + "(SELECT id FROM category WHERE display_name IN (?, ?, ?, ?))", NAME_A, NAME_B, NAME_RENAMED, NAME_LINKED);
                jdbc.update("DELETE FROM category WHERE display_name IN (?, ?, ?, ?)", NAME_A, NAME_B, NAME_RENAMED, NAME_LINKED);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private int count(String sql, Object... args) {
        Integer result = jdbc.queryForObject(sql, Integer.class, args);
        return result == null ? 0 : result;
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
