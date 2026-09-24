package com.zslab.mall.product.controller;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.support.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 구매자 카탈로그 목록 큐레이션 파라미터 통합 테스트(D-221·실 MariaDB·MockMvc). 셀러 필터(sellerPublicId)·가격 상한(maxPrice)·
 * 판매량 정렬(sort=SALES)과 기존 필터·페이징 조합을 실측한다. 픽스처는 {@link ProductCatalogControllerIntegrationTest}와 같은
 * JDBC 직접 시드(LT-02 FOREIGN_KEY_CHECKS try-finally)이며, 주문·주문품목은 결제 시각·품목 상태만 의미가 있다.
 *
 * <ul>
 *   <li>CAT_SALES 판매량: 판매많음 5 · 동률신상 2 · 판매적음 2 · 신상무판매 0 · 제외만있음 0(CANCELLED·RETURNED·ORDERED·
 *       기간 밖 결제만) · 숨김대박(HIDDEN·비노출) 50</li>
 *   <li>CAT_STATUS: 집계 상태 9종을 상품마다 1개씩(수량 1) + 그보다 최신인 무판매 대조 상품. 상태 하나라도 집계에서 빠지면
 *       그 상품이 판매 0이 돼 대조 상품 뒤로 밀린다.</li>
 *   <li>CAT_PRICE(셀러 B): 대표가 9000 · 10000 · 10500 + 가격이 낮지만 비노출인 STOPPED·판매기간 밖 상품</li>
 * </ul>
 */
