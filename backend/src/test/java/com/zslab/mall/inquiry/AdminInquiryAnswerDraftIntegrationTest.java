package com.zslab.mall.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.productquestion.ProductQuestionFixture;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.Map;
import org.hamcrest.Matchers;
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
 * 1:1 문의 답안 초안 GET 통합 테스트(D-253 · 실 MariaDB): 카테고리 5종 템플릿 · FAQ 근거 · 첨부 주문 근거(주문·원 발송 배송 상태만) · 근거 없음 null ·
 * FAQ 후보 · 개인정보 미노출 · 404 · 인가.
 *
 * <p>FAQ 근거는 V42 초기 FAQ와 겹치지 않는 고유 토큰으로 이 대역 FAQ만 일치하게 만든다. 셀러 쿠키 거부는 같은 요청이 관리자 쿠키로 200임을 먼저 확인해
 * 엔드포인트 부재 false-green을 막는다.
 */
@AutoConfigureMockMvc
class AdminInquiryAnswerDraftIntegrationTest extends AbstractIntegrationTest {

    private static final long BAND_FROM = 10960L;
    private static final long BAND_TO = 10969L;
    private static final long BUYER = 10960L;
    private static final long ADMIN = 10961L;
    private static final long SELLER = 10962L;
    private static final long SELLER_USER = 10963L;
    private static final long PRODUCT = 10964L;
    private static final long ORDER = 10965L;
    private static final long ORDER_ITEM_SHIPPING = 10966L;
    private static final long ORDER_ITEM_DELIVERED = 10967L;
    private static final long FAQ = 10968L;
    /** 문의 id는 대역 밖 자유 값(정리는 구매자 대역 기준). */
    private static final long INQUIRY_BASE = 10970_000L;
    private static final String FAQ_TOKENS = "초안토큰알파 초안토큰베타";
    private static final String FAQ_ANSWER = "초안토큰알파 초안토큰베타 안내 답변입니다.";
    private static final String NO_MATCH_CONTENT = "zqxjvk wqpzmn";
    private static final String RECIPIENT_NAME = "초안수령인";
    private static final String RECIPIENT_PHONE = "010-9696-9696";
    private static final String ADDRESS_ROAD = "초안시 비공개로 1";
    private static final String URL = "/api/v1/admin/inquiries/%s/answer-draft";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private InquiryFixture fixture;
    private ProductQuestionFixture sellerFixture;
    private LocalDateTime base;
    private long nextInquiryId;

