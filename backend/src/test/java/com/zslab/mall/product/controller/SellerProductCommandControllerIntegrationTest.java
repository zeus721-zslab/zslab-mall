package com.zslab.mall.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.product.controller.response.AdminProductDetailResponse;
import com.zslab.mall.product.controller.response.AdminProductSummaryResponse;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductVariant;
import com.zslab.mall.product.policy.ProductPurchasePolicy;
import com.zslab.mall.product.policy.PurchaseBlockReason;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.product.repository.ProductVariantRepository;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 셀러 상품 수정 3 API 통합 테스트(Track 90-C-2·실 MariaDB·HTTP 경유).
 *
 * <p><b>시드 그래프</b>: 셀러 A(user A·상태 파라미터)·셀러 B(user B·ACTIVE) · 카테고리 C1·C2 /
 * 셀러 A 상품 P1(SALE·"커맨드상품"·기본가 10000·<b>관리자 설정값 공급가 7000·판매기간·썸네일</b>·옵션 그룹 색상{검정·빨강·파랑(미사용)}·
 * VA1(검정·SKU-BLK·재고 10)·VA2(빨강·재고 5)·이미지 I1(GALLERY 대표)·I2(DETAIL)) + 주문 품목 OI(VA1·unit_price 10000·product_name 스냅샷) /
 * 셀러 A 상품 P3(3그룹 색상·사이즈·소재·VA3(검정,S,면) 활성·VD(빨강,S,면) soft-delete) / 셀러 B 상품 PB(VPB·이미지 IB).
 *
 * <p>시드는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}·? 바인딩(SQL injection 없음).
 */
