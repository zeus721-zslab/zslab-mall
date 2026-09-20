package com.zslab.mall.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.product.controller.response.AdminProductDetailResponse;
import com.zslab.mall.product.controller.response.AdminProductSummaryResponse;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 셀러 상품 목록·상세 통합 테스트(Track 90-C-1·실 MariaDB·HTTP 경유).
 *
 * <p><b>시드 그래프</b>: 셀러 A(user A·상태 파라미터)·셀러 B(user B·ACTIVE) · 카테고리 C1·C2 /
 * 셀러 A 상품 — P1(SALE·"셀러상품 알파"·C1·2026-01·옵션 그룹 색상{검정·빨강}·variant VA1(검정·SKU-BLK·재고 10/2/8)·VA2(빨강·0/0/0)·
 * VA3(soft-delete)·이미지 GALLERY 대표 + DETAIL) · P2(PENDING·"셀러상품 베타"·C2·2026-02·단순상품 DEFAULT sentinel·VB1 5/0/5) ·
 * P3(STOPPED·"기타 감마"·C1·2026-03·VC1 3/1/2) · P4(soft-delete) / 셀러 B 상품 — PB("타셀러상품"·VPB).
 * 기대: 셀러 A 목록 = P3·P2·P1(등록일 최신순) 3행 — P4(삭제)·PB(타 셀러) 제외.
 *
 * <p>시드는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}·? 바인딩(SQL injection 없음).
 */