@AutoConfigureMockMvc
class ProductCatalogCurationIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/products";

    private static final long SELLER_A = 46001L;
    private static final long SELLER_B = 46002L;
    private static final long SELLER_SUSPENDED = 46003L;
    private static final List<Long> SELLER_IDS = List.of(SELLER_A, SELLER_B, SELLER_SUSPENDED);
    private static final String SELLER_B_PUBLIC_ID = sellerPublicId(SELLER_B);

    private static final long CAT_SALES = 46101L;
    private static final long CAT_PRICE = 46102L;
    private static final long CAT_STATUS = 46103L;
    private static final List<Long> CATEGORY_IDS = List.of(CAT_SALES, CAT_PRICE, CAT_STATUS);

    private static final long BUYER_ID = 46901L;
    // 판매량 집계 기간(ProductCatalogService.SALES_WINDOW_DAYS 계약)과 경계 여유(요청 시각과 시드 시각 차이 흡수).
    private static final int SALES_WINDOW_DAYS = 7;
    private static final int WINDOW_MARGIN_HOURS = 1;
    private static final List<String> COUNTED_ITEM_STATUSES = List.of(
            "PAID", "PREPARING", "SHIPPING", "DELIVERED", "CONFIRMED",
            "CANCEL_REQUESTED", "RETURN_REQUESTED", "EXCHANGE_REQUESTED", "EXCHANGED");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        seedFixtures();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    // ==================== 셀러 필터 ====================

    @Test
    @DisplayName("C1 셀러 필터 — sellerPublicId 일치 노출 상품 3건만(STOPPED·판매기간 밖 제외)·응답 sellerPublicId·sellerName")
    void list_filterBySeller() throws Exception {
        mockMvc.perform(get(URL).param("sellerPublicId", SELLER_B_PUBLIC_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.items[*].sellerPublicId", hasSize(3)))
                .andExpect(jsonPath("$.items[*].sellerPublicId").value(everyItem(is(SELLER_B_PUBLIC_ID))))
                .andExpect(jsonPath("$.items[0].sellerPublicId").value(SELLER_B_PUBLIC_ID))
                .andExpect(jsonPath("$.items[0].sellerName").value("비셀러"));
    }

    @Test
    @DisplayName("C2 미존재·형식 오류·정지 셀러 — 에러 아닌 빈 목록 200(존재 여부 구분 불가)")
    void list_unknownOrInactiveSeller_returnsEmpty() throws Exception {
        for (String sellerPublicId : List.of(sellerPublicId(46999L), "abc", sellerPublicId(SELLER_SUSPENDED))) {
            mockMvc.perform(get(URL).param("sellerPublicId", sellerPublicId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalCount").value(0))
                    .andExpect(jsonPath("$.items.length()").value(0));
        }
    }

    @Test
    @DisplayName("C3 셀러 필터 빈 값 — 빈 문자열·공백은 조건 없음(keyword와 동일)")
    void list_blankSeller_ignored() throws Exception {
        for (String blank : List.of("", "   ")) {
            mockMvc.perform(get(URL).param("categoryId", String.valueOf(CAT_PRICE)).param("sellerPublicId", blank))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalCount").value(3));
        }
    }

    // ==================== 가격 상한 ====================

    @Test
    @DisplayName("C4 가격 상한 — 대표가(basePrice+MIN additional) 이하만: 9500 → 9000원 1건")
    void list_maxPrice() throws Exception {
        mockMvc.perform(get(URL).param("categoryId", String.valueOf(CAT_PRICE)).param("maxPrice", "9500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].displayPrice").value(9000));
    }

    @Test
    @DisplayName("C5 가격 상한 경계 + 셀러 조합 — 대표가 = 상한(10000) 포함·10500(추가금 반영) 제외·5000원 비노출 상품 제외")
    void list_maxPriceBoundary_withSeller() throws Exception {
        mockMvc.perform(get(URL).param("sellerPublicId", SELLER_B_PUBLIC_ID).param("maxPrice", "10000").param("sort", "PRICE_ASC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items[0].displayPrice").value(9000))
                .andExpect(jsonPath("$.items[1].displayPrice").value(10000));
    }

    @Test
    @DisplayName("C6 가격 상한 잘못된 값 — 음수·숫자 아님은 400 MALFORMED_REQUEST·0은 200 빈 목록")
    void list_maxPriceInvalid() throws Exception {
        mockMvc.perform(get(URL).param("maxPrice", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mockMvc.perform(get(URL).param("maxPrice", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mockMvc.perform(get(URL).param("categoryId", String.valueOf(CAT_PRICE)).param("maxPrice", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0));
    }

    // ==================== 판매량 정렬 ====================

    @Test
    @DisplayName("C7 판매량 정렬 + 카테고리 — 7일 밖(1시간 차)·취소·반품·미결제 제외·동률은 최신순·판매 0 포함·비노출 제외")
    void list_sortSales_withCategory() throws Exception {
        mockMvc.perform(get(URL).param("categoryId", String.valueOf(CAT_SALES)).param("sort", "SALES"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(5))
                .andExpect(jsonPath("$.items[0].name").value("판매많음"))
                .andExpect(jsonPath("$.items[1].name").value("동률신상"))
                .andExpect(jsonPath("$.items[2].name").value("판매적음"))
                .andExpect(jsonPath("$.items[3].name").value("신상무판매"))
                .andExpect(jsonPath("$.items[4].name").value("제외만있음"));
    }

    @Test
    @DisplayName("C8 판매량 집계 상태 9종 — 상태별 1개씩 모두 판매로 집계돼 더 최신인 무판매 대조 상품보다 앞선다")
    void list_sortSales_countsEveryPaidStatus() throws Exception {
        mockMvc.perform(get(URL).param("categoryId", String.valueOf(CAT_STATUS)).param("sort", "SALES"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(COUNTED_ITEM_STATUSES.size() + 1))
                .andExpect(jsonPath("$.items[" + COUNTED_ITEM_STATUSES.size() + "].name").value("무판매대조"));
    }

    @Test
    @DisplayName("C9 판매량 정렬 페이징 — size=2: page0 판매많음·동률신상 hasNext true / page2 제외만있음 hasNext false")
    void list_sortSales_paging() throws Exception {
        mockMvc.perform(get(URL).param("categoryId", String.valueOf(CAT_SALES)).param("sort", "SALES")
                        .param("size", "2").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("판매많음"))
                .andExpect(jsonPath("$.items[1].name").value("동률신상"))
                .andExpect(jsonPath("$.totalCount").value(5))
                .andExpect(jsonPath("$.hasNext").value(true));
        mockMvc.perform(get(URL).param("categoryId", String.valueOf(CAT_SALES)).param("sort", "SALES")
                        .param("size", "2").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("제외만있음"))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("C10 판매량 정렬 + 셀러 + 가격 상한 — 필터 통과분만 판매량순(9000원 100개 > 10000원 0개)")
    void list_sortSales_withSellerAndMaxPrice() throws Exception {
        mockMvc.perform(get(URL).param("sellerPublicId", SELLER_B_PUBLIC_ID).param("maxPrice", "10000").param("sort", "SALES"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items[0].name").value("비셀러싼"))
                .andExpect(jsonPath("$.items[1].name").value("비셀러경계"));
    }

    @Test
    @DisplayName("C11 기본 정렬 불변 — sort 미지정은 최신순(판매량 무관)")
    void list_defaultSortUnchanged() throws Exception {
        mockMvc.perform(get(URL).param("categoryId", String.valueOf(CAT_SALES)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("신상무판매"))
                .andExpect(jsonPath("$.items[1].name").value("동률신상"))
                .andExpect(jsonPath("$.items[2].name").value("제외만있음"))
                .andExpect(jsonPath("$.items[3].name").value("판매적음"))
                .andExpect(jsonPath("$.items[4].name").value("판매많음"));
    }

    // ==================== seed·helpers ====================

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).
    private void seedFixtures() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedSeller(SELLER_A, "에이셀러", "ACTIVE");
                seedSeller(SELLER_B, "비셀러", "ACTIVE");
                seedSeller(SELLER_SUSPENDED, "정지셀러", "SUSPENDED");
                for (Long categoryId : CATEGORY_IDS) {
                    seedCategory(categoryId);
                }

                LocalDateTime base = LocalDateTime.of(2026, 7, 1, 0, 0);
                LocalDateTime now = LocalDateTime.now();
                LocalDateTime inside = now.minusDays(SALES_WINDOW_DAYS).plusHours(WINDOW_MARGIN_HOURS);
                LocalDateTime outside = now.minusDays(SALES_WINDOW_DAYS).minusHours(WINDOW_MARGIN_HOURS);

                // CAT_SALES(셀러 A) — created_at: 판매많음 < 판매적음 < 제외만있음 < 동률신상 < 신상무판매
                seedProduct(46201L, "CMANY", SELLER_A, CAT_SALES, "판매많음", "SALE", 10000L, 0L, base, null);
                seedProduct(46202L, "CFEW", SELLER_A, CAT_SALES, "판매적음", "SALE", 10000L, 0L, base.plusDays(1), null);
                seedProduct(46203L, "CEXCL", SELLER_A, CAT_SALES, "제외만있음", "SALE", 10000L, 0L, base.plusDays(2), null);
                seedProduct(46204L, "CTIE", SELLER_A, CAT_SALES, "동률신상", "SALE", 10000L, 0L, base.plusDays(3), null);
                seedProduct(46205L, "CZERO", SELLER_A, CAT_SALES, "신상무판매", "SALE", 10000L, 0L, base.plusDays(4), null);
                seedProduct(46206L, "CHIDDEN", SELLER_A, CAT_SALES, "숨김대박", "HIDDEN", 10000L, 0L, base.plusDays(5), null);

                seedOrderItem(46301L, 46201L, SELLER_A, 3, "PAID", inside);
                seedOrderItem(46302L, 46201L, SELLER_A, 2, "CONFIRMED", inside);
                seedOrderItem(46303L, 46202L, SELLER_A, 1, "DELIVERED", inside);
                seedOrderItem(46304L, 46202L, SELLER_A, 1, "RETURN_REQUESTED", inside);
                seedOrderItem(46305L, 46204L, SELLER_A, 2, "EXCHANGED", inside);
                // 제외 대상: 취소·반품 종결·미결제 상태(기간 안) + 기간 밖 결제(집계 상태)
                seedOrderItem(46306L, 46203L, SELLER_A, 10, "CANCELLED", inside);
                seedOrderItem(46307L, 46203L, SELLER_A, 10, "RETURNED", inside);
                seedOrderItem(46308L, 46203L, SELLER_A, 10, "ORDERED", inside);
                seedOrderItem(46309L, 46203L, SELLER_A, 10, "PAID", outside);
                seedOrderItem(46310L, 46206L, SELLER_A, 50, "PAID", inside);

                // CAT_STATUS(셀러 A) — 집계 상태별 상품(수량 1) + 가장 최신 무판매 대조 상품
                for (int index = 0; index < COUNTED_ITEM_STATUSES.size(); index++) {
                    long productId = 46221L + index;
                    seedProduct(productId, "CST" + index, SELLER_A, CAT_STATUS, "상태" + COUNTED_ITEM_STATUSES.get(index), "SALE",
                            10000L, 0L, base.plusDays(index), null);
                    seedOrderItem(46321L + index, productId, SELLER_A, 1, COUNTED_ITEM_STATUSES.get(index), inside);
                }
                seedProduct(46239L, "CSTCTRL", SELLER_A, CAT_STATUS, "무판매대조", "SALE", 10000L, 0L, base.plusDays(30), null);

                // CAT_PRICE(셀러 B) — 대표가 9000 · 10000 · 10500(base 10000 + 추가 500) + 5000원 비노출 2건(STOPPED·판매기간 종료)
                seedProduct(46211L, "CCHEAP", SELLER_B, CAT_PRICE, "비셀러싼", "SALE", 9000L, 0L, base, null);
                seedProduct(46212L, "CEDGE", SELLER_B, CAT_PRICE, "비셀러경계", "SALE", 10000L, 0L, base.plusDays(1), null);
                seedProduct(46213L, "CPRICEY", SELLER_B, CAT_PRICE, "비셀러비쌈", "SALE", 10000L, 500L, base.plusDays(2), null);
                seedProduct(46214L, "CSTOP", SELLER_B, CAT_PRICE, "비셀러중지", "STOPPED", 5000L, 0L, base.plusDays(3), null);
                seedProduct(46215L, "CENDED", SELLER_B, CAT_PRICE, "비셀러기간끝", "SALE", 5000L, 0L, base.plusDays(4), base);
                seedOrderItem(46311L, 46211L, SELLER_B, 100, "PAID", inside);

                // 정지 셀러의 SALE 상품 — 셀러 필터로도 노출되지 않아야 한다.
                seedProduct(46216L, "CSUSP", SELLER_SUSPENDED, CAT_PRICE, "정지셀러상품", "SALE", 5000L, 0L, base, null);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedSeller(long id, String companyName, String status) {
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, '대표', ?, NOW(6), NOW(6))",
                id, sellerPublicId(id), companyName, status);
    }

    private void seedCategory(long id) {
        jdbc.update("INSERT INTO category (id, display_name, depth, sort_order, created_at, updated_at) "
                        + "VALUES (?, ?, 0, 0, NOW(6), NOW(6))",
                id, "큐레이션" + id);
    }

    /** 단일 variant(재고 5) 상품. 대표가 = basePrice + additionalPrice. saleEndAt이 null이면 무기한 판매. */
    private void seedProduct(long productId, String tag, long sellerId, long categoryId, String name, String status,
            long basePrice, long additionalPrice, LocalDateTime createdAt, LocalDateTime saleEndAt) {
        Timestamp created = Timestamp.valueOf(createdAt);
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, description, status, base_price, "
                        + "thumbnail_url, sale_end_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, NULL, ?, ?, NULL, ?, ?, ?)",
                productId, pad("prd_", tag), sellerId, categoryId, name, status, basePrice,
                saleEndAt == null ? null : Timestamp.valueOf(saleEndAt), created, created);
        long groupId = productId * 10;
        jdbc.update("INSERT INTO product_option_group (id, product_id, name, display_order, created_at, updated_at) "
                        + "VALUES (?, ?, '색상', 0, NOW(6), NOW(6))",
                groupId, productId);
        long valueId = groupId + 1;
        jdbc.update("INSERT INTO product_option_value (id, option_group_id, value, display_order, created_at, updated_at) "
                        + "VALUES (?, ?, '검정', 0, NOW(6), NOW(6))",
                valueId, groupId);
        long variantId = variantIdOf(productId);
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, option2_value_id, option3_value_id, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'SALE', 0, 0, ?, NULL, NULL, NOW(6), NOW(6))",
                variantId, pad("var_", tag), productId, "SKU-" + tag, additionalPrice, valueId);
        jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, "
                        + "created_at, updated_at) VALUES (?, ?, 5, 0, 5, NOW(6), NOW(6))",
                variantId, variantId);
    }

    /** 주문 1건 + 품목 1건. 주문 id = 품목 id(테스트 전용 1:1). */
    private void seedOrderItem(long id, long productId, long sellerId, int quantity, String itemStatus, LocalDateTime paidAt) {
        Timestamp paid = Timestamp.valueOf(paidAt);
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                        + "paid_at, ordered_at, created_at, updated_at) VALUES (?, ?, ?, ?, 'PAID', 0, 0, 0, ?, ?, ?, ?)",
                id, pad("ord_", "CUR" + id), BUYER_ID, "CUR-" + id, paid, paid, paid, paid);
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, product_name, quantity, "
                        + "unit_price, total_price, commission_rate, item_status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, '상품', ?, 0, 0, 1000, ?, ?, ?)",
                id, pad("oit_", "CUR" + id), id, productId, variantIdOf(productId), sellerId, quantity, itemStatus, paid, paid);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                String sellerIn = "(" + join(SELLER_IDS) + ")";
                jdbc.update("DELETE FROM `order` WHERE id IN (SELECT order_id FROM order_item WHERE seller_id IN " + sellerIn + ")");
                jdbc.update("DELETE FROM order_item WHERE seller_id IN " + sellerIn);
                jdbc.update("DELETE FROM inventory WHERE variant_id IN (SELECT id FROM product_variant WHERE product_id IN "
                        + "(SELECT id FROM product WHERE seller_id IN " + sellerIn + "))");
                jdbc.update("DELETE FROM product_variant WHERE product_id IN (SELECT id FROM product WHERE seller_id IN "
                        + sellerIn + ")");
                jdbc.update("DELETE FROM product_option_value WHERE option_group_id IN (SELECT id FROM product_option_group "
                        + "WHERE product_id IN (SELECT id FROM product WHERE seller_id IN " + sellerIn + "))");
                jdbc.update("DELETE FROM product_option_group WHERE product_id IN (SELECT id FROM product WHERE seller_id IN "
                        + sellerIn + ")");
                jdbc.update("DELETE FROM product WHERE seller_id IN " + sellerIn);
                jdbc.update("DELETE FROM seller WHERE id IN " + sellerIn);
                jdbc.update("DELETE FROM category WHERE id IN (" + join(CATEGORY_IDS) + ")");
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static long variantIdOf(long productId) {
        return productId * 100;
    }

    // SELLER_IDS·CATEGORY_IDS는 코드 상수(정적 long 리터럴)만 조인한다(외부 입력 없음·SQL injection 위험 없음).
    private static String join(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    private static String sellerPublicId(long sellerId) {
        return pad("slr_", "CURSLR" + sellerId);
    }

    private static String pad(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
