package com.zslab.mall.review;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 작성 폼 키워드 선택지 조회 통합 테스트(Track 106-1 PR2·실 MariaDB). 비로그인 공개 · 기본 세트 ∪ 상품 최상위 카테고리 세트 · 표시 순서 ·
 * 다른 카테고리 세트 제외 · 비노출 상품 404(리뷰 목록과 같은 판정)를 검증한다.
 */
@AutoConfigureMockMvc
class ReviewKeywordQueryIntegrationTest extends AbstractIntegrationTest {

    private static final long BAND_FROM = 10790L;
    private static final long BAND_TO = 10809L;
    private static final long SELLER = 10791L;
    private static final long PRODUCT = 10792L;
    private static final long VARIANT = 10793L;
    private static final long CATEGORY = 10794L;
    private static final long OTHER_SELLER = 10795L;
    private static final long OTHER_PRODUCT = 10796L;
    private static final long OTHER_VARIANT = 10797L;
    private static final long OTHER_CATEGORY = 10798L;
    private static final long KEYWORD_FIRST = 10799L;
    private static final long KEYWORD_LAST = 10800L;
    private static final long KEYWORD_OTHER_CATEGORY = 10801L;
    private static final int BASE_SET_SIZE = 6;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private ReviewFixture fixture;
    private String productPid;
    private String otherProductPid;

    @BeforeEach
    void setUp() {
        fixture = new ReviewFixture(jdbc, txManager);
        fixture.cleanup(BAND_FROM, BAND_TO);
        fixture.seedCatalog(SELLER, PRODUCT, VARIANT, CATEGORY);
        fixture.seedCatalog(OTHER_SELLER, OTHER_PRODUCT, OTHER_VARIANT, OTHER_CATEGORY);
        productPid = ReviewFixture.pid("prd_", "RVP" + PRODUCT);
        otherProductPid = ReviewFixture.pid("prd_", "RVP" + OTHER_PRODUCT);
        fixture.insertCategoryKeyword(KEYWORD_FIRST, "RVK_SIZE_FIT", CATEGORY);
        fixture.insertCategoryKeyword(KEYWORD_LAST, "RVK_SIZE_BIG", CATEGORY);
        fixture.insertCategoryKeyword(KEYWORD_OTHER_CATEGORY, "RVK_OTHER", OTHER_CATEGORY);
        // id가 가장 큰 행이라도 표시 순서가 앞이면 맨 앞에 온다(정렬이 id가 아니라 표시 순서 기준인지 대조).
        fixture.withoutForeignKeys(() -> jdbc.update("UPDATE review_keyword SET display_order = 0 WHERE id = ?", KEYWORD_FIRST));
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(BAND_FROM, BAND_TO);
    }

    @Test
    @DisplayName("K1 비로그인 → 200 · 기본 6 + 이 카테고리 2 · 표시 순서(0 → 기본 1~6 → 100) · 다른 카테고리 세트 제외 · groupCode·sortOrder")
    void keywords_unionOfBaseAndCategorySet_orderedByDisplayOrder() throws Exception {
        mockMvc.perform(get(keywordsUrl(productPid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(BASE_SET_SIZE + 2))
                .andExpect(jsonPath("$[0].code").value("RVK_SIZE_FIT"))
                .andExpect(jsonPath("$[0].groupCode").value("PRODUCT"))
                .andExpect(jsonPath("$[0].sortOrder").value(0))
                .andExpect(jsonPath("$[1].code").value("DELIVERY_FAST"))
                .andExpect(jsonPath("$[1].label").value("배송이 빨라요"))
                .andExpect(jsonPath("$[1].groupCode").value("DELIVERY"))
                .andExpect(jsonPath("$[1].sortOrder").value(1))
                .andExpect(jsonPath("$[6].code").value("WILL_REPURCHASE"))
                .andExpect(jsonPath("$[7].code").value("RVK_SIZE_BIG"))
                .andExpect(jsonPath("$[?(@.code == 'RVK_OTHER')]").isEmpty());
    }

    @Test
    @DisplayName("K2 카테고리 세트가 없는 상품 → 기본 세트 6개만")
    void keywords_productWithoutCategorySet_returnsBaseSetOnly() throws Exception {
        fixture.withoutForeignKeys(() -> jdbc.update("DELETE FROM review_keyword WHERE id = ?", KEYWORD_OTHER_CATEGORY));

        mockMvc.perform(get(keywordsUrl(otherProductPid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(BASE_SET_SIZE))
                .andExpect(jsonPath("$[0].code").value("DELIVERY_FAST"))
                .andExpect(jsonPath("$[5].code").value("WILL_REPURCHASE"));
    }

    @Test
    @DisplayName("K3 비노출 상품(HIDDEN)·판매자 비-ACTIVE·미존재 → 404 PRODUCT_NOT_FOUND / 판매중지(STOPPED) → 200")
    void keywords_unexposedProduct_returns404() throws Exception {
        // 시드 상품은 더미 category_id(FK 끔)라 행 수정도 FK를 끈 경로로 한다.
        fixture.withoutForeignKeys(() -> jdbc.update("UPDATE product SET status = 'HIDDEN' WHERE id = ?", PRODUCT));
        mockMvc.perform(get(keywordsUrl(productPid)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));

        fixture.withoutForeignKeys(() -> jdbc.update("UPDATE product SET status = 'STOPPED' WHERE id = ?", PRODUCT));
        mockMvc.perform(get(keywordsUrl(productPid))).andExpect(status().isOk());

        fixture.withoutForeignKeys(() -> jdbc.update("UPDATE seller SET status = 'SUSPENDED' WHERE id = ?", SELLER));
        mockMvc.perform(get(keywordsUrl(productPid)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));

        mockMvc.perform(get(keywordsUrl(ReviewFixture.pid("prd_", "RVNOSUCH"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    private static String keywordsUrl(String productPublicId) {
        return "/api/v1/products/" + productPublicId + "/reviews/keywords";
    }
}