@AutoConfigureMockMvc
class SellerProductQueryControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String LIST_URL = "/api/v1/seller/products";

    private static final long USER_A = 9740L;
    private static final long USER_B = 9741L;
    private static final long BUYER_ID = 9742L;
    private static final long SELLER_A = 9740L;
    private static final long SELLER_B = 9741L;
    private static final long CATEGORY_1 = 9740L;
    private static final long CATEGORY_2 = 9741L;
    private static final long PRODUCT_P1 = 9740L;
    private static final long PRODUCT_P2 = 9741L;
    private static final long PRODUCT_P3 = 9742L;
    private static final long PRODUCT_P4 = 9743L;
    private static final long PRODUCT_PB = 9744L;
    private static final long GROUP_COLOR = 9740L;
    private static final long GROUP_DEFAULT = 9741L;
    private static final long VALUE_BLACK = 9740L;
    private static final long VALUE_RED = 9741L;
    private static final long VALUE_DEFAULT = 9742L;
    private static final long VARIANT_VA1 = 9740L;
    private static final long VARIANT_VA2 = 9741L;
    private static final long VARIANT_VA3 = 9742L;
    private static final long VARIANT_VB1 = 9743L;
    private static final long VARIANT_VC1 = 9744L;
    private static final long VARIANT_VPB = 9745L;
    private static final long IMAGE_MAIN = 9740L;
    private static final long IMAGE_DETAIL = 9741L;

    private static final String P1_PID = pid("prd_", "SPQP1");
    private static final String P2_PID = pid("prd_", "SPQP2");
    private static final String P3_PID = pid("prd_", "SPQP3");
    private static final String P4_PID = pid("prd_", "SPQP4");
    private static final String PB_PID = pid("prd_", "SPQPB");
    private static final String MISSING_PID = pid("prd_", "SPQNONE");
    private static final String VA1_PID = pid("var_", "SPQVA1");
    private static final String VA2_PID = pid("var_", "SPQVA2");
    private static final String VA3_PID = pid("var_", "SPQVA3");
    private static final String VB1_PID = pid("var_", "SPQVB1");
    private static final String VC1_PID = pid("var_", "SPQVC1");
    private static final String VPB_PID = pid("var_", "SPQVPB");
    private static final String KEYWORD_LIMIT_EXCEEDED = "K".repeat(51);

    /** 목록 행 키 화이트리스트 — 필드가 늘면 여기와 SellerProductSummaryResponse를 함께 바꿔야 한다. */
    private static final Set<String> SUMMARY_KEYS = Set.of("productPublicId", "name", "categoryId", "categoryName", "status",
            "basePrice", "thumbnailUrl", "variantCount", "createdAt", "updatedAt");
    private static final Set<String> DETAIL_KEYS = Set.of("productPublicId", "name", "description", "categoryId", "categoryName",
            "status", "basePrice", "thumbnailUrl", "soldoutManual", "createdAt", "updatedAt", "images", "optionGroups", "variants");
    private static final Set<String> IMAGE_KEYS = Set.of("imageId", "imageUrl", "imageType", "displayOrder", "main");
    private static final Set<String> OPTION_GROUP_KEYS = Set.of("optionGroupId", "name", "displayOrder", "values");
    private static final Set<String> OPTION_VALUE_KEYS = Set.of("optionValueId", "value", "displayOrder");
    private static final Set<String> VARIANT_KEYS = Set.of("variantPublicId", "variantCode", "sellerSku", "barcode", "additionalPrice",
            "status", "soldoutManual", "displayOrder", "options", "quantityOnHand", "quantityReserved", "quantityAvailable");
    private static final Set<String> VARIANT_OPTION_KEYS = Set.of("optionGroupId", "optionValueId", "value");
    /** 셀러 응답이 어느 층위에서든 가질 수 있는 키 전체(관리자 필드에서 뺄 허용 집합). */
    private static final Set<String> SELLER_ALLOWED_KEYS = union(SUMMARY_KEYS, DETAIL_KEYS, IMAGE_KEYS, OPTION_GROUP_KEYS,
            OPTION_VALUE_KEYS, VARIANT_KEYS, VARIANT_OPTION_KEYS);
    /** 셀러 노출 금지 수동 목록(확정 결정·관리자 DTO에서 사라져도 여기 항목은 남는다). */
    private static final Set<String> MANUAL_FORBIDDEN_KEYS = Set.of("supplyPrice", "sellerPublicId", "sellerName", "saleStartAt",
            "saleEndAt", "sellerId", "id", "productId", "variantId");
    /**
     * 셀러 노출 금지 키 = (관리자 상품 응답 DTO 2종의 선언 필드 — 중첩 record·List&lt;record&gt; 포함 — 셀러 허용 키) ∪ 수동 목록.
     * 관리자 DTO에 민감 필드가 추가되면 셀러 허용 집합에 없는 한 자동으로 금지 키가 돼 이 테스트가 알게 된다(90-B-1 패턴).
     */
    private static final Set<String> FORBIDDEN_KEYS = forbiddenKeys();

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
        cleanup();
        seedAll(SellerStatus.ACTIVE);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("T1 인가: 비인증 401 · 구매자 403 · 셀러 200(목록·상세) · 필터 응답 charset=UTF-8·한글 detail 원문")
    void authorization() throws Exception {
        // Track 91: 필터 계층(SecurityErrorHandler) 직접 응답은 charset 미지정 시 ISO-8859-1로 쓰여 한글이 '?'가 됐다 → 헤더·detail 원문 단언
        mockMvc.perform(get(LIST_URL)).andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", containsString("charset=UTF-8")))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.detail").value("인증이 필요합니다."));
        mockMvc.perform(get(LIST_URL + "/" + P1_PID)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(LIST_URL).headers(authHeaders.buyer(BUYER_ID))).andExpect(status().isForbidden())
                .andExpect(header().string("Content-Type", containsString("charset=UTF-8")))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.detail").value("접근 권한이 없습니다."));
        mockMvc.perform(get(LIST_URL + "/" + P1_PID).headers(authHeaders.buyer(BUYER_ID))).andExpect(status().isForbidden());
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A))).andExpect(status().isOk());
        mockMvc.perform(get(LIST_URL + "/" + P1_PID).headers(authHeaders.seller(USER_A))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T2 목록: 셀러 A는 자기 상품 P3·P2·P1만(등록일 최신순·삭제 P4·타 셀러 PB 제외)·variantCount는 활성 variant 수·키 화이트리스트·금지 키 0")
    void list_returnsOwnProductsOnly() throws Exception {
        String body = mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.items[*].productPublicId", contains(P3_PID, P2_PID, P1_PID)))
                .andExpect(jsonPath("$.items[2].name").value("셀러상품 알파"))
                .andExpect(jsonPath("$.items[2].categoryId").value(CATEGORY_1))
                .andExpect(jsonPath("$.items[2].categoryName").value("셀러카테고리1"))
                .andExpect(jsonPath("$.items[2].status").value("SALE"))
                .andExpect(jsonPath("$.items[2].basePrice").value(10000))
                .andExpect(jsonPath("$.items[2].thumbnailUrl").value("/api/v1/files/products/2026/09/spq-p1_thumb.jpg"))
                .andExpect(jsonPath("$.items[2].variantCount").value(2))
                .andExpect(jsonPath("$.items[1].variantCount").value(1))
                .andExpect(jsonPath("$.items[1].status").value("PENDING"))
                .andExpect(jsonPath("$.items[0].status").value("STOPPED"))
                .andReturn().getResponse().getContentAsString();

        JsonNode items = objectMapper.readTree(body).get("items");
        // NON_NULL 직렬화라 null 필드는 생략된다 → 키 집합은 화이트리스트의 부분집합·금지 키는 어느 층위에도 없어야 한다.
        for (JsonNode row : items) {
            assertThat(SUMMARY_KEYS).containsAll(keysOf(row));
            assertThat(allKeys(row)).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
            assertThat(row.toString()).doesNotContain("타셀러상품");
        }
        assertThat(keysOf(items.get(2))).containsExactlyInAnyOrderElementsOf(SUMMARY_KEYS);
        assertThat(items.get(2).get("createdAt").asText()).endsWith("+09:00");

        // 셀러 B 관점: 자기 상품 PB만.
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].productPublicId").value(PB_PID));
    }

    @Test
    @DisplayName("T3 필터·정렬: keyword 상품명 부분일치 · status · categoryId · sort NAME/PRICE_ASC/PRICE_DESC")
    void list_filtersAndSort() throws Exception {
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("keyword", "알파"))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].productPublicId").value(P1_PID));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("keyword", "셀러상품"))
                .andExpect(jsonPath("$.totalCount").value(2));
        // LIKE 와일드카드는 리터럴 매칭(escape).
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("keyword", "%"))
                .andExpect(jsonPath("$.totalCount").value(0));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("status", "PENDING"))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].productPublicId").value(P2_PID));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("categoryId", String.valueOf(CATEGORY_1)))
                .andExpect(jsonPath("$.items[*].productPublicId", contains(P3_PID, P1_PID)));
        // 상품명 오름차순: "기타 감마" < "셀러상품 베타" < "셀러상품 알파"(ㅂ < ㅇ).
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("sort", "NAME"))
                .andExpect(jsonPath("$.items[*].productPublicId", contains(P3_PID, P2_PID, P1_PID)));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("sort", "PRICE_ASC"))
                .andExpect(jsonPath("$.items[*].productPublicId", contains(P1_PID, P3_PID, P2_PID)));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("sort", "PRICE_DESC"))
                .andExpect(jsonPath("$.items[*].productPublicId", contains(P2_PID, P3_PID, P1_PID)));
    }

    @Test
    @DisplayName("T4 400: 허용 외 status·sort → MALFORMED_REQUEST · keyword 51자 → MALFORMED_REQUEST")
    void list_malformed_returns400() throws Exception {
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("status", "BOGUS"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("sort", "BOGUS"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("keyword", KEYWORD_LIMIT_EXCEEDED))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("T5 페이지네이션 경계: size=2 page 0(hasNext) · page 1(마지막) · page 9(빈) · 조건 불일치 빈 결과")
    void list_paginationBoundaries() throws Exception {
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("size", "2").param("page", "0"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[*].productPublicId", contains(P3_PID, P2_PID)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.hasNext").value(true));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productPublicId").value(P1_PID))
                .andExpect(jsonPath("$.hasNext").value(false));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("size", "2").param("page", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.hasNext").value(false));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("keyword", "없는상품"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalCount").value(0));
    }

    @Test
    @DisplayName("T6 상세 P1: 기본 필드·이미지(순서·대표·유형)·옵션 그룹/값·variant(옵션 해소·재고 3수치·삭제 VA3 제외)·키 화이트리스트·금지 키 0")
    void detail_optionProduct() throws Exception {
        String body = mockMvc.perform(get(LIST_URL + "/" + P1_PID).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productPublicId").value(P1_PID))
                .andExpect(jsonPath("$.name").value("셀러상품 알파"))
                .andExpect(jsonPath("$.description").value("알파 설명"))
                .andExpect(jsonPath("$.categoryId").value(CATEGORY_1))
                .andExpect(jsonPath("$.categoryName").value("셀러카테고리1"))
                .andExpect(jsonPath("$.status").value("SALE"))
                .andExpect(jsonPath("$.basePrice").value(10000))
                .andExpect(jsonPath("$.soldoutManual").value(false))
                .andExpect(jsonPath("$.images.length()").value(2))
                .andExpect(jsonPath("$.images[0].imageId").value(IMAGE_MAIN))
                .andExpect(jsonPath("$.images[0].imageType").value("GALLERY"))
                .andExpect(jsonPath("$.images[0].main").value(true))
                .andExpect(jsonPath("$.images[0].displayOrder").value(0))
                .andExpect(jsonPath("$.images[1].imageId").value(IMAGE_DETAIL))
                .andExpect(jsonPath("$.images[1].imageType").value("DETAIL"))
                .andExpect(jsonPath("$.images[1].main").value(false))
                .andExpect(jsonPath("$.optionGroups.length()").value(1))
                .andExpect(jsonPath("$.optionGroups[0].optionGroupId").value(GROUP_COLOR))
                .andExpect(jsonPath("$.optionGroups[0].name").value("색상"))
                .andExpect(jsonPath("$.optionGroups[0].values[*].value", contains("검정", "빨강")))
                .andExpect(jsonPath("$.variants.length()").value(2))
                .andExpect(jsonPath("$.variants[*].variantPublicId", contains(VA1_PID, VA2_PID)))
                .andExpect(jsonPath("$.variants[0].variantCode").value("VC-BLK"))
                .andExpect(jsonPath("$.variants[0].sellerSku").value("SKU-BLK"))
                .andExpect(jsonPath("$.variants[0].additionalPrice").value(500))
                .andExpect(jsonPath("$.variants[0].status").value("SALE"))
                .andExpect(jsonPath("$.variants[0].soldoutManual").value(false))
                .andExpect(jsonPath("$.variants[0].options.length()").value(1))
                .andExpect(jsonPath("$.variants[0].options[0].optionGroupId").value(GROUP_COLOR))
                .andExpect(jsonPath("$.variants[0].options[0].optionValueId").value(VALUE_BLACK))
                .andExpect(jsonPath("$.variants[0].options[0].value").value("검정"))
                .andExpect(jsonPath("$.variants[0].quantityOnHand").value(10))
                .andExpect(jsonPath("$.variants[0].quantityReserved").value(2))
                .andExpect(jsonPath("$.variants[0].quantityAvailable").value(8))
                .andExpect(jsonPath("$.variants[1].status").value("HIDDEN"))
                .andExpect(jsonPath("$.variants[1].quantityAvailable").value(0))
                .andReturn().getResponse().getContentAsString();

        JsonNode root = objectMapper.readTree(body);
        assertThat(keysOf(root)).containsExactlyInAnyOrderElementsOf(DETAIL_KEYS);
        assertThat(keysOf(root.get("images").get(0))).containsExactlyInAnyOrderElementsOf(IMAGE_KEYS);
        assertThat(keysOf(root.get("optionGroups").get(0))).containsExactlyInAnyOrderElementsOf(OPTION_GROUP_KEYS);
        assertThat(keysOf(root.get("optionGroups").get(0).get("values").get(0))).containsExactlyInAnyOrderElementsOf(OPTION_VALUE_KEYS);
        // VA1은 barcode null → NON_NULL 생략, 그 외 variant 키는 전부 존재.
        Set<String> va1Keys = new LinkedHashSet<>(VARIANT_KEYS);
        va1Keys.remove("barcode");
        assertThat(keysOf(root.get("variants").get(0))).containsExactlyInAnyOrderElementsOf(va1Keys);
        assertThat(keysOf(root.get("variants").get(0).get("options").get(0))).containsExactlyInAnyOrderElementsOf(VARIANT_OPTION_KEYS);
        assertThat(allKeys(root)).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
        assertThat(body).doesNotContain(VA3_PID);
    }

    @Test
    @DisplayName("T7 상세 P2(단순상품): DEFAULT sentinel 그룹 제외(optionGroups 빈 배열)·variant options 빈 배열·이미지 없음")
    void detail_simpleProduct() throws Exception {
        mockMvc.perform(get(LIST_URL + "/" + P2_PID).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.images.length()").value(0))
                .andExpect(jsonPath("$.optionGroups.length()").value(0))
                .andExpect(jsonPath("$.variants.length()").value(1))
                .andExpect(jsonPath("$.variants[0].variantPublicId").value(VB1_PID))
                .andExpect(jsonPath("$.variants[0].options.length()").value(0))
                .andExpect(jsonPath("$.variants[0].quantityOnHand").value(5));
    }

    @Test
    @DisplayName("T8 상세 404: 타 셀러 상품(403 아님) · 미존재 · soft-delete 상품 → PRODUCT_NOT_FOUND / 타 셀러 본인은 200")
    void detail_hiddenAs404() throws Exception {
        mockMvc.perform(get(LIST_URL + "/" + PB_PID).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        // Track 91 대조군: GlobalExceptionHandler 경유 404의 한글 detail은 MVC 컨버터가 UTF-8로 쓴다(회귀 방어)
        mockMvc.perform(get(LIST_URL + "/" + MISSING_PID).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("상품을 찾을 수 없습니다: publicId=" + MISSING_PID));
        mockMvc.perform(get(LIST_URL + "/" + P4_PID).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        mockMvc.perform(get(LIST_URL + "/" + PB_PID).headers(authHeaders.seller(USER_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("타셀러상품"))
                .andExpect(jsonPath("$.variants[0].variantPublicId").value(VPB_PID));
    }

    @Test
    @DisplayName("T9 D-190: SUSPENDED 셀러 목록·상세 200(조회 허용)")
    void suspended_returns200() throws Exception {
        cleanup();
        seedAll(SellerStatus.SUSPENDED);

        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(3));
        mockMvc.perform(get(LIST_URL + "/" + P1_PID).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0} 셀러 GET products → 401")
    @EnumSource(value = SellerStatus.class, names = {"PENDING", "TERMINATED"})
    @DisplayName("T10 D-190: 세션 불가 상태 → 401 UNAUTHENTICATED")
    void sessionDenied_returns401(SellerStatus status) throws Exception {
        cleanup();
        seedAll(status);

        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get(LIST_URL + "/" + P1_PID).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isUnauthorized());
    }

    // ---------- seed·helpers ----------

    private void seedAll(SellerStatus sellerAStatus) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedSellerWithOwner(USER_A, SELLER_A, "SPQUSA", "SPQSLA", "상품셀러A", sellerAStatus);
                seedSellerWithOwner(USER_B, SELLER_B, "SPQUSB", "SPQSLB", "상품셀러B", SellerStatus.ACTIVE);
                jdbc.update("INSERT INTO category (id, display_name, depth, sort_order, created_at, updated_at) "
                        + "VALUES (?, '셀러카테고리1', 0, 0, NOW(6), NOW(6))", CATEGORY_1);
                jdbc.update("INSERT INTO category (id, display_name, depth, sort_order, created_at, updated_at) "
                        + "VALUES (?, '셀러카테고리2', 0, 1, NOW(6), NOW(6))", CATEGORY_2);

                seedProduct(PRODUCT_P1, P1_PID, SELLER_A, CATEGORY_1, "셀러상품 알파", "알파 설명", "SALE", 10_000L,
                        "/api/v1/files/products/2026/09/spq-p1_thumb.jpg", "2026-01-01 09:00:00", null);
                seedProduct(PRODUCT_P2, P2_PID, SELLER_A, CATEGORY_2, "셀러상품 베타", null, "PENDING", 30_000L, null,
                        "2026-02-01 09:00:00", null);
                seedProduct(PRODUCT_P3, P3_PID, SELLER_A, CATEGORY_1, "기타 감마", null, "STOPPED", 20_000L, null,
                        "2026-03-01 09:00:00", null);
                seedProduct(PRODUCT_P4, P4_PID, SELLER_A, CATEGORY_1, "삭제상품", null, "SALE", 5_000L, null,
                        "2026-03-02 09:00:00", "2026-03-03 09:00:00");
                seedProduct(PRODUCT_PB, PB_PID, SELLER_B, CATEGORY_1, "타셀러상품", null, "SALE", 7_000L, null,
                        "2026-03-04 09:00:00", null);

                // P1: 옵션 그룹 색상{검정·빨강} · VA1(검정·SALE) · VA2(빨강·HIDDEN) · VA3(soft-delete).
                seedOptionGroup(GROUP_COLOR, PRODUCT_P1, "색상");
                seedOptionValue(VALUE_BLACK, GROUP_COLOR, "검정", 0);
                seedOptionValue(VALUE_RED, GROUP_COLOR, "빨강", 1);
                seedVariant(VARIANT_VA1, VA1_PID, PRODUCT_P1, "VC-BLK", "SKU-BLK", 500L, "SALE", 0, VALUE_BLACK, null);
                seedVariant(VARIANT_VA2, VA2_PID, PRODUCT_P1, "VC-RED", "SKU-RED", 0L, "HIDDEN", 1, VALUE_RED, null);
                seedVariant(VARIANT_VA3, VA3_PID, PRODUCT_P1, "VC-DEL", null, 0L, "SALE", 2, VALUE_RED, "2026-03-03 09:00:00");
                seedInventory(VARIANT_VA1, 10, 2);
                seedInventory(VARIANT_VA2, 0, 0);
                seedInventory(VARIANT_VA3, 1, 0);
                jdbc.update("INSERT INTO product_image (id, product_id, image_url, image_type, display_order, is_main, created_at, updated_at) "
                        + "VALUES (?, ?, '/api/v1/files/products/2026/09/spq-p1.jpg', 'GALLERY', 0, 1, NOW(6), NOW(6))", IMAGE_MAIN, PRODUCT_P1);
                jdbc.update("INSERT INTO product_image (id, product_id, image_url, image_type, display_order, is_main, created_at, updated_at) "
                        + "VALUES (?, ?, '/api/v1/files/products/2026/09/spq-p1-detail.jpg', 'DETAIL', 1, 0, NOW(6), NOW(6))", IMAGE_DETAIL, PRODUCT_P1);

                // P2: 단순상품(DEFAULT sentinel 그룹 1조) · VB1.
                seedOptionGroup(GROUP_DEFAULT, PRODUCT_P2, "DEFAULT");
                seedOptionValue(VALUE_DEFAULT, GROUP_DEFAULT, "DEFAULT", 0);
                seedVariant(VARIANT_VB1, VB1_PID, PRODUCT_P2, "VC-BETA", "SKU-BETA", 0L, "SALE", 0, VALUE_DEFAULT, null);
                seedInventory(VARIANT_VB1, 5, 0);

                // P3·PB: 옵션값은 DEFAULT를 공유(FK 검사 off·옵션 해소는 상품 소속 그룹 기준이라 options 빈 배열).
                seedVariant(VARIANT_VC1, VC1_PID, PRODUCT_P3, "VC-GAMMA", null, 0L, "SALE", 0, VALUE_DEFAULT, null);
                seedInventory(VARIANT_VC1, 3, 1);
                seedVariant(VARIANT_VPB, VPB_PID, PRODUCT_PB, "VC-B", "SKU-B", 0L, "SALE", 0, VALUE_DEFAULT, null);
                seedInventory(VARIANT_VPB, 4, 0);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedSellerWithOwner(long userId, long sellerId, String userTag, String sellerTag, String companyName,
            SellerStatus status) {
        jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                userId, pid("usr_", userTag));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, '대표', ?, NOW(6), NOW(6))", sellerId, pid("slr_", sellerTag), companyName, status.name());
        jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'", userId, sellerId);
    }

    private void seedProduct(long id, String publicId, long sellerId, long categoryId, String name, String description,
            String status, long basePrice, String thumbnailUrl, String createdAt, String deletedAt) {
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, description, status, is_soldout_manual, "
                + "base_price, supply_price, thumbnail_url, created_at, updated_at, deleted_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, 9999, ?, ?, ?, ?)",
                id, publicId, sellerId, categoryId, name, description, status, basePrice, thumbnailUrl, createdAt, createdAt, deletedAt);
    }

    private void seedOptionGroup(long id, long productId, String name) {
        jdbc.update("INSERT INTO product_option_group (id, product_id, name, display_order, created_at, updated_at) "
                + "VALUES (?, ?, ?, 0, NOW(6), NOW(6))", id, productId, name);
    }

    private void seedOptionValue(long id, long groupId, String value, int displayOrder) {
        jdbc.update("INSERT INTO product_option_value (id, option_group_id, value, display_order, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, NOW(6), NOW(6))", id, groupId, value, displayOrder);
    }

    private void seedVariant(long id, String publicId, long productId, String variantCode, String sellerSku, long additionalPrice,
            String status, int displayOrder, long option1ValueId, String deletedAt) {
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, seller_sku, additional_price, status, "
                + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at, deleted_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, NOW(6), NOW(6), ?)",
                id, publicId, productId, variantCode, sellerSku, additionalPrice, status, displayOrder, option1ValueId, deletedAt);
    }

    private void seedInventory(long variantId, int onHand, int reserved) {
        jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))", variantId, variantId, onHand, reserved, onHand - reserved);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM inventory WHERE id IN (?, ?, ?, ?, ?, ?)",
                        VARIANT_VA1, VARIANT_VA2, VARIANT_VA3, VARIANT_VB1, VARIANT_VC1, VARIANT_VPB);
                jdbc.update("DELETE FROM product_image WHERE id IN (?, ?)", IMAGE_MAIN, IMAGE_DETAIL);
                jdbc.update("DELETE FROM product_variant WHERE id IN (?, ?, ?, ?, ?, ?)",
                        VARIANT_VA1, VARIANT_VA2, VARIANT_VA3, VARIANT_VB1, VARIANT_VC1, VARIANT_VPB);
                jdbc.update("DELETE FROM product_option_value WHERE id IN (?, ?, ?)", VALUE_BLACK, VALUE_RED, VALUE_DEFAULT);
                jdbc.update("DELETE FROM product_option_group WHERE id IN (?, ?)", GROUP_COLOR, GROUP_DEFAULT);
                jdbc.update("DELETE FROM product WHERE id IN (?, ?, ?, ?, ?)", PRODUCT_P1, PRODUCT_P2, PRODUCT_P3, PRODUCT_P4, PRODUCT_PB);
                jdbc.update("DELETE FROM category WHERE id IN (?, ?)", CATEGORY_1, CATEGORY_2);
                jdbc.update("DELETE FROM seller_user WHERE user_id IN (?, ?)", USER_A, USER_B);
                jdbc.update("DELETE FROM seller WHERE id IN (?, ?)", SELLER_A, SELLER_B);
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?)", USER_A, USER_B);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        Set<String> result = new LinkedHashSet<>();
        for (Set<String> set : sets) {
            result.addAll(set);
        }
        return result;
    }

    private static Set<String> forbiddenKeys() {
        Set<String> adminKeys = new LinkedHashSet<>();
        collectRecordKeys(AdminProductSummaryResponse.class, adminKeys);
        collectRecordKeys(AdminProductDetailResponse.class, adminKeys);
        adminKeys.removeAll(SELLER_ALLOWED_KEYS);
        adminKeys.addAll(MANUAL_FORBIDDEN_KEYS);
        return Set.copyOf(adminKeys);
    }

    /** record 컴포넌트명을 모으고, 컴포넌트 타입이 record이거나 List&lt;record&gt;면 재귀한다(중첩 Image·OptionGroup·Variant 등). */
    private static void collectRecordKeys(Class<?> type, Set<String> into) {
        if (!type.isRecord()) {
            return;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            into.add(component.getName());
            Class<?> nested = component.getType();
            if (List.class.isAssignableFrom(nested) && component.getGenericType() instanceof ParameterizedType parameterized
                    && parameterized.getActualTypeArguments()[0] instanceof Class<?> element) {
                nested = element;
            }
            if (nested != type) {
                collectRecordKeys(nested, into);
            }
        }
    }

    private static Set<String> keysOf(JsonNode node) {
        Set<String> keys = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    /** 응답 JSON의 모든 층위(중첩 객체·배열 원소) 키 합집합 — 금지 키가 어디에도 없음을 단언한다. */
    private static Set<String> allKeys(JsonNode node) {
        Set<String> keys = new LinkedHashSet<>();
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                keys.add(entry.getKey());
                keys.addAll(allKeys(entry.getValue()));
            });
        } else if (node.isArray()) {
            node.forEach(element -> keys.addAll(allKeys(element)));
        }
        return keys;
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