    @BeforeEach
    void setUp() {
        fixture = new InquiryFixture(jdbc, txManager);
        sellerFixture = new ProductQuestionFixture(jdbc, txManager);
        cleanup();
        fixture.seedUser(BUYER, "buyer10960@test.zslab");
        fixture.seedUser(ADMIN, "admin10961@test.zslab");
        sellerFixture.seedCatalog(SELLER, "ACTIVE", PRODUCT, null);
        sellerFixture.seedSellerUser(SELLER_USER, SELLER);
        fixture.withoutForeignKeys(() -> jdbc.update("INSERT INTO faq (id, category, question, answer, sort_order, visible, created_at, "
                + "updated_at) VALUES (?, 'DELIVERY', ?, ?, 999, TRUE, NOW(6), NOW(6))", FAQ, FAQ_TOKENS + " 질문", FAQ_ANSWER));
        base = LocalDateTime.now().minusDays(10);
        nextInquiryId = INQUIRY_BASE;
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("D1 카테고리 5종: 같은 FAQ 근거 → 카테고리별 인사·마무리 템플릿 + 근거 요약 1줄 · FAQ 근거 있음 = FAQ 후보 아님")
    void categoryTemplates() throws Exception {
        Map<String, String> greetings = Map.of(
                "ORDER_PAYMENT", "주문·결제 관련 문의 주셔서 감사합니다.",
                "DELIVERY", "배송 관련 문의 주셔서 감사합니다.",
                "CLAIM", "취소·반품·교환 관련 문의 주셔서 감사합니다.",
                "ACCOUNT", "회원·계정 관련 문의 주셔서 감사합니다.",
                "OTHER", "안녕하세요, 고객님. 문의 주셔서 감사합니다.");
        for (Map.Entry<String, String> entry : greetings.entrySet()) {
            String inquiryPid = insertInquiry(null, entry.getKey(), FAQ_TOKENS);
            mockMvc.perform(get(URL.formatted(inquiryPid)).with(authHeaders.admin(ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.draft").value(Matchers.startsWith("안녕하세요, 고객님.")))
                    .andExpect(jsonPath("$.draft").value(Matchers.containsString(entry.getValue())))
                    .andExpect(jsonPath("$.draft").value(Matchers.containsString("- " + FAQ_ANSWER)))
                    .andExpect(jsonPath("$.evidence.length()").value(1))
                    .andExpect(jsonPath("$.evidence[0].kind").value("FAQ"))
                    .andExpect(jsonPath("$.evidence[0].title").value(FAQ_TOKENS + " 질문"))
                    .andExpect(jsonPath("$.evidence[0].summary").value(FAQ_ANSWER))
                    .andExpect(jsonPath("$.faqCandidate").value(false));
        }
    }

    @Test
    @DisplayName("D2 주문 근거: 주문번호·주문 상태·원 발송 배송 상태만(교환 재발송·반품 회수 제외) · 주문 근거 → FAQ 순 · 수령인·연락처·주소 미노출")
    void orderEvidence_statusOnly() throws Exception {
        seedOrderWithDeliveries();
        String inquiryPid = insertInquiry(ORDER, "DELIVERY", FAQ_TOKENS);

        String body = mockMvc.perform(get(URL.formatted(inquiryPid)).with(authHeaders.admin(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence.length()").value(2))
                .andExpect(jsonPath("$.evidence[0].kind").value("ORDER"))
                .andExpect(jsonPath("$.evidence[0].title").value("주문 ORDIN" + ORDER))
                .andExpect(jsonPath("$.evidence[0].summary").value("주문 상태: 배송중 · 배송 상태: 배송중, 배송완료"))
                .andExpect(jsonPath("$.evidence[1].kind").value("FAQ"))
                .andExpect(jsonPath("$.draft").value(Matchers.containsString("- 주문 상태: 배송중 · 배송 상태: 배송중, 배송완료\n- " + FAQ_ANSWER)))
                .andExpect(jsonPath("$.faqCandidate").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(RECIPIENT_NAME, RECIPIENT_PHONE, ADDRESS_ROAD, "recipient", "phone", "address", "zonecode");
    }

    @Test
    @DisplayName("D3 근거 없음: FAQ 불일치·주문 없음 → draft null(키 유지)·근거 0·FAQ 후보 / 주문만 있음 → 초안 있음·FAQ 후보")
    void noEvidence_nullDraftAndFaqCandidate() throws Exception {
        String bare = insertInquiry(null, "OTHER", NO_MATCH_CONTENT);
        String bareBody = mockMvc.perform(get(URL.formatted(bare)).with(authHeaders.admin(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence").isEmpty())
                .andExpect(jsonPath("$.faqCandidate").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(bareBody).as("전역 non_null과 달리 draft 키를 null로 남긴다").contains("\"draft\":null");

        seedOrderWithDeliveries();
        String orderOnly = insertInquiry(ORDER, "ORDER_PAYMENT", NO_MATCH_CONTENT);
        mockMvc.perform(get(URL.formatted(orderOnly)).with(authHeaders.admin(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft").value(Matchers.containsString("주문·결제 관련")))
                .andExpect(jsonPath("$.evidence.length()").value(1))
                .andExpect(jsonPath("$.evidence[0].kind").value("ORDER"))
                .andExpect(jsonPath("$.faqCandidate").value(true));
    }

    @Test
    @DisplayName("D4 404: 없는 문의 · 삭제된 문의 → INQUIRY_NOT_FOUND")
    void notFound() throws Exception {
        String deletedPid = insertInquiry(null, "OTHER", FAQ_TOKENS);
        fixture.markInquiryDeleted(nextInquiryId - 1);
        mockMvc.perform(get(URL.formatted(deletedPid)).with(authHeaders.admin(ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));
        mockMvc.perform(get(URL.formatted(InquiryFixture.pid("inq_", "NONE"))).with(authHeaders.admin(ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));
    }

    @Test
    @DisplayName("D5 인가: 관리자 200(대조) → 무쿠키 401 · 셀러 쿠키 401 · 구매자 쿠키 401")
    void authorization() throws Exception {
        String inquiryPid = insertInquiry(null, "OTHER", FAQ_TOKENS);
        mockMvc.perform(get(URL.formatted(inquiryPid)).with(authHeaders.admin(ADMIN))).andExpect(status().isOk());
        mockMvc.perform(get(URL.formatted(inquiryPid))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL.formatted(inquiryPid)).with(authHeaders.seller(SELLER_USER))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL.formatted(inquiryPid)).with(authHeaders.buyer(BUYER))).andExpect(status().isUnauthorized());
    }

    private String insertInquiry(Long orderId, String category, String content) {
        long inquiryId = nextInquiryId++;
        return fixture.insertInquiry(inquiryId, BUYER, orderId, category, content, null, null, false, base.plusMinutes(inquiryId - INQUIRY_BASE));
    }

    /**
     * 주문(배송중) + 배송지 스냅샷 + 품목 2개의 원 발송(배송중·배송완료) + 제외 대상 2행(교환 재발송 발송준비 · 반품 회수 발송준비). 제외 행이 집계되면
     * 요약에 "발송준비"가 섞인다.
     */
    private void seedOrderWithDeliveries() {
        fixture.seedOrder(ORDER, BUYER);
        fixture.withoutForeignKeys(() -> {
            jdbc.update("UPDATE `order` SET status = 'SHIPPING' WHERE id = ?", ORDER);
            jdbc.update("INSERT INTO order_shipping_snapshot (order_id, recipient_name, recipient_phone, zonecode, address_road, created_at, "
                    + "updated_at) VALUES (?, ?, ?, '06236', ?, NOW(6), NOW(6))", ORDER, RECIPIENT_NAME, RECIPIENT_PHONE, ADDRESS_ROAD);
            insertOrderItem(ORDER_ITEM_SHIPPING, "SHIPPING");
            insertOrderItem(ORDER_ITEM_DELIVERED, "DELIVERED");
            insertDelivery(ORDER_ITEM_SHIPPING, ORDER_ITEM_SHIPPING, "OUTBOUND", "SHIPPING", null);
            insertDelivery(ORDER_ITEM_DELIVERED, ORDER_ITEM_DELIVERED, "OUTBOUND", "DELIVERED", null);
            insertDelivery(ORDER_ITEM_DELIVERED + 100, ORDER_ITEM_DELIVERED, "OUTBOUND", "READY", ORDER_ITEM_DELIVERED);
            insertDelivery(ORDER_ITEM_SHIPPING + 100, ORDER_ITEM_SHIPPING, "RETURN", "READY", ORDER_ITEM_SHIPPING);
        });
    }

    private void insertOrderItem(long orderItemId, String itemStatus) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, total_price, "
                + "item_status, created_at, updated_at, product_name, commission_rate) VALUES (?, ?, ?, ?, 1, ?, 1, 10000, 10000, ?, NOW(6), "
                + "NOW(6), '초안상품', 1000)", orderItemId, InquiryFixture.pid("itm_", "ADI" + orderItemId), ORDER, PRODUCT, SELLER, itemStatus);
    }

    private void insertDelivery(long deliveryId, long orderItemId, String direction, String deliveryStatus, Long claimId) {
        jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, direction, carrier, tracking_no, status, claim_id, created_at, "
                + "updated_at) VALUES (?, ?, ?, ?, 'CJ', ?, ?, ?, NOW(6), NOW(6))", deliveryId, InquiryFixture.pid("dlv_", "ADD" + deliveryId),
                orderItemId, direction, "ADT" + deliveryId, deliveryStatus, claimId);
    }

    private void cleanup() {
        fixture.withoutForeignKeys(() -> {
            jdbc.update("DELETE FROM delivery WHERE order_item_id BETWEEN ? AND ?", BAND_FROM, BAND_TO);
            jdbc.update("DELETE FROM order_item WHERE order_id BETWEEN ? AND ?", BAND_FROM, BAND_TO);
            jdbc.update("DELETE FROM order_shipping_snapshot WHERE order_id BETWEEN ? AND ?", BAND_FROM, BAND_TO);
            jdbc.update("DELETE FROM faq WHERE id BETWEEN ? AND ?", BAND_FROM, BAND_TO);
        });
        fixture.cleanup(BAND_FROM, BAND_TO);
        sellerFixture.cleanup(BAND_FROM, BAND_TO);
    }
}