@AutoConfigureMockMvc
class SellerProductCommandControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE_URL = "/api/v1/seller/products";

    private static final long USER_A = 9750L;
    private static final long USER_B = 9751L;
    private static final long BUYER_ID = 9752L;
    private static final long SELLER_A = 9750L;
    private static final long SELLER_B = 9751L;
    private static final long CATEGORY_1 = 9750L;
    private static final long CATEGORY_2 = 9751L;
    private static final long MISSING_CATEGORY = 9759L;
    private static final long PRODUCT_P1 = 9750L;
    private static final long PRODUCT_P3 = 9751L;
    private static final long PRODUCT_PB = 9752L;
    private static final long GROUP_COLOR = 9750L;
    private static final long GROUP_COLOR3 = 9751L;
    private static final long GROUP_SIZE3 = 9752L;
    private static final long GROUP_MATERIAL3 = 9753L;
    private static final long VALUE_BLACK = 9750L;
    private static final long VALUE_RED = 9751L;
    private static final long VALUE_BLUE = 9752L;
    private static final long VALUE_GREEN = 9757L;
    private static final long VALUE_BLACK3 = 9753L;
    private static final long VALUE_RED3 = 9754L;
    private static final long VALUE_S3 = 9755L;
    private static final long VALUE_COTTON3 = 9756L;
    private static final long VARIANT_VA1 = 9750L;
    private static final long VARIANT_VA2 = 9751L;
    private static final long VARIANT_VA3 = 9752L;
    private static final long VARIANT_VD = 9753L;
    private static final long VARIANT_VPB = 9754L;
    private static final long VARIANT_VA4 = 9755L;
    private static final long IMAGE_I1 = 9750L;
    private static final long IMAGE_I2 = 9751L;
    private static final long IMAGE_IB = 9752L;
    private static final long ORDER_ITEM_OI = 9750L;
    private static final long SUPPLY_PRICE = 7_000L;
    private static final String SALE_START_AT = "2026-01-01 00:00:00.000000";
    private static final String SALE_END_AT = "2027-01-01 00:00:00.000000";
    private static final String THUMBNAIL_URL = "/api/v1/files/products/2026/09/spc-thumb.jpg";
    private static final String I1_URL = "/api/v1/files/products/2026/09/spc-1.jpg";
    private static final String I2_URL = "/api/v1/files/products/2026/09/spc-2.jpg";
    /** 셀러 A에게 발급된 업로드 경로(products/sellers/{sellerId}/…·검토 반영 ①). 신규·URL 변경은 이 접두만 통과한다. */
    private static final String NEW_IMAGE_URL = "/api/v1/files/products/sellers/" + SELLER_A + "/2026/09/spc-new.jpg";
    /** 셀러 B에게 발급된 경로·관리자 경로(products/yyyy/MM) — 셀러 A의 신규 등록은 400. */
    private static final String OTHER_SELLER_IMAGE_URL = "/api/v1/files/products/sellers/" + SELLER_B + "/2026/09/spc-b-new.jpg";
    private static final String ADMIN_PATH_IMAGE_URL = "/api/v1/files/products/2026/09/spc-admin-new.jpg";

    private static final String P1_PID = pid("prd_", "SPCP1");
    private static final String P3_PID = pid("prd_", "SPCP3");
    private static final String PB_PID = pid("prd_", "SPCPB");
    private static final String MISSING_PID = pid("prd_", "SPCNONE");
    private static final String VA1_PID = pid("var_", "SPCVA1");
    private static final String VA2_PID = pid("var_", "SPCVA2");
    private static final String VA3_PID = pid("var_", "SPCVA3");
    private static final String VD_PID = pid("var_", "SPCVD");
    private static final String VPB_PID = pid("var_", "SPCVPB");
    private static final String VA4_PID = pid("var_", "SPCVA4");

    private static final String VALID_UPDATE_BODY = "{\"categoryId\":" + CATEGORY_2
            + ",\"name\":\"수정상품\",\"description\":\"수정 설명\",\"basePrice\":12000}";
    private static final String VALID_IMAGES_BODY = "{\"images\":[{\"imageId\":" + IMAGE_I2
            + ",\"imageUrl\":\"" + I2_URL + "\",\"imageType\":\"GALLERY\",\"main\":true}]}";
    private static final String VALID_VARIANTS_BODY = "{\"variants\":[" + variantItem(VA1_PID, "VC-BLK", "SKU-BLK", 0, "SALE", 0, 0, null) + "]}";

    /** 셀러 상세 응답 키 화이트리스트(90-C-1 SellerProductQueryControllerIntegrationTest와 동일·응답 DTO 공유). */
    private static final Set<String> DETAIL_KEYS = Set.of("productPublicId", "name", "description", "categoryId", "categoryName",
            "status", "basePrice", "thumbnailUrl", "soldoutManual", "createdAt", "updatedAt", "images", "optionGroups", "variants");
    private static final Set<String> NESTED_KEYS = Set.of("imageId", "imageUrl", "imageType", "displayOrder", "main",
            "optionGroupId", "name", "values", "optionValueId", "value", "variantPublicId", "variantCode", "sellerSku", "barcode",
            "additionalPrice", "soldoutManual", "options", "quantityOnHand", "quantityReserved", "quantityAvailable");
    private static final Set<String> SELLER_ALLOWED_KEYS = union(DETAIL_KEYS, NESTED_KEYS);
    private static final Set<String> MANUAL_FORBIDDEN_KEYS = Set.of("supplyPrice", "sellerPublicId", "sellerName", "saleStartAt",
            "saleEndAt", "sellerId", "id", "productId", "variantId");
    /** 금지 키 = (관리자 상품 응답 DTO 2종 선언 필드(중첩 포함) − 셀러 허용 키) ∪ 수동 목록(90-B-1·90-C-1 패턴). */
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
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private ProductVariantRepository productVariantRepository;

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

    // ==================== 인가·상태 가드 ====================

    @Test
    @DisplayName("T1 인가: 비인증 401 · 구매자 403 → 3 API 전부·DB 불변")
    void authorization() throws Exception {
        for (String url : List.of(BASE_URL + "/" + P1_PID, BASE_URL + "/" + P1_PID + "/images", BASE_URL + "/" + P1_PID + "/variants")) {
            String body = bodyFor(url);
            mockMvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
            mockMvc.perform(put(url).headers(authHeaders.buyer(BUYER_ID)).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
        assertProductUntouched();
    }

    @Test
    @DisplayName("T2 D-190: SUSPENDED 셀러 PUT 3종 → 403 SELLER_SUSPENDED·DB 불변")
    void suspended_returns403() throws Exception {
        cleanup();
        seedAll(SellerStatus.SUSPENDED);

        for (String url : List.of(BASE_URL + "/" + P1_PID, BASE_URL + "/" + P1_PID + "/images", BASE_URL + "/" + P1_PID + "/variants")) {
            putAs(USER_A, url, bodyFor(url), status().isForbidden())
                    .andExpect(jsonPath("$.code").value("SELLER_SUSPENDED"));
        }
        assertProductUntouched();
    }

    @ParameterizedTest(name = "{0} 셀러 PUT → 401")
    @EnumSource(value = SellerStatus.class, names = {"PENDING", "TERMINATED"})
    @DisplayName("T3 D-190: 세션 불가 상태 → 401 UNAUTHENTICATED")
    void sessionDenied_returns401(SellerStatus status) throws Exception {
        cleanup();
        seedAll(status);

        for (String url : List.of(BASE_URL + "/" + P1_PID, BASE_URL + "/" + P1_PID + "/images", BASE_URL + "/" + P1_PID + "/variants")) {
            putAs(USER_A, url, bodyFor(url), status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
        assertProductUntouched();
    }

    @Test
    @DisplayName("T4 404 은닉: 타 셀러 상품(403 아님)·미존재 publicId → 3 API 전부 PRODUCT_NOT_FOUND / 타 셀러 variantPublicId → PRODUCT_VARIANT_NOT_FOUND")
    void hiddenAs404() throws Exception {
        for (String productPublicId : List.of(PB_PID, MISSING_PID)) {
            for (String suffix : List.of("", "/images", "/variants")) {
                String url = BASE_URL + "/" + productPublicId + suffix;
                putAs(USER_A, url, bodyFor(url), status().isNotFound())
                        .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
            }
        }
        // 타 셀러 상품 PB의 variant를 자기 상품 P1 경로로 수정 시도 → variant 404(P1 소속이 아님).
        putAs(USER_A, BASE_URL + "/" + P1_PID + "/variants",
                "{\"variants\":[" + variantItem(VPB_PID, "VC-X", null, 0, "SALE", 0, 0, null) + "]}", status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_VARIANT_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE id = ?", String.class, PRODUCT_PB)).isEqualTo("타셀러상품");
        assertThat(jdbc.queryForObject("SELECT variant_code FROM product_variant WHERE id = ?", String.class, VARIANT_VPB)).isEqualTo("VC-B");
    }

    // ==================== 1. 기본정보 ====================

    @Test
    @DisplayName("T5 기본정보 수정 200: name·description·basePrice·categoryId 반영 / 공급가·판매기간·썸네일 보존(함정 회귀) / SALE 상태 가격 변경 허용 + order_item 스냅샷 불변 / status·seller 불변 / 금지 키 0")
    void updateBasicInfo_preservesAdminFieldsAndSnapshots() throws Exception {
        String body = putAs(USER_A, BASE_URL + "/" + P1_PID, VALID_UPDATE_BODY, status().isOk())
                .andExpect(jsonPath("$.productPublicId").value(P1_PID))
                .andExpect(jsonPath("$.name").value("수정상품"))
                .andExpect(jsonPath("$.description").value("수정 설명"))
                .andExpect(jsonPath("$.basePrice").value(12000))
                .andExpect(jsonPath("$.categoryId").value(CATEGORY_2))
                .andExpect(jsonPath("$.categoryName").value("커맨드카테고리2"))
                .andExpect(jsonPath("$.status").value("SALE"))
                .andExpect(jsonPath("$.thumbnailUrl").value(THUMBNAIL_URL))
                .andReturn().getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(body);
        assertThat(keysOf(root)).containsExactlyInAnyOrderElementsOf(DETAIL_KEYS);
        assertThat(allKeys(root)).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);

        Map<String, Object> product = jdbc.queryForMap("SELECT name, base_price, category_id, status, seller_id, supply_price, "
                + "thumbnail_url, sale_start_at, sale_end_at FROM product WHERE id = ?", PRODUCT_P1);
        assertThat(product.get("name")).isEqualTo("수정상품");
        assertThat(((Number) product.get("base_price")).longValue()).isEqualTo(12_000L);
        assertThat(((Number) product.get("category_id")).longValue()).isEqualTo(CATEGORY_2);
        assertThat(product.get("status")).isEqualTo("SALE");
        assertThat(((Number) product.get("seller_id")).longValue()).isEqualTo(SELLER_A);
        // 셀러가 바꿀 수 없는 관리자 설정값은 전체 치환 mutator를 거쳐도 그대로다.
        assertThat(((Number) product.get("supply_price")).longValue()).isEqualTo(SUPPLY_PRICE);
        assertThat(product.get("thumbnail_url")).isEqualTo(THUMBNAIL_URL);
        assertThat(String.valueOf(product.get("sale_start_at"))).startsWith("2026-01-01");
        assertThat(String.valueOf(product.get("sale_end_at"))).startsWith("2027-01-01");
        // 주문 시점 스냅샷은 가격·상품명 변경과 무관하다.
        Map<String, Object> orderItem = jdbc.queryForMap("SELECT unit_price, product_name FROM order_item WHERE id = ?", ORDER_ITEM_OI);
        assertThat(((Number) orderItem.get("unit_price")).longValue()).isEqualTo(10_000L);
        assertThat(orderItem.get("product_name")).isEqualTo("커맨드상품");
    }

    @Test
    @DisplayName("T6 기본정보 400·404: categoryId null / name 공백 / basePrice 음수 → 400 VALIDATION_FAILED · 카테고리 미존재 → 404 · DB 불변")
    void updateBasicInfo_validation() throws Exception {
        String url = BASE_URL + "/" + P1_PID;
        putAs(USER_A, url, "{\"name\":\"x\",\"basePrice\":1}", status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        putAs(USER_A, url, "{\"categoryId\":" + CATEGORY_1 + ",\"name\":\"  \",\"basePrice\":1}", status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        putAs(USER_A, url, "{\"categoryId\":" + CATEGORY_1 + ",\"name\":\"x\",\"basePrice\":-1}", status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        putAs(USER_A, url, "{\"categoryId\":" + MISSING_CATEGORY + ",\"name\":\"x\",\"basePrice\":1}", status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
        assertProductUntouched();
    }

    // ==================== 2. 이미지 ====================

    @Test
    @DisplayName("T7 이미지 치환 200: [I2→GALLERY 대표 order0, 신규 order1] → 미포함 I1 soft-delete·I2 메타 갱신·신규 행·thumbnail_url 대표 URL 동기화(썸네일 파일 없으면 원본)")
    void replaceImages_replacesAndSyncsThumbnail() throws Exception {
        String body = "{\"images\":["
                + "{\"imageId\":" + IMAGE_I2 + ",\"imageUrl\":\"" + I2_URL + "\",\"imageType\":\"GALLERY\",\"main\":true},"
                + "{\"imageUrl\":\"" + NEW_IMAGE_URL + "\",\"imageType\":\"DETAIL\",\"main\":false}]}";
        putAs(USER_A, BASE_URL + "/" + P1_PID + "/images", body, status().isOk())
                .andExpect(jsonPath("$.images.length()").value(2))
                .andExpect(jsonPath("$.images[0].imageId").value(IMAGE_I2))
                .andExpect(jsonPath("$.images[0].imageType").value("GALLERY"))
                .andExpect(jsonPath("$.images[0].main").value(true))
                .andExpect(jsonPath("$.images[0].displayOrder").value(0))
                .andExpect(jsonPath("$.images[1].imageUrl").value(NEW_IMAGE_URL))
                .andExpect(jsonPath("$.images[1].imageType").value("DETAIL"))
                .andExpect(jsonPath("$.images[1].displayOrder").value(1))
                .andExpect(jsonPath("$.thumbnailUrl").value(I2_URL));

        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM product_image WHERE id = ?", Boolean.class, IMAGE_I1)).isTrue();
        Map<String, Object> i2 = jdbc.queryForMap("SELECT image_type, display_order, is_main, deleted_at FROM product_image WHERE id = ?", IMAGE_I2);
        assertThat(i2.get("image_type")).isEqualTo("GALLERY");
        assertThat(((Number) i2.get("display_order")).intValue()).isZero();
        assertThat(String.valueOf(i2.get("is_main"))).isIn("1", "true"); // TINYINT(1)은 드라이버가 Boolean으로 매핑한다
        assertThat(i2.get("deleted_at")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_image WHERE product_id = ? AND image_url = ? AND deleted_at IS NULL",
                Integer.class, PRODUCT_P1, NEW_IMAGE_URL)).isEqualTo(1);
        // 썸네일 파일이 없는 URL은 thumbnailUrlFor가 원본을 돌려준다(D-174 동기화 규칙·업로드 파일 존재 시 _thumb).
        assertThat(jdbc.queryForObject("SELECT thumbnail_url FROM product WHERE id = ?", String.class, PRODUCT_P1)).isEqualTo(I2_URL);
    }

    @Test
    @DisplayName("T8 이미지 400·404: 외부 URL·타 셀러 발급 URL·관리자 경로 URL 신규 400 · 대표 2장 400 · DETAIL 대표 400 · 타 상품 imageId 404 · 관리자 발급 기존 URL 보존 편집 200 · 대표 없으면 첫 GALLERY 썸네일 · 실패 시 DB 불변")
    void replaceImages_rejections() throws Exception {
        String url = BASE_URL + "/" + P1_PID + "/images";
        String keepI1 = "{\"imageId\":" + IMAGE_I1 + ",\"imageUrl\":\"" + I1_URL + "\",\"imageType\":\"GALLERY\",\"main\":true}";
        putAs(USER_A, url, "{\"images\":[" + keepI1 + ",{\"imageUrl\":\"https://external/x.jpg\",\"imageType\":\"GALLERY\",\"main\":false}]}",
                status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        // ① 셀러 귀속: 타 셀러(B)에게 발급된 경로·관리자 경로(products/yyyy/MM)는 서버 발급이라도 셀러 A의 신규 등록 400.
        putAs(USER_A, url, "{\"images\":[" + keepI1 + ",{\"imageUrl\":\"" + OTHER_SELLER_IMAGE_URL + "\",\"imageType\":\"GALLERY\",\"main\":false}]}",
                status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        putAs(USER_A, url, "{\"images\":[" + keepI1 + ",{\"imageUrl\":\"" + ADMIN_PATH_IMAGE_URL + "\",\"imageType\":\"GALLERY\",\"main\":false}]}",
                status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        // 기존 행의 URL 변경도 본 셀러 발급 경로만(타 셀러 경로로 교체 400).
        putAs(USER_A, url, "{\"images\":[{\"imageId\":" + IMAGE_I1 + ",\"imageUrl\":\"" + OTHER_SELLER_IMAGE_URL + "\",\"imageType\":\"GALLERY\",\"main\":true}]}",
                status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        putAs(USER_A, url, "{\"images\":[" + keepI1 + ",{\"imageId\":" + IMAGE_I2 + ",\"imageUrl\":\"" + I2_URL
                + "\",\"imageType\":\"GALLERY\",\"main\":true}]}", status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        putAs(USER_A, url, "{\"images\":[{\"imageId\":" + IMAGE_I2 + ",\"imageUrl\":\"" + I2_URL + "\",\"imageType\":\"DETAIL\",\"main\":true}]}",
                status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        putAs(USER_A, url, "{\"images\":[{\"imageId\":" + IMAGE_IB + ",\"imageUrl\":\"" + I1_URL + "\",\"imageType\":\"GALLERY\",\"main\":false}]}",
                status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_IMAGE_NOT_FOUND"));
        assertProductUntouched();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_image WHERE product_id = ? AND deleted_at IS NULL", Integer.class, PRODUCT_P1))
                .isEqualTo(2);

        // ① 관리자가 등록한 기존 이미지(관리자 경로 URL·I1)를 URL 변경 없이 보존 편집 → 200(검증 생략 분기 유지)
        // ③ 대표 없이 치환 → 요청의 첫 GALLERY(I1)가 썸네일(썸네일 파일 없으면 원본 URL).
        putAs(USER_A, url, "{\"images\":[{\"imageId\":" + IMAGE_I2 + ",\"imageUrl\":\"" + I2_URL + "\",\"imageType\":\"DETAIL\",\"main\":false},"
                + "{\"imageId\":" + IMAGE_I1 + ",\"imageUrl\":\"" + I1_URL + "\",\"imageType\":\"GALLERY\",\"main\":false}]}",
                status().isOk()).andExpect(jsonPath("$.thumbnailUrl").value(I1_URL));
        assertThat(jdbc.queryForObject("SELECT thumbnail_url FROM product WHERE id = ?", String.class, PRODUCT_P1)).isEqualTo(I1_URL);
        // ③ GALLERY 0장(DETAIL만) → 썸네일 null.
        putAs(USER_A, url, "{\"images\":[{\"imageId\":" + IMAGE_I2 + ",\"imageUrl\":\"" + I2_URL + "\",\"imageType\":\"DETAIL\",\"main\":false}]}",
                status().isOk()).andExpect(jsonPath("$.thumbnailUrl").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT thumbnail_url FROM product WHERE id = ?", String.class, PRODUCT_P1)).isNull();
    }

    // ==================== 3. variant ====================

    @Test
    @DisplayName("T9 variant 메타 수정 200: VA1 HIDDEN·추가금·SKU 변경 → 목록에 없는 VA2는 살아 있음(삭제 금지)·VA1 재고 불변·ProductPurchasePolicy NOT_ON_SALE")
    void replaceVariants_updatesMetaWithoutDeleting() throws Exception {
        String body = "{\"variants\":[" + variantItem(VA1_PID, "VC-BLK2", "SKU-BLK2", 700, "HIDDEN", 3, 99, null) + "]}";
        putAs(USER_A, BASE_URL + "/" + P1_PID + "/variants", body, status().isOk())
                .andExpect(jsonPath("$.variants.length()").value(2))
                .andExpect(jsonPath("$.variants[*].variantPublicId", contains(VA2_PID, VA1_PID)))
                .andExpect(jsonPath("$.variants[1].variantCode").value("VC-BLK2"))
                .andExpect(jsonPath("$.variants[1].sellerSku").value("SKU-BLK2"))
                .andExpect(jsonPath("$.variants[1].additionalPrice").value(700))
                .andExpect(jsonPath("$.variants[1].status").value("HIDDEN"))
                .andExpect(jsonPath("$.variants[1].displayOrder").value(3))
                .andExpect(jsonPath("$.variants[1].quantityOnHand").value(10))
                .andExpect(jsonPath("$.variants[1].options[0].value").value("검정"))
                .andExpect(jsonPath("$.variants[0].status").value("SALE"));

        assertThat(jdbc.queryForObject("SELECT deleted_at FROM product_variant WHERE id = ?", String.class, VARIANT_VA2)).isNull();
        assertThat(jdbc.queryForObject("SELECT option1_value_id FROM product_variant WHERE id = ?", Long.class, VARIANT_VA1)).isEqualTo(VALUE_BLACK);
        assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM inventory WHERE variant_id = ?", Integer.class, VARIANT_VA1)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_history WHERE inventory_id = ?", Integer.class, VARIANT_VA1)).isZero();

        Product product = productRepository.findByPublicId(P1_PID).orElseThrow();
        ProductVariant hidden = productVariantRepository.findByPublicId(VA1_PID).orElseThrow();
        ProductVariant onSale = productVariantRepository.findByPublicId(VA2_PID).orElseThrow();
        assertThat(ProductPurchasePolicy.saleBlock(product, hidden, LocalDateTime.of(2026, 6, 1, 12, 0)))
                .contains(PurchaseBlockReason.NOT_ON_SALE);
        assertThat(ProductPurchasePolicy.saleBlock(product, onSale, LocalDateTime.of(2026, 6, 1, 12, 0))).isEmpty();
    }

    @Test
    @DisplayName("T10 variant 신규 추가 200: 기존 옵션값(파랑) 조합 + initialStock 3 → variant 생성·inventory 3/0/3·INBOUND 이력·응답 3행")
    void replaceVariants_createsWithInitialStock() throws Exception {
        String body = "{\"variants\":[" + variantItem(null, "VC-BLU", "SKU-BLU", 300, "SALE", 2, 3,
                "[{\"optionGroupId\":" + GROUP_COLOR + ",\"value\":\"파랑\"}]") + "]}";
        putAs(USER_A, BASE_URL + "/" + P1_PID + "/variants", body, status().isOk())
                .andExpect(jsonPath("$.variants.length()").value(3))
                .andExpect(jsonPath("$.variants[2].variantCode").value("VC-BLU"))
                .andExpect(jsonPath("$.variants[2].additionalPrice").value(300))
                .andExpect(jsonPath("$.variants[2].options[0].optionValueId").value(VALUE_BLUE))
                .andExpect(jsonPath("$.variants[2].options[0].value").value("파랑"))
                .andExpect(jsonPath("$.variants[2].quantityOnHand").value(3))
                .andExpect(jsonPath("$.variants[2].quantityAvailable").value(3));

        Long createdId = jdbc.queryForObject("SELECT id FROM product_variant WHERE product_id = ? AND variant_code = 'VC-BLU'",
                Long.class, PRODUCT_P1);
        assertThat(jdbc.queryForObject("SELECT option1_value_id FROM product_variant WHERE id = ?", Long.class, createdId)).isEqualTo(VALUE_BLUE);
        Map<String, Object> inventory = jdbc.queryForMap("SELECT quantity_on_hand, quantity_reserved, quantity_available FROM inventory "
                + "WHERE variant_id = ?", createdId);
        assertThat(((Number) inventory.get("quantity_on_hand")).intValue()).isEqualTo(3);
        assertThat(((Number) inventory.get("quantity_reserved")).intValue()).isZero();
        assertThat(((Number) inventory.get("quantity_available")).intValue()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_history h JOIN inventory i ON h.inventory_id = i.id "
                + "WHERE i.variant_id = ? AND h.change_type = 'INBOUND' AND h.quantity_delta = 3", Integer.class, createdId)).isEqualTo(1);
        // 옵션값은 새로 만들지 않는다(옵션 구조 불변).
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_option_value WHERE option_group_id = ?", Integer.class, GROUP_COLOR)).isEqualTo(4);
    }

    @Test
    @DisplayName("T11 variant 409·400: 활성 조합(검정) 중복 409 · soft-delete된 1슬롯(P1 초록)·3슬롯(P3) 조합 재등록 모두 409 · 미존재 옵션값 400 · 그룹 누락 400 · variantPublicId 중복 400 · UK 외 제약(variant_code 51자·NOT NULL) 위반은 409 아님 · 실패 시 variant·재고 무생성")
    void replaceVariants_conflictsAndInvalidOptions() throws Exception {
        String p1Url = BASE_URL + "/" + P1_PID + "/variants";
        putAs(USER_A, p1Url, "{\"variants\":[" + variantItem(null, "VC-DUP", null, 0, "SALE", 5, 1,
                "[{\"optionGroupId\":" + GROUP_COLOR + ",\"value\":\"검정\"}]") + "]}", status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_VARIANT_OPTION_CONFLICT"));
        putAs(USER_A, p1Url, "{\"variants\":[" + variantItem(null, "VC-PUR", null, 0, "SALE", 5, 1,
                "[{\"optionGroupId\":" + GROUP_COLOR + ",\"value\":\"보라\"}]") + "]}", status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        putAs(USER_A, p1Url, "{\"variants\":[" + variantItem(null, "VC-NOOPT", null, 0, "SALE", 5, 1, "[]") + "]}", status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        // ② 1슬롯 상품 P1의 soft-delete된 조합(초록·VA4)은 UK가 발동하지 않지만(option2/3 NULL) 삭제 포함 선검증이 409로 막는다.
        putAs(USER_A, p1Url, "{\"variants\":[" + variantItem(null, "VC-GRN", null, 0, "SALE", 5, 1,
                "[{\"optionGroupId\":" + GROUP_COLOR + ",\"value\":\"초록\"}]") + "]}", status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_VARIANT_OPTION_CONFLICT"));
        // ⑤ 요청 내 variantPublicId 중복 → 400 MALFORMED_REQUEST(어느 행도 수정되지 않음).
        putAs(USER_A, p1Url, "{\"variants\":[" + variantItem(VA1_PID, "VC-DUP1", null, 0, "SALE", 0, 0, null) + ","
                + variantItem(VA1_PID, "VC-DUP2", null, 0, "HIDDEN", 1, 0, null) + "]}", status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        assertThat(jdbc.queryForObject("SELECT variant_code FROM product_variant WHERE id = ?", String.class, VARIANT_VA1)).isEqualTo("VC-BLK");
        // ④ UK 외 제약 위반(variant_code 51자·VARCHAR(50) 초과·Bean Validation @Size는 50이라 400)은 409로 위장되지 않는다.
        putAs(USER_A, p1Url, "{\"variants\":[" + variantItem(VA1_PID, "V".repeat(51), null, 0, "SALE", 0, 0, null) + "]}", status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_variant WHERE product_id = ? AND deleted_at IS NULL", Integer.class, PRODUCT_P1)).isEqualTo(2);

        // P3(3그룹): soft-delete된 VD(빨강,S,면)와 같은 조합 → 삭제 포함 선검증 409(UK와 동일 결과).
        String p3Url = BASE_URL + "/" + P3_PID + "/variants";
        putAs(USER_A, p3Url, "{\"variants\":[" + variantItem(null, "VC-RSC", null, 0, "SALE", 5, 1,
                "[{\"optionGroupId\":" + GROUP_COLOR3 + ",\"value\":\"빨강\"},{\"optionGroupId\":" + GROUP_SIZE3 + ",\"value\":\"S\"},"
                + "{\"optionGroupId\":" + GROUP_MATERIAL3 + ",\"value\":\"면\"}]") + "]}", status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_VARIANT_OPTION_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_variant WHERE product_id = ?", Integer.class, PRODUCT_P3)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory WHERE variant_id IN (SELECT id FROM product_variant WHERE product_id = ?)",
                Integer.class, PRODUCT_P3)).isEqualTo(2);
        // 활성 VA3(검정,S,면)는 그대로 살아 있다.
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM product_variant WHERE id = ?", String.class, VARIANT_VA3)).isNull();
    }

    // ---------- seed·helpers ----------

    private ResultActions putAs(long userId, String url, String body, ResultMatcher expected)
            throws Exception {
        return mockMvc.perform(put(url).headers(authHeaders.seller(userId)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(expected);
    }

    private static String bodyFor(String url) {
        if (url.endsWith("/images")) {
            return VALID_IMAGES_BODY;
        }
        if (url.endsWith("/variants")) {
            return VALID_VARIANTS_BODY;
        }
        return VALID_UPDATE_BODY;
    }

    private static String variantItem(String variantPublicId, String variantCode, String sellerSku, long additionalPrice, String status,
            int displayOrder, int initialStock, String optionsJson) {
        StringBuilder json = new StringBuilder("{");
        if (variantPublicId != null) {
            json.append("\"variantPublicId\":\"").append(variantPublicId).append("\",");
        }
        json.append("\"variantCode\":\"").append(variantCode).append("\",");
        if (sellerSku != null) {
            json.append("\"sellerSku\":\"").append(sellerSku).append("\",");
        }
        json.append("\"additionalPrice\":").append(additionalPrice).append(",");
        json.append("\"status\":\"").append(status).append("\",");
        json.append("\"soldoutManual\":false,");
        json.append("\"displayOrder\":").append(displayOrder).append(",");
        json.append("\"initialStock\":").append(initialStock);
        if (optionsJson != null) {
            json.append(",\"options\":").append(optionsJson);
        }
        return json.append("}").toString();
    }

    /** P1 기본정보·이미지·variant가 시드 그대로인지(실패 경로의 DB 불변 단언). */
    private void assertProductUntouched() {
        Map<String, Object> product = jdbc.queryForMap("SELECT name, base_price, category_id, status, supply_price, thumbnail_url "
                + "FROM product WHERE id = ?", PRODUCT_P1);
        assertThat(product.get("name")).isEqualTo("커맨드상품");
        assertThat(((Number) product.get("base_price")).longValue()).isEqualTo(10_000L);
        assertThat(((Number) product.get("category_id")).longValue()).isEqualTo(CATEGORY_1);
        assertThat(product.get("status")).isEqualTo("SALE");
        assertThat(((Number) product.get("supply_price")).longValue()).isEqualTo(SUPPLY_PRICE);
        assertThat(product.get("thumbnail_url")).isEqualTo(THUMBNAIL_URL);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_image WHERE product_id = ? AND deleted_at IS NULL", Integer.class, PRODUCT_P1))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT is_main FROM product_image WHERE id = ?", Integer.class, IMAGE_I1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_variant WHERE product_id = ? AND deleted_at IS NULL", Integer.class, PRODUCT_P1))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM product_variant WHERE id = ?", String.class, VARIANT_VA1)).isEqualTo("SALE");
    }

    private void seedAll(SellerStatus sellerAStatus) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedSellerWithOwner(USER_A, SELLER_A, "SPCUSA", "SPCSLA", "커맨드셀러A", sellerAStatus);
                seedSellerWithOwner(USER_B, SELLER_B, "SPCUSB", "SPCSLB", "커맨드셀러B", SellerStatus.ACTIVE);
                jdbc.update("INSERT INTO category (id, display_name, depth, sort_order, created_at, updated_at) "
                        + "VALUES (?, '커맨드카테고리1', 0, 0, NOW(6), NOW(6))", CATEGORY_1);
                jdbc.update("INSERT INTO category (id, display_name, depth, sort_order, created_at, updated_at) "
                        + "VALUES (?, '커맨드카테고리2', 0, 1, NOW(6), NOW(6))", CATEGORY_2);

                // P1: 관리자 설정값(공급가·판매기간·썸네일)이 있는 SALE 상품.
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, description, status, is_soldout_manual, "
                        + "base_price, supply_price, thumbnail_url, sale_start_at, sale_end_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, '커맨드상품', '원래 설명', 'SALE', 0, 10000, ?, ?, ?, ?, NOW(6), NOW(6))",
                        PRODUCT_P1, P1_PID, SELLER_A, CATEGORY_1, SUPPLY_PRICE, THUMBNAIL_URL, SALE_START_AT, SALE_END_AT);
                seedOptionGroup(GROUP_COLOR, PRODUCT_P1, "색상", 0);
                seedOptionValue(VALUE_BLACK, GROUP_COLOR, "검정", 0);
                seedOptionValue(VALUE_RED, GROUP_COLOR, "빨강", 1);
                seedOptionValue(VALUE_BLUE, GROUP_COLOR, "파랑", 2);
                seedOptionValue(VALUE_GREEN, GROUP_COLOR, "초록", 3);
                seedVariant(VARIANT_VA1, VA1_PID, PRODUCT_P1, "VC-BLK", "SKU-BLK", 0, VALUE_BLACK, null, null, null);
                seedVariant(VARIANT_VA2, VA2_PID, PRODUCT_P1, "VC-RED", "SKU-RED", 1, VALUE_RED, null, null, null);
                // VA4(초록)는 soft-delete — 1슬롯 조합 재생성 409 검증용(UK는 option2/3 NULL이라 발동하지 않음).
                seedVariant(VARIANT_VA4, VA4_PID, PRODUCT_P1, "VC-GRN-DEL", null, 3, VALUE_GREEN, null, null, "2026-03-03 09:00:00");
                seedInventory(VARIANT_VA1, 10);
                seedInventory(VARIANT_VA2, 5);
                seedInventory(VARIANT_VA4, 0);
                seedImage(IMAGE_I1, PRODUCT_P1, I1_URL, "GALLERY", 0, true);
                seedImage(IMAGE_I2, PRODUCT_P1, I2_URL, "DETAIL", 1, false);
                jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, product_name, quantity, "
                        + "unit_price, total_price, commission_rate, item_status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, '커맨드상품', 1, 10000, 10000, 1000, 'PAID', NOW(6), NOW(6))",
                        ORDER_ITEM_OI, pid("oit_", "SPCOI"), ORDER_ITEM_OI, PRODUCT_P1, VARIANT_VA1, SELLER_A);

                // P3: 3그룹 상품 — 활성 VA3(검정,S,면)·soft-delete VD(빨강,S,면)(uk 3슬롯 잔존 트랩).
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, is_soldout_manual, base_price, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, '삼중옵션상품', 'SALE', 0, 20000, NOW(6), NOW(6))",
                        PRODUCT_P3, P3_PID, SELLER_A, CATEGORY_1);
                seedOptionGroup(GROUP_COLOR3, PRODUCT_P3, "색상", 0);
                seedOptionGroup(GROUP_SIZE3, PRODUCT_P3, "사이즈", 1);
                seedOptionGroup(GROUP_MATERIAL3, PRODUCT_P3, "소재", 2);
                seedOptionValue(VALUE_BLACK3, GROUP_COLOR3, "검정", 0);
                seedOptionValue(VALUE_RED3, GROUP_COLOR3, "빨강", 1);
                seedOptionValue(VALUE_S3, GROUP_SIZE3, "S", 0);
                seedOptionValue(VALUE_COTTON3, GROUP_MATERIAL3, "면", 0);
                seedVariant(VARIANT_VA3, VA3_PID, PRODUCT_P3, "VC-KSC", null, 0, VALUE_BLACK3, VALUE_S3, VALUE_COTTON3, null);
                seedVariant(VARIANT_VD, VD_PID, PRODUCT_P3, "VC-RSC-DEL", null, 1, VALUE_RED3, VALUE_S3, VALUE_COTTON3, "2026-03-03 09:00:00");
                seedInventory(VARIANT_VA3, 2);
                seedInventory(VARIANT_VD, 0);

                // PB: 타 셀러 상품(변형·이미지 포함).
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, is_soldout_manual, base_price, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, '타셀러상품', 'SALE', 0, 7000, NOW(6), NOW(6))",
                        PRODUCT_PB, PB_PID, SELLER_B, CATEGORY_1);
                seedVariant(VARIANT_VPB, VPB_PID, PRODUCT_PB, "VC-B", "SKU-B", 0, VALUE_BLACK, null, null, null);
                seedInventory(VARIANT_VPB, 4);
                seedImage(IMAGE_IB, PRODUCT_PB, "/api/v1/files/products/2026/09/spc-b.jpg", "GALLERY", 0, true);
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

    private void seedOptionGroup(long id, long productId, String name, int displayOrder) {
        jdbc.update("INSERT INTO product_option_group (id, product_id, name, display_order, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, NOW(6), NOW(6))", id, productId, name, displayOrder);
    }

    private void seedOptionValue(long id, long groupId, String value, int displayOrder) {
        jdbc.update("INSERT INTO product_option_value (id, option_group_id, value, display_order, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, NOW(6), NOW(6))", id, groupId, value, displayOrder);
    }

    private void seedVariant(long id, String publicId, long productId, String variantCode, String sellerSku, int displayOrder,
            Long option1ValueId, Long option2ValueId, Long option3ValueId, String deletedAt) {
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, seller_sku, additional_price, status, "
                + "is_soldout_manual, display_order, option1_value_id, option2_value_id, option3_value_id, created_at, updated_at, deleted_at) "
                + "VALUES (?, ?, ?, ?, ?, 0, 'SALE', 0, ?, ?, ?, ?, NOW(6), NOW(6), ?)",
                id, publicId, productId, variantCode, sellerSku, displayOrder, option1ValueId, option2ValueId, option3ValueId, deletedAt);
    }

    private void seedInventory(long variantId, int onHand) {
        jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, created_at, updated_at) "
                + "VALUES (?, ?, ?, 0, ?, NOW(6), NOW(6))", variantId, variantId, onHand, onHand);
    }

    private void seedImage(long id, long productId, String imageUrl, String imageType, int displayOrder, boolean main) {
        jdbc.update("INSERT INTO product_image (id, product_id, image_url, image_type, display_order, is_main, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, NOW(6), NOW(6))", id, productId, imageUrl, imageType, displayOrder, main ? 1 : 0);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                // 신규 생성분(variant·inventory·history·image)은 상품 소속으로 지운다.
                jdbc.update("DELETE FROM inventory_history WHERE inventory_id IN (SELECT i.id FROM inventory i JOIN product_variant v "
                        + "ON i.variant_id = v.id WHERE v.product_id IN (?, ?, ?))", PRODUCT_P1, PRODUCT_P3, PRODUCT_PB);
                jdbc.update("DELETE FROM inventory WHERE variant_id IN (SELECT id FROM product_variant WHERE product_id IN (?, ?, ?))",
                        PRODUCT_P1, PRODUCT_P3, PRODUCT_PB);
                jdbc.update("DELETE FROM product_image WHERE product_id IN (?, ?, ?)", PRODUCT_P1, PRODUCT_P3, PRODUCT_PB);
                jdbc.update("DELETE FROM product_variant WHERE product_id IN (?, ?, ?)", PRODUCT_P1, PRODUCT_P3, PRODUCT_PB);
                jdbc.update("DELETE FROM product_option_value WHERE option_group_id IN (?, ?, ?, ?)",
                        GROUP_COLOR, GROUP_COLOR3, GROUP_SIZE3, GROUP_MATERIAL3);
                jdbc.update("DELETE FROM product_option_group WHERE id IN (?, ?, ?, ?)", GROUP_COLOR, GROUP_COLOR3, GROUP_SIZE3, GROUP_MATERIAL3);
                jdbc.update("DELETE FROM order_item WHERE id = ?", ORDER_ITEM_OI);
                jdbc.update("DELETE FROM product WHERE id IN (?, ?, ?)", PRODUCT_P1, PRODUCT_P3, PRODUCT_PB);
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

    /** record 컴포넌트명을 모으고, 컴포넌트 타입이 record이거나 List&lt;record&gt;면 재귀한다(90-B-1 패턴). */
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
