package com.zslab.mall.product.controller;

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
import com.zslab.mall.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 상품 관리 API E2E 통합 테스트(Track 76·실 MariaDB). 목록 필터/정렬/페이징·쿼리 수·상세·등록·수정·이미지·variant·수동품절·
 * 일괄·삭제(409)·상품명 스냅샷 불변·비-ADMIN 403을 HTTP 경로로 검증한다(AdminProductControllerIntegrationTest 패턴:
 * 클래스 @Transactional 없음·시드/정리는 TransactionTemplate + FK_CHECKS 토글·검증은 JdbcTemplate).
 *
 * <p>시드 상품 5건: P1(SALE·재고 5·기간 무제한) P2(SALE·재고 0) P3(PENDING) P4(STOPPED·수동품절) P5(SALE·주문 이력 보유). 셀러 2명(S1·S2·P5는 S2).
 */
@AutoConfigureMockMvc
class AdminProductManagementControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/admin/products";
    private static final long ADMIN_ID = 76000L;
    private static final long BUYER_ID = 76001L;
    private static final long SELLER_1 = 76001L;
    private static final long SELLER_2 = 76002L;
    private static final long CATEGORY_1 = 76001L;
    private static final long CATEGORY_2 = 76002L;
    private static final long ORDER_ID = 76001L;
    private static final String SELLER_1_PID = pid("slr_", "T76SELLER1");
    private static final String SELLER_2_PID = pid("slr_", "T76SELLER2");
    private static final String P1 = pid("prd_", "T76P1SALE");
    private static final String P2 = pid("prd_", "T76P2ZERO");
    private static final String P3 = pid("prd_", "T76P3PEND");
    private static final String P4 = pid("prd_", "T76P4STOP");
    private static final String P5 = pid("prd_", "T76P5ORDR");
    private static final String MISSING = pid("prd_", "T76MISSING");
    private static final int QUERY_BUDGET_FOR_LIST = 6; // 상품 페이지 + count + variant + inventory + seller + category

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
    private EntityManagerFactory entityManagerFactory;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        seed();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    // ==================== 조회 ====================

    @Test
    @DisplayName("목록 기본: 5건 전부(상태 무관)·LATEST·행 필드(재고합·soldOut·soldOutManual·공급가·판매기간)")
    void list_returnsAllStatusesWithRowFields() throws Exception {
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("sellerPublicId", SELLER_1_PID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(4))
                .andExpect(jsonPath("$.items[?(@.productPublicId == '" + P1 + "')].stockTotal").value(5))
                .andExpect(jsonPath("$.items[?(@.productPublicId == '" + P1 + "')].soldOut").value(false))
                .andExpect(jsonPath("$.items[?(@.productPublicId == '" + P1 + "')].supplyPrice").value(6000))
                .andExpect(jsonPath("$.items[?(@.productPublicId == '" + P2 + "')].soldOut").value(true))
                .andExpect(jsonPath("$.items[?(@.productPublicId == '" + P2 + "')].soldOutManual").value(false))
                .andExpect(jsonPath("$.items[?(@.productPublicId == '" + P4 + "')].soldOutManual").value(true))
                .andExpect(jsonPath("$.items[?(@.productPublicId == '" + P4 + "')].sellerName").value("셀러일"))
                .andExpect(jsonPath("$.items[?(@.productPublicId == '" + P3 + "')].status").value("PENDING"));
    }

    @Test
    @DisplayName("목록 필터: status=SALE 3건·soldOut=true 2건(재고0+수동품절)·soldOut=false 2건·categoryId·keyword(이름·public_id)")
    void list_filters() throws Exception {
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("status", "SALE"))
                .andExpect(jsonPath("$.totalCount").value(3));
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("soldOut", "true").param("sellerPublicId", SELLER_1_PID))
                .andExpect(jsonPath("$.totalCount").value(2));
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("soldOut", "false").param("sellerPublicId", SELLER_1_PID))
                .andExpect(jsonPath("$.totalCount").value(2));
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("categoryId", String.valueOf(CATEGORY_2)))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].productPublicId").value(P5));
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("keyword", "재고없음"))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].productPublicId").value(P2));
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("keyword", P3))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].productPublicId").value(P3));
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("sellerPublicId", MISSING.replace("prd_", "slr_")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SELLER_NOT_FOUND"));
    }

    @Test
    @DisplayName("목록 정렬·페이징: PRICE_ASC 첫 행 P2(1000)·NAME·size=2 hasNext true·잘못된 sort 400")
    void list_sortAndPaging() throws Exception {
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("sort", "PRICE_ASC"))
                .andExpect(jsonPath("$.items[0].productPublicId").value(P2));
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("sort", "PRICE_DESC"))
                .andExpect(jsonPath("$.items[0].productPublicId").value(P5));
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("sort", "NAME"))
                .andExpect(jsonPath("$.items[0].name").value("가판매중"));
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("size", "2").param("page", "0"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.totalCount").value(5));
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("sort", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("목록 N+1 없음: 5건 조회 SQL 수 ≤ 6(상품 페이지·count·variant·inventory·seller·category 배치)")
    void list_queryCountIsBounded() throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5));

        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(QUERY_BUDGET_FOR_LIST);
        statistics.setStatisticsEnabled(false);
    }

    @Test
    @DisplayName("상세: 기본정보·셀러·이미지(type·순서·대표·id)·옵션 그룹/값(id)·variant(재고·옵션 조합·상태)")
    void detail_returnsEditableGraph() throws Exception {
        mockMvc.perform(get(URL + "/" + P1).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productPublicId").value(P1))
                .andExpect(jsonPath("$.sellerPublicId").value(SELLER_1_PID))
                .andExpect(jsonPath("$.supplyPrice").value(6000))
                .andExpect(jsonPath("$.images.length()").value(2))
                .andExpect(jsonPath("$.images[0].imageType").value("GALLERY"))
                .andExpect(jsonPath("$.images[0].main").value(true))
                .andExpect(jsonPath("$.images[1].imageType").value("DETAIL"))
                .andExpect(jsonPath("$.optionGroups.length()").value(1))
                .andExpect(jsonPath("$.optionGroups[0].name").value("색상"))
                .andExpect(jsonPath("$.optionGroups[0].values.length()").value(1))
                .andExpect(jsonPath("$.variants.length()").value(1))
                .andExpect(jsonPath("$.variants[0].quantityAvailable").value(5))
                .andExpect(jsonPath("$.variants[0].options[0].groupName").value("색상"))
                .andExpect(jsonPath("$.variants[0].options[0].value").value("검정"));
        mockMvc.perform(get(URL + "/" + MISSING).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    @DisplayName("셀러 선택 목록: GET /api/v1/admin/sellers → 시드 셀러 2명 포함·회사명 순")
    void sellerList() throws Exception {
        mockMvc.perform(get("/api/v1/admin/sellers").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sellerPublicId == '" + SELLER_1_PID + "')].companyName").value("셀러일"))
                .andExpect(jsonPath("$[?(@.sellerPublicId == '" + SELLER_2_PID + "')].status").value("ACTIVE"));
    }

    @Test
    @DisplayName("비-ADMIN(BUYER) → 목록·등록·삭제 전부 403")
    void nonAdmin_forbidden() throws Exception {
        mockMvc.perform(get(URL).headers(authHeaders.buyer(BUYER_ID))).andExpect(status().isForbidden());
        mockMvc.perform(post(URL).headers(authHeaders.buyer(BUYER_ID))
                .contentType(MediaType.APPLICATION_JSON).content(createBody(SELLER_1_PID, "x")))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(URL + "/" + P1).headers(authHeaders.buyer(BUYER_ID))).andExpect(status().isForbidden());
    }

    // ==================== 등록·수정 ====================

    @Test
    @DisplayName("등록: 셀러 지정 + 공급가·판매기간 → 201·PENDING·supply_price/sale_*_at 저장(KST)·variant/inventory 생성·감사 로그")
    void create_persistsAdminFields() throws Exception {
        String productPublicId = readJson(mockMvc.perform(post(URL).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(SELLER_1_PID, "관리자등록상품")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.variantPublicIds.length()").value(1)))
                .get("productPublicId").asText();

        assertThat(jdbc.queryForObject("SELECT status FROM product WHERE public_id = ?", String.class, productPublicId))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT supply_price FROM product WHERE public_id = ?", Long.class, productPublicId))
                .isEqualTo(7000L);
        assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(sale_start_at, '%Y-%m-%d %H:%i') FROM product WHERE public_id = ?",
                String.class, productPublicId)).isEqualTo("2026-10-01 09:00"); // +00:00 입력 → KST 저장
        assertThat(jdbc.queryForObject("SELECT sale_end_at FROM product WHERE public_id = ?", String.class, productPublicId))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory i JOIN product_variant v ON v.id = i.variant_id "
                + "JOIN product p ON p.id = v.product_id WHERE p.public_id = ?", Long.class, productPublicId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target_type = 'PRODUCT' AND action = 'CREATE' "
                + "AND target_id = (SELECT id FROM product WHERE public_id = ?)", Long.class, productPublicId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("등록 검증: 셀러 미존재 404 SELLER_NOT_FOUND·필수값 누락 400 VALIDATION_FAILED + fieldErrors[field]")
    void create_validation() throws Exception {
        mockMvc.perform(post(URL).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(MISSING.replace("prd_", "slr_"), "x")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SELLER_NOT_FOUND"));
        mockMvc.perform(post(URL).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellerPublicId\":\"" + SELLER_1_PID + "\",\"categoryId\":" + CATEGORY_1
                                + ",\"name\":\"\",\"basePrice\":-1,\"variants\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'name')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'basePrice')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'variants')]").exists());
    }

    @Test
    @DisplayName("수정: 기본정보·공급가·판매기간 치환 → 200 상세·DB 반영 / 시작≥종료 400 / 카테고리 미존재 404")
    void update_replacesBasicInfo() throws Exception {
        mockMvc.perform(put(URL + "/" + P1).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(CATEGORY_2, "수정된이름", 12000, 9000L,
                                "2026-09-01T00:00:00+09:00", "2026-12-31T00:00:00+09:00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("수정된이름"))
                .andExpect(jsonPath("$.categoryId").value(CATEGORY_2))
                .andExpect(jsonPath("$.supplyPrice").value(9000))
                .andExpect(jsonPath("$.saleEndAt").value("2026-12-31T00:00:00+09:00"));
        assertThat(jdbc.queryForObject("SELECT base_price FROM product WHERE public_id = ?", Long.class, P1)).isEqualTo(12000L);

        mockMvc.perform(put(URL + "/" + P1).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(CATEGORY_1, "역전", 12000, null,
                                "2026-12-31T00:00:00+09:00", "2026-09-01T00:00:00+09:00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mockMvc.perform(put(URL + "/" + P1).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(99999999L, "없는카테고리", 12000, null, null, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
    }

    @Test
    @DisplayName("이미지 치환: 기존 1건 유지(순서 변경)+신규 1건 추가·미포함 1건 soft-delete·대표 2장 400·타 상품 imageId 404")
    void replaceImages() throws Exception {
        long keepImageId = jdbc.queryForObject(
                "SELECT id FROM product_image WHERE product_id = 76001 AND image_type = 'DETAIL'", Long.class);
        String body = "{\"images\":[{\"imageId\":null,\"imageUrl\":\"/api/v1/files/products/2026/09/new.jpg\",\"imageType\":\"GALLERY\",\"main\":true},"
                + "{\"imageId\":" + keepImageId + ",\"imageUrl\":\"/api/v1/files/products/2026/09/detail2.jpg\",\"imageType\":\"DETAIL\",\"main\":false}]}";
        mockMvc.perform(put(URL + "/" + P1 + "/images").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images.length()").value(2))
                .andExpect(jsonPath("$.images[0].imageUrl").value("/api/v1/files/products/2026/09/new.jpg"))
                .andExpect(jsonPath("$.images[0].main").value(true))
                .andExpect(jsonPath("$.images[1].imageId").value(keepImageId))
                .andExpect(jsonPath("$.images[1].displayOrder").value(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_image WHERE product_id = 76001 AND deleted_at IS NOT NULL",
                Long.class)).isEqualTo(1L);

        String twoMains = "{\"images\":[{\"imageUrl\":\"/api/v1/files/products/2026/09/a.jpg\",\"imageType\":\"GALLERY\",\"main\":true},"
                + "{\"imageUrl\":\"/api/v1/files/products/2026/09/b.jpg\",\"imageType\":\"GALLERY\",\"main\":true}]}";
        mockMvc.perform(put(URL + "/" + P1 + "/images").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(twoMains))
                .andExpect(status().isBadRequest());
        String foreignImage = "{\"images\":[{\"imageId\":" + keepImageId + ",\"imageUrl\":\"/api/v1/files/products/2026/09/a.jpg\",\"imageType\":\"DETAIL\",\"main\":false}]}";
        mockMvc.perform(put(URL + "/" + P2 + "/images").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(foreignImage))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_IMAGE_NOT_FOUND"));
        mockMvc.perform(put(URL + "/" + P1 + "/images").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"images\":[{\"imageUrl\":\"/api/v1/files/products/2026/09/a.jpg\",\"imageType\":\"BOGUS\",\"main\":false}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("variant 치환: 기존 메타 수정 + 신규(새 옵션값 '빨강'·초기재고 3) 추가 → 옵션값·inventory 생성 / 재요청으로 기존 soft-delete / 중복 조합 409")
    void replaceVariants() throws Exception {
        String existingVariant = pid("var_", "T76P1V0");
        long groupId = jdbc.queryForObject("SELECT id FROM product_option_group WHERE product_id = 76001", Long.class);
        String body = "{\"variants\":["
                + "{\"variantPublicId\":\"" + existingVariant + "\",\"variantCode\":\"SKU-BLACK\",\"additionalPrice\":500,"
                + "\"status\":\"STOPPED\",\"soldoutManual\":true,\"displayOrder\":0,\"initialStock\":0,\"options\":[]},"
                + "{\"variantPublicId\":null,\"variantCode\":\"SKU-RED\",\"additionalPrice\":1000,\"status\":\"SALE\","
                + "\"soldoutManual\":false,\"displayOrder\":1,\"initialStock\":3,"
                + "\"options\":[{\"optionGroupId\":" + groupId + ",\"value\":\"빨강\"}]}]}";
        mockMvc.perform(put(URL + "/" + P1 + "/variants").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants.length()").value(2))
                .andExpect(jsonPath("$.variants[0].variantCode").value("SKU-BLACK"))
                .andExpect(jsonPath("$.variants[0].status").value("STOPPED"))
                .andExpect(jsonPath("$.variants[0].soldOutManual").value(true))
                .andExpect(jsonPath("$.variants[1].options[0].value").value("빨강"))
                .andExpect(jsonPath("$.variants[1].quantityAvailable").value(3))
                .andExpect(jsonPath("$.optionGroups[0].values.length()").value(2));

        // 신규 variant만 남기면 기존 variant는 soft-delete(행은 존재·deleted_at 설정).
        String newVariantPid = jdbc.queryForObject(
                "SELECT public_id FROM product_variant WHERE product_id = 76001 AND variant_code = 'SKU-RED'", String.class);
        String keepOnlyNew = "{\"variants\":[{\"variantPublicId\":\"" + newVariantPid + "\",\"variantCode\":\"SKU-RED\","
                + "\"additionalPrice\":1000,\"status\":\"SALE\",\"soldoutManual\":false,\"displayOrder\":0,\"initialStock\":0,\"options\":[]}]}";
        mockMvc.perform(put(URL + "/" + P1 + "/variants").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(keepOnlyNew))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants.length()").value(1));
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM product_variant WHERE public_id = ?", String.class, existingVariant))
                .isNotNull();

        // 이미 존재하는 조합(빨강)으로 신규 생성 → 409
        String duplicate = "{\"variants\":[{\"variantPublicId\":\"" + newVariantPid + "\",\"variantCode\":\"SKU-RED\","
                + "\"additionalPrice\":1000,\"status\":\"SALE\",\"soldoutManual\":false,\"displayOrder\":0,\"initialStock\":0,\"options\":[]},"
                + "{\"variantCode\":\"SKU-RED2\",\"additionalPrice\":0,\"status\":\"SALE\",\"soldoutManual\":false,\"displayOrder\":1,"
                + "\"initialStock\":0,\"options\":[{\"optionGroupId\":" + groupId + ",\"value\":\"빨강\"}]}]}";
        mockMvc.perform(put(URL + "/" + P1 + "/variants").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(duplicate))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_VARIANT_OPTION_CONFLICT"));
    }

    @Test
    @DisplayName("수동 품절: PATCH soldout true → 200 soldOutManual true·DB 1 / false → 0")
    void changeSoldOut() throws Exception {
        mockMvc.perform(patch(URL + "/" + P1 + "/soldout").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"soldOut\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.soldOutManual").value(true));
        assertThat(jdbc.queryForObject("SELECT is_soldout_manual FROM product WHERE public_id = ?", Integer.class, P1)).isEqualTo(1);
        mockMvc.perform(patch(URL + "/" + P1 + "/soldout").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"soldOut\":false}"))
                .andExpect(jsonPath("$.soldOutManual").value(false));
    }

    // ==================== 일괄 ====================

    @Test
    @DisplayName("일괄 상태: [P1 SALE→STOPPED 성공·P3 PENDING→SALE 승인 성공·P4 STOPPED→STOPPED 422 항목실패·미존재 404 항목실패] → 200·집계")
    void bulkStatus_partialFailure() throws Exception {
        String stopBody = "{\"productPublicIds\":[\"" + P1 + "\",\"" + P4 + "\",\"" + MISSING + "\"],\"status\":\"STOPPED\"}";
        mockMvc.perform(post(URL + "/bulk/status").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(stopBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(2))
                .andExpect(jsonPath("$.results[0].success").value(true))
                .andExpect(jsonPath("$.results[1].code").value("PRODUCT_INVALID_STATE"))
                .andExpect(jsonPath("$.results[2].code").value("PRODUCT_NOT_FOUND"));
        assertThat(productStatus(P1)).isEqualTo("STOPPED");

        String saleBody = "{\"productPublicIds\":[\"" + P1 + "\",\"" + P3 + "\"],\"status\":\"SALE\"}";
        mockMvc.perform(post(URL + "/bulk/status").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(saleBody))
                .andExpect(jsonPath("$.successCount").value(2));
        assertThat(productStatus(P1)).isEqualTo("SALE");
        assertThat(productStatus(P3)).isEqualTo("SALE");

        mockMvc.perform(post(URL + "/bulk/status").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productPublicIds\":[\"" + P1 + "\"],\"status\":\"PENDING\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("일괄 품절: [P1·P2 true] → 2건 성공·DB 반영 / 미존재 포함 시 항목 실패")
    void bulkSoldOut() throws Exception {
        mockMvc.perform(post(URL + "/bulk/soldout").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productPublicIds\":[\"" + P1 + "\",\"" + P2 + "\",\"" + MISSING + "\"],\"soldOut\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(2))
                .andExpect(jsonPath("$.failureCount").value(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE public_id IN (?, ?) AND is_soldout_manual = 1",
                Long.class, P1, P2)).isEqualTo(2L);
    }

    // ==================== 삭제·스냅샷 ====================

    @Test
    @DisplayName("삭제: 주문 이력 없음 → 204·deleted_at 설정·관리자 상세 404·목록 제외 / 재삭제 404")
    void delete_softDeletes() throws Exception {
        mockMvc.perform(delete(URL + "/" + P2).headers(authHeaders.admin(ADMIN_ID))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM product WHERE public_id = ?", String.class, P2)).isNotNull();
        mockMvc.perform(get(URL + "/" + P2).headers(authHeaders.admin(ADMIN_ID))).andExpect(status().isNotFound());
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("keyword", P2))
                .andExpect(jsonPath("$.totalCount").value(0));
        mockMvc.perform(delete(URL + "/" + P2).headers(authHeaders.admin(ADMIN_ID))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("삭제 차단: 주문 이력 있는 P5 → 409 PRODUCT_HAS_ORDER_HISTORY·detail에 판매중지 안내·행 불변")
    void delete_blockedByOrderHistory() throws Exception {
        mockMvc.perform(delete(URL + "/" + P5).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_HAS_ORDER_HISTORY"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("판매중지")));
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM product WHERE public_id = ?", String.class, P5)).isNull();
    }

    @Test
    @DisplayName("상품명 스냅샷: P5 이름 수정 후 구매자 주문 상세 productName은 주문 시점 이름 유지(V22 order_item.product_name)")
    void productNameSnapshot_survivesRename() throws Exception {
        mockMvc.perform(put(URL + "/" + P5).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(CATEGORY_2, "이름바뀐상품", 30000, null, null, null)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/orders/" + pid("ord_", "T76ORDER1")).headers(authHeaders.buyer(BUYER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellers[0].items[0].productName").value("주문된상품"));
        mockMvc.perform(get("/api/v1/orders").headers(authHeaders.buyer(BUYER_ID)))
                .andExpect(jsonPath("$.items[0].previewTitle").value("주문된상품"));
    }

    // ==================== seed·helpers ====================
    // 모든 시드 INSERT는 바인딩 파라미터 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    private void seed() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO category (id, display_name, depth, sort_order, created_at, updated_at) "
                        + "VALUES (?, '트랙76카테고리1', 0, 0, NOW(6), NOW(6))", CATEGORY_1);
                jdbc.update("INSERT INTO category (id, display_name, depth, sort_order, created_at, updated_at) "
                        + "VALUES (?, '트랙76카테고리2', 0, 1, NOW(6), NOW(6))", CATEGORY_2);
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, commission_rate, created_at, updated_at) "
                        + "VALUES (?, ?, '셀러일', '대표', 'ACTIVE', 1000, NOW(6), NOW(6))", SELLER_1, SELLER_1_PID);
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, commission_rate, created_at, updated_at) "
                        + "VALUES (?, ?, '셀러이', '대표', 'ACTIVE', 1000, NOW(6), NOW(6))", SELLER_2, SELLER_2_PID);
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        BUYER_ID, pid("usr_", "T76BUYER"));

                seedProduct(76001L, P1, SELLER_1, CATEGORY_1, "가판매중", "SALE", 10000L, 6000L, false, "T76P1V0", 5);
                seedProduct(76002L, P2, SELLER_1, CATEGORY_1, "나재고없음", "SALE", 1000L, null, false, "T76P2V0", 0);
                seedProduct(76003L, P3, SELLER_1, CATEGORY_1, "다승인대기", "PENDING", 20000L, null, false, "T76P3V0", 5);
                seedProduct(76004L, P4, SELLER_1, CATEGORY_1, "라판매중지", "STOPPED", 15000L, null, true, "T76P4V0", 5);
                seedProduct(76005L, P5, SELLER_2, CATEGORY_2, "주문된상품", "SALE", 30000L, null, false, "T76P5V0", 5);

                // P1 이미지 2건(GALLERY 대표 + DETAIL)
                jdbc.update("INSERT INTO product_image (product_id, image_url, image_type, display_order, is_main, created_at, updated_at) "
                        + "VALUES (76001, 'https://img/p1-main', 'GALLERY', 0, 1, NOW(6), NOW(6))");
                jdbc.update("INSERT INTO product_image (product_id, image_url, image_type, display_order, is_main, created_at, updated_at) "
                        + "VALUES (76001, 'https://img/p1-detail', 'DETAIL', 1, 0, NOW(6), NOW(6))");

                // P5 주문 이력(구매자 BUYER_ID·상품명 스냅샷 '주문된상품')
                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                                + "ordered_at, created_at, updated_at) VALUES (?, ?, ?, 'T76-ORDER-1', 'PAID', 30000, 0, 0, NOW(6), NOW(6), NOW(6))",
                        ORDER_ID, pid("ord_", "T76ORDER1"), BUYER_ID);
                jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, product_name, quantity, "
                                + "unit_price, total_price, item_status, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 76005, 7600500, ?, '주문된상품', 1, 30000, 30000, 'PAID', NOW(6), NOW(6))",
                        ORDER_ID, pid("oit_", "T76ITEM1"), ORDER_ID, SELLER_2);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedProduct(long productId, String publicId, long sellerId, long categoryId, String name, String status,
            long basePrice, Long supplyPrice, boolean soldoutManual, String variantTag, int available) {
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, is_soldout_manual, base_price, "
                        + "supply_price, thumbnail_url, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "DATE_ADD(NOW(6), INTERVAL ? SECOND), NOW(6))",
                productId, publicId, sellerId, categoryId, name, status, soldoutManual ? 1 : 0, basePrice, supplyPrice,
                "https://img/" + variantTag, productId - 76000);
        long groupId = productId * 10;
        long valueId = productId * 10 + 1;
        long variantId = productId * 100;
        jdbc.update("INSERT INTO product_option_group (id, product_id, name, display_order, created_at, updated_at) "
                + "VALUES (?, ?, '색상', 0, NOW(6), NOW(6))", groupId, productId);
        jdbc.update("INSERT INTO product_option_value (id, option_group_id, value, display_order, created_at, updated_at) "
                + "VALUES (?, ?, '검정', 0, NOW(6), NOW(6))", valueId, groupId);
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 0, 'SALE', 0, 0, ?, NOW(6), NOW(6))",
                variantId, pid("var_", variantTag), productId, "SKU-" + variantTag, valueId);
        jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, created_at, updated_at) "
                + "VALUES (?, ?, ?, 0, ?, NOW(6), NOW(6))", variantId, variantId, available, available);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                List<Long> productIds = jdbc.queryForList(
                        "SELECT id FROM product WHERE seller_id IN (?, ?)", Long.class, SELLER_1, SELLER_2);
                for (Long productId : productIds) {
                    jdbc.update("DELETE FROM inventory_history WHERE inventory_id IN "
                            + "(SELECT i.id FROM inventory i JOIN product_variant v ON v.id = i.variant_id WHERE v.product_id = ?)", productId);
                    jdbc.update("DELETE FROM inventory WHERE variant_id IN (SELECT id FROM product_variant WHERE product_id = ?)", productId);
                    jdbc.update("DELETE FROM product_variant WHERE product_id = ?", productId);
                    jdbc.update("DELETE FROM product_option_value WHERE option_group_id IN "
                            + "(SELECT id FROM product_option_group WHERE product_id = ?)", productId);
                    jdbc.update("DELETE FROM product_option_group WHERE product_id = ?", productId);
                    jdbc.update("DELETE FROM product_image WHERE product_id = ?", productId);
                    jdbc.update("DELETE FROM audit_log WHERE target_type = 'PRODUCT' AND target_id = ?", productId);
                    jdbc.update("DELETE FROM product WHERE id = ?", productId);
                }
                jdbc.update("DELETE FROM order_item WHERE order_id = ?", ORDER_ID);
                jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
                jdbc.update("DELETE FROM `user` WHERE id = ?", BUYER_ID);
                jdbc.update("DELETE FROM seller WHERE id IN (?, ?)", SELLER_1, SELLER_2);
                jdbc.update("DELETE FROM category WHERE id IN (?, ?)", CATEGORY_1, CATEGORY_2);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private String productStatus(String publicId) {
        return jdbc.queryForObject("SELECT status FROM product WHERE public_id = ?", String.class, publicId);
    }

    private JsonNode readJson(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private static String createBody(String sellerPublicId, String name) {
        return "{\"sellerPublicId\":\"" + sellerPublicId + "\",\"categoryId\":" + CATEGORY_1 + ",\"name\":\"" + name + "\","
                + "\"description\":\"설명\",\"basePrice\":10000,\"supplyPrice\":7000,\"thumbnailUrl\":\"/api/v1/files/products/2026/09/new.jpg\","
                + "\"saleStartAt\":\"2026-10-01T00:00:00+00:00\",\"saleEndAt\":null,\"optionGroups\":[],"
                + "\"variants\":[{\"variantCode\":\"SKU-NEW\",\"additionalPrice\":0,\"displayOrder\":0,\"initialStock\":10,\"optionKeys\":[]}]}";
    }

    private static String updateBody(long categoryId, String name, long basePrice, Long supplyPrice, String start, String end) {
        return "{\"categoryId\":" + categoryId + ",\"name\":\"" + name + "\",\"description\":\"수정설명\",\"basePrice\":" + basePrice
                + ",\"supplyPrice\":" + supplyPrice + ",\"thumbnailUrl\":\"https://img/upd\","
                + "\"saleStartAt\":" + (start == null ? "null" : "\"" + start + "\"")
                + ",\"saleEndAt\":" + (end == null ? "null" : "\"" + end + "\"") + "}";
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
