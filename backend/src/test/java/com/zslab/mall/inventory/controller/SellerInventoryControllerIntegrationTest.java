package com.zslab.mall.inventory.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import com.zslab.mall.common.security.AuthHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Seller Inventory endpoint E2E 통합 테스트(Track 27·D-112·실 MariaDB). HTTP → {@code SellerInventoryController} →
 * {@code InventoryService.markInboundBySeller/markOutboundBySeller} → {@code Inventory.adjustStock(±qty)} → DB 흐름을 실
 * 커밋·HTTP 경유로 실측한다(라이브 트랩 차단·{@link AdminInventoryControllerIntegrationTest} + Seller cross-tenant 패턴 정합).
 * 입고·출고 2 endpoint를 커버한다.
 *
 * <p><b>3P self-service 소유권(D-92 Q3·§9-6)</b>: 3홉(variantId→productId→sellerId) 소유권 검증을 {@code InventoryService}
 * 진입부가 수행한다. SELLER_A(소유)·SELLER_B(타 셀러) 2 테넌트 픽스처로 cross-tenant(T6)를 격리 실측하며, 미존재(T5)와
 * cross-tenant(T6) 모두 404 {@code PRODUCT_VARIANT_NOT_FOUND}로 존재를 은닉한다(기존 Seller 컨트롤러 full-hiding 정합).
 *
 * <p><b>γ 계승(D-105 §2 Q3)</b>: markInbound/markOutbound는 E10 InventoryAdjusted를 발행하지 않는다. {@link RecordApplicationEvents}로
 * inventory 패키지 도메인 이벤트 0건을 단언해 γ(미발행)를 회귀 잠금한다(adjustStock 선례 정합).
 *
 * <p><b>트랜잭션</b>: wrapper의 @Transactional 커밋을 JdbcTemplate 직접 조회로 검증하므로 클래스에 {@code @Transactional}을
 * 두지 않는다. 시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally)로 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class SellerInventoryControllerIntegrationTest {

    private static final long USER_ID = 9520L;
    private static final long SELLER_A = 9520L; // 상품 소유 셀러
    private static final long SELLER_B = 9521L; // 타 셀러(cross-tenant)
    // Track 36 γ Phase 3: actorId(JWT subject)를 seller_id와 다른 값으로 둔다 — user.id==seller.id 우연일치 은폐 제거.
    private static final long SELLER_A_USER = 9522L; // SELLER_A 소속 user(actorId)
    private static final long SELLER_B_USER = 9523L; // SELLER_B 소속 user(cross-tenant actorId)
    private static final long PRODUCT_ID = 9520L;
    private static final long VARIANT_ID = 9520L;
    private static final long INVENTORY_ID = 9520L; // inventory.id(variant 1:1)
    private static final long DUMMY_FK_ID = 9520L;

    private static final String VARIANT_PID = pid("var_", "SIVVAR");
    private static final String MISSING_VARIANT_PID = pid("var_", "SIVNON");
    private static final String REASON = "판매자 입고 실사";

    private static final int INITIAL_ON_HAND = 10;
    private static final int INITIAL_RESERVED = 2;
    private static final int INITIAL_AVAILABLE = 8; // on_hand - reserved 정합
    private static final int INBOUND_QTY = 5;        // T3: on_hand 15·available 13
    private static final int OUTBOUND_QTY = 3;       // T4: on_hand 7·available 5
    private static final int EXCESS_OUTBOUND_QTY = 11; // T7: on_hand 부족(projected on_hand=-1·INV-4 위반)

    private static final String INBOUND_URL = "/api/v1/seller/inventories/" + VARIANT_PID + "/mark-inbound";
    private static final String OUTBOUND_URL = "/api/v1/seller/inventories/" + VARIANT_PID + "/mark-outbound";
    private static final String MISSING_INBOUND_URL = "/api/v1/seller/inventories/" + MISSING_VARIANT_PID + "/mark-inbound";

    static final MariaDBContainer<?> MARIADB;

    static {
        MARIADB = new MariaDBContainer<>(DockerImageName.parse("mariadb:11.4"));
        MARIADB.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MARIADB::getJdbcUrl);
        registry.add("spring.datasource.username", MARIADB::getUsername);
        registry.add("spring.datasource.password", MARIADB::getPassword);
        registry.add("spring.datasource.driver-class-name", MARIADB::getDriverClassName);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private ApplicationEvents events;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        seedSellerUsers();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("T1 인증 실패: X-Seller-Id 헤더 부재 → 401 UNAUTHENTICATED·inventory 도메인 이벤트 0")
    void markInbound_missingSellerHeader_returns401() throws Exception {
        // resolve()가 variant 조회 이전 최선두에서 throw하므로 시드 불요(variantPublicId 미존재여도 401이 우선).
        mockMvc.perform(post(INBOUND_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(INBOUND_QTY, REASON)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertThat(inventoryDomainEventCount()).isZero();
    }

    @Test
    @DisplayName("T2 인증 실패: 잘못된 Bearer 토큰 → 401 UNAUTHENTICATED·이벤트 0")
    void markInbound_malformedCredential_returns401() throws Exception {
        // Track 33 P5: 잘못된 Bearer 토큰은 JwtAuthenticationFilter가 verify 실패로 예외 전파 → ExceptionTranslationFilter가 401 위임.
        mockMvc.perform(post(INBOUND_URL)
                        .header("Authorization", "Bearer not-a-valid-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(INBOUND_QTY, REASON)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertThat(inventoryDomainEventCount()).isZero();
    }

    @Test
    @DisplayName("T3 입고 성공: 소유 셀러 + 입고(+5) → 200·on_hand 15·available 13·History INBOUND 1행·reference seller/sellerId·이벤트 0")
    void markInbound_ownerSeller_returns200_persistsAndAppendsHistory() throws Exception {
        seed(() -> {
            seedCatalog();
            seedInventory(INITIAL_ON_HAND, INITIAL_RESERVED, INITIAL_AVAILABLE);
        });

        mockMvc.perform(post(INBOUND_URL)
                        .headers(authHeaders.seller(SELLER_A_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(INBOUND_QTY, REASON)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variantPublicId").value(VARIANT_PID))
                .andExpect(jsonPath("$.quantityOnHand").value(15))
                .andExpect(jsonPath("$.quantityReserved").value(2))
                .andExpect(jsonPath("$.quantityAvailable").value(13));

        assertThat(onHand()).isEqualTo(15);
        assertThat(reserved()).isEqualTo(2);
        assertThat(available()).isEqualTo(13);
        // History INBOUND 1행(quantity_delta 양수·reference_type 'seller'·reference_id=sellerId·reason 기록)
        assertThat(historyCount("INBOUND")).isEqualTo(1);
        assertThat(historyDelta("INBOUND")).isEqualTo(INBOUND_QTY);
        assertThat(historyReferenceType("INBOUND")).isEqualTo("seller");
        assertThat(historyReferenceId("INBOUND")).isEqualTo(SELLER_A);
        assertThat(historyReason("INBOUND")).isEqualTo(REASON);
        assertThat(inventoryDomainEventCount()).isZero();
    }

    @Test
    @DisplayName("T4 출고 성공: 소유 셀러 + 출고(3) → 200·on_hand 7·available 5·History OUTBOUND 1행·quantity_delta 음수·이벤트 0")
    void markOutbound_ownerSeller_returns200_decrementsAndAppendsHistory() throws Exception {
        seed(() -> {
            seedCatalog();
            seedInventory(INITIAL_ON_HAND, INITIAL_RESERVED, INITIAL_AVAILABLE);
        });

        mockMvc.perform(post(OUTBOUND_URL)
                        .headers(authHeaders.seller(SELLER_A_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(OUTBOUND_QTY, REASON)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityOnHand").value(7))
                .andExpect(jsonPath("$.quantityReserved").value(2))
                .andExpect(jsonPath("$.quantityAvailable").value(5));

        assertThat(onHand()).isEqualTo(7);
        assertThat(available()).isEqualTo(5);
        assertThat(historyCount("OUTBOUND")).isEqualTo(1);
        // 출고는 quantity_delta 음수(-qty)로 기록한다.
        assertThat(historyDelta("OUTBOUND")).isEqualTo(-OUTBOUND_QTY);
        assertThat(historyReferenceType("OUTBOUND")).isEqualTo("seller");
        assertThat(historyReferenceId("OUTBOUND")).isEqualTo(SELLER_A);
        assertThat(inventoryDomainEventCount()).isZero();
    }

    @Test
    @DisplayName("T5 미존재: 미존재 variantPublicId → 404 PRODUCT_VARIANT_NOT_FOUND·이벤트 0")
    void markInbound_unknownVariantPublicId_returns404() throws Exception {
        // 시드 없음(variant 미존재). resolve 통과 후 findByPublicId 실패 → ProductVariantNotFoundException 404.
        mockMvc.perform(post(MISSING_INBOUND_URL)
                        .headers(authHeaders.seller(SELLER_A_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(INBOUND_QTY, REASON)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_VARIANT_NOT_FOUND"));

        assertThat(inventoryDomainEventCount()).isZero();
    }

    @Test
    @DisplayName("T6 cross-tenant: 타 셀러 → 404 PRODUCT_VARIANT_NOT_FOUND(존재 은닉)·on_hand 무변경·History 0·이벤트 0")
    void markInbound_crossTenant_returns404_noChange() throws Exception {
        seed(() -> {
            seedCatalog();
            seedInventory(INITIAL_ON_HAND, INITIAL_RESERVED, INITIAL_AVAILABLE);
        });

        // variant는 존재(SELLER_A 소유)하나 SELLER_B가 조작 시도 → 3홉 소유권 위반을 미존재로 은닉(404).
        mockMvc.perform(post(INBOUND_URL)
                        .headers(authHeaders.seller(SELLER_B_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(INBOUND_QTY, REASON)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_VARIANT_NOT_FOUND"));

        assertThat(onHand()).isEqualTo(INITIAL_ON_HAND);
        assertThat(available()).isEqualTo(INITIAL_AVAILABLE);
        assertThat(historyCount("INBOUND")).isZero();
        assertThat(inventoryDomainEventCount()).isZero();
    }

    @Test
    @DisplayName("T7 실물 부족: 출고량(11)이 on_hand(10) 초과 → 422 INVENTORY_INVARIANT_VIOLATION·재고 롤백·History 0·이벤트 0")
    void markOutbound_exceedsOnHand_returns422_rollsBack() throws Exception {
        seed(() -> {
            seedCatalog();
            seedInventory(INITIAL_ON_HAND, INITIAL_RESERVED, INITIAL_AVAILABLE);
        });

        mockMvc.perform(post(OUTBOUND_URL)
                        .headers(authHeaders.seller(SELLER_A_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(EXCESS_OUTBOUND_QTY, REASON)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVENTORY_INVARIANT_VIOLATION"));

        // INV-4 위반은 mutate 이전에 throw → @Transactional 롤백: on_hand·available 불변·History 미기록.
        assertThat(onHand()).isEqualTo(INITIAL_ON_HAND);
        assertThat(available()).isEqualTo(INITIAL_AVAILABLE);
        assertThat(historyCount("OUTBOUND")).isZero();
        assertThat(inventoryDomainEventCount()).isZero();
    }

    @Test
    @DisplayName("T8 수량 오류: qty=0 → 400 MALFORMED_REQUEST·재고 무변경·History 0·이벤트 0")
    void markInbound_zeroQty_returns400() throws Exception {
        seed(() -> {
            seedCatalog();
            seedInventory(INITIAL_ON_HAND, INITIAL_RESERVED, INITIAL_AVAILABLE);
        });

        // quantity는 @Positive 미적용(형식/도메인 분리) → Service의 qty≤0 가드가 IllegalArgumentException(→400)으로 차단.
        mockMvc.perform(post(INBOUND_URL)
                        .headers(authHeaders.seller(SELLER_A_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(0, REASON)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        assertThat(onHand()).isEqualTo(INITIAL_ON_HAND);
        assertThat(historyCount("INBOUND")).isZero();
        assertThat(inventoryDomainEventCount()).isZero();
    }

    // ---------- seed·helpers (AdminInventoryControllerIntegrationTest 패턴 1:1·SELLER_A 소유) ----------

    private void seed(Runnable seedingWork) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedingWork.run();
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    private void seedCatalog() {
        jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                USER_ID, pid("usr_", "SIVUSR"));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, '트랙27셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                SELLER_A, pid("slr_", "SIVSLR"));
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, '트랙27상품', 'SALE', 10000, NOW(6), NOW(6))",
                PRODUCT_ID, pid("prd_", "SIVPRD"), SELLER_A, DUMMY_FK_ID);
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VCSIV', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                VARIANT_ID, VARIANT_PID, PRODUCT_ID, DUMMY_FK_ID);
    }

    private void seedInventory(int onHand, int reserved, int available) {
        jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))",
                INVENTORY_ID, VARIANT_ID, onHand, reserved, available);
    }

    // resolver 해소용 seller_user 실 매핑 시드(actorId≠seller_id·FK_CHECKS=0라 user/seller 행 부재 허용·role_id=SELLER_OWNER seed).
    private void seedSellerUsers() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'",
                        SELLER_A_USER, SELLER_A);
                jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'",
                        SELLER_B_USER, SELLER_B);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM seller_user WHERE user_id IN (?, ?)", SELLER_A_USER, SELLER_B_USER);
                jdbc.update("DELETE FROM inventory_history WHERE inventory_id = ?", INVENTORY_ID);
                jdbc.update("DELETE FROM inventory WHERE id = ?", INVENTORY_ID);
                jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_A);
                jdbc.update("DELETE FROM `user` WHERE id = ?", USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private String body(int quantity, String reason) {
        return "{\"quantity\":" + quantity + ",\"reason\":\"" + reason + "\"}";
    }

    /** γ 검증: inventory 패키지에서 발행된 도메인 이벤트 수(0이어야 한다·E10 미발행). */
    private long inventoryDomainEventCount() {
        return events.stream()
                .filter(event -> event.getClass().getPackageName().startsWith("com.zslab.mall.inventory"))
                .count();
    }

    private Integer onHand() {
        return jdbc.queryForObject("SELECT quantity_on_hand FROM inventory WHERE id = ?", Integer.class, INVENTORY_ID);
    }

    private Integer reserved() {
        return jdbc.queryForObject("SELECT quantity_reserved FROM inventory WHERE id = ?", Integer.class, INVENTORY_ID);
    }

    private Integer available() {
        return jdbc.queryForObject("SELECT quantity_available FROM inventory WHERE id = ?", Integer.class, INVENTORY_ID);
    }

    private int historyCount(String changeType) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_history WHERE inventory_id = ? AND change_type = ?",
                Integer.class, INVENTORY_ID, changeType);
        return count == null ? 0 : count;
    }

    private Integer historyDelta(String changeType) {
        return jdbc.queryForObject(
                "SELECT quantity_delta FROM inventory_history WHERE inventory_id = ? AND change_type = ?",
                Integer.class, INVENTORY_ID, changeType);
    }

    private String historyReferenceType(String changeType) {
        return jdbc.queryForObject(
                "SELECT reference_type FROM inventory_history WHERE inventory_id = ? AND change_type = ?",
                String.class, INVENTORY_ID, changeType);
    }

    private Long historyReferenceId(String changeType) {
        return jdbc.queryForObject(
                "SELECT reference_id FROM inventory_history WHERE inventory_id = ? AND change_type = ?",
                Long.class, INVENTORY_ID, changeType);
    }

    private String historyReason(String changeType) {
        return jdbc.queryForObject(
                "SELECT reason FROM inventory_history WHERE inventory_id = ? AND change_type = ?",
                String.class, INVENTORY_ID, changeType);
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
