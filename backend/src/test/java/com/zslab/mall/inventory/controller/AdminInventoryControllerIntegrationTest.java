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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Admin Inventory endpoint E2E 통합 테스트(Track 21·D-105·실 MariaDB). HTTP → {@code AdminInventoryController} →
 * {@code InventoryService.adjustStock} → {@code Inventory} 도메인 → DB 흐름을 실 커밋·HTTP 경유로 실측한다(라이브 트랩 차단·
 * {@link com.zslab.mall.delivery.controller.AdminDeliveryControllerIntegrationTest} 패턴 1:1). 재고 조정 1 endpoint를 커버한다.
 *
 * <p><b>Admin 책임 경계(D-93 Q3·Q5)</b>: Admin은 전체 접근이므로 cross-tenant 시나리오가 부재하다. 인증 헤더 검증(401)·성공(200)·
 * variantPublicId 미존재(404)·도메인 불변조건 위반(422) 4건을 보장한다(CLAUDE.md 신규 도메인 통합 테스트 3건 의무 초과 충족).
 *
 * <p><b>판단 3 γ(D-105 §2 Q3)</b>: adjustStock은 E10 InventoryAdjusted를 발행하지 않는다. {@link RecordApplicationEvents}로
 * inventory 패키지 도메인 이벤트 0건을 단언해 γ(미발행)를 회귀 잠금한다.
 *
 * <p><b>트랜잭션</b>: {@code adjustStock}의 @Transactional 커밋을 JdbcTemplate 직접 조회로 검증하므로 클래스에 {@code @Transactional}을
 * 두지 않는다. 시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally)로 한다.
 *
 * <p><b>HTTP 경유 의무</b>: primitive를 직접 호출하지 않고 MockMvc로 endpoint를 구동한다. X-Admin-Id 헤더 stub은
 * {@code HeaderAdminActorResolver}가 해소하되 식별자는 사용하지 않는다(D-93 Q3·헤더 존재·형식 검증만).
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class AdminInventoryControllerIntegrationTest {

    private static final String ADMIN_ID_HEADER = "X-Admin-Id";

    private static final long ADMIN = 7101L; // Admin 액터 stub(전체 접근·검증 비대상)
    private static final long USER_ID = 9210L;
    private static final long SELLER_ID = 9210L; // 품목 소유 셀러(FK 부모 그래프·Admin 검증 비대상·D-91)
    private static final long PRODUCT_ID = 9210L;
    private static final long VARIANT_ID = 9210L;
    private static final long INVENTORY_ID = 9210L; // inventory.id(variant 1:1·adjustStock 대상)
    private static final long DUMMY_FK_ID = 9210L;

    private static final String VARIANT_PID = pid("var_", "ADIVAR");
    private static final String MISSING_VARIANT_PID = pid("var_", "ADINON");
    private static final String REASON = "재고 실사 보정";

    private static final int INITIAL_ON_HAND = 10;
    private static final int INITIAL_RESERVED = 2;
    private static final int INITIAL_AVAILABLE = 8; // on_hand - reserved 정합
    private static final int VALID_DECREASE = -3;   // T2: on_hand 7·available 5(INV-1·INV-4 정합)
    private static final int INVALID_DECREASE = -11; // T4: on_hand 부족(projected on_hand=-1·INV-4 위반)

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
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("T1 인증 실패: X-Admin-Id 헤더 부재 → 401 UNAUTHENTICATED·inventory 도메인 이벤트 0")
    void adjust_missingAdminHeader_returns401() throws Exception {
        // resolve()가 variant 조회 이전 최선두에서 throw하므로 시드 불요(variantPublicId 미존재여도 401이 우선한다).
        mockMvc.perform(post("/api/v1/admin/inventories/" + VARIANT_PID + "/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(VALID_DECREASE, REASON)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertThat(inventoryDomainEventCount()).isZero();
    }

    @Test
    @DisplayName("T2 성공: 유효 X-Admin-Id + 유효 차감(-3) → 200·응답 정합·on_hand 7·available 5·InventoryHistory ADJUST 1행·이벤트 0")
    void adjust_validAdmin_validDecrease_returns200_persistsAndAppendsHistory() throws Exception {
        seed(() -> {
            seedCatalog();
            seedInventory(INITIAL_ON_HAND, INITIAL_RESERVED, INITIAL_AVAILABLE);
        });

        mockMvc.perform(post("/api/v1/admin/inventories/" + VARIANT_PID + "/adjust")
                        .header(ADMIN_ID_HEADER, String.valueOf(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(VALID_DECREASE, REASON)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variantPublicId").value(VARIANT_PID))
                .andExpect(jsonPath("$.quantityOnHand").value(7))
                .andExpect(jsonPath("$.quantityReserved").value(2))
                .andExpect(jsonPath("$.quantityAvailable").value(5));

        // 커밋된 DB 상태 재조회 검증(on_hand 감소·available 재계산·reserved 불변)
        assertThat(onHand()).isEqualTo(7);
        assertThat(reserved()).isEqualTo(2);
        assertThat(available()).isEqualTo(5);
        // InventoryHistory ADJUST 1행 append(quantity_delta 부호 그대로·reason 기록·reference_type 'admin')
        assertThat(adjustHistoryCount()).isEqualTo(1);
        assertThat(adjustHistoryDelta()).isEqualTo(VALID_DECREASE);
        assertThat(adjustHistoryReason()).isEqualTo(REASON);
        assertThat(adjustHistoryReferenceType()).isEqualTo("admin");
        // 판단 3 γ: adjust 흐름은 도메인 이벤트를 발행하지 않는다(E10 미발행 잠금).
        assertThat(inventoryDomainEventCount()).isZero();
    }

    @Test
    @DisplayName("T3 실패: 미존재 variantPublicId → 404 PRODUCT_VARIANT_NOT_FOUND·이벤트 0")
    void adjust_unknownVariantPublicId_returns404() throws Exception {
        // 시드 없음(variant 미존재). resolve 통과 후 findByPublicId 실패 → ProductVariantNotFoundException 404.
        mockMvc.perform(post("/api/v1/admin/inventories/" + MISSING_VARIANT_PID + "/adjust")
                        .header(ADMIN_ID_HEADER, String.valueOf(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(VALID_DECREASE, REASON)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_VARIANT_NOT_FOUND"));

        assertThat(inventoryDomainEventCount()).isZero();
    }

    @Test
    @DisplayName("T4 실패: 음수 차감(-11)·on_hand 부족 → 422 INVENTORY_INVARIANT_VIOLATION·재고 롤백·InventoryHistory 0행·이벤트 0")
    void adjust_negativeDeltaBelowOnHand_returns422_rollsBack() throws Exception {
        seed(() -> {
            seedCatalog();
            seedInventory(INITIAL_ON_HAND, INITIAL_RESERVED, INITIAL_AVAILABLE);
        });

        mockMvc.perform(post("/api/v1/admin/inventories/" + VARIANT_PID + "/adjust")
                        .header(ADMIN_ID_HEADER, String.valueOf(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(INVALID_DECREASE, REASON)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVENTORY_INVARIANT_VIOLATION"));

        // INV-4 위반은 mutate 이전에 throw → @Transactional 롤백: on_hand·available 불변·History 미기록.
        assertThat(onHand()).isEqualTo(INITIAL_ON_HAND);
        assertThat(available()).isEqualTo(INITIAL_AVAILABLE);
        assertThat(adjustHistoryCount()).isZero();
        assertThat(inventoryDomainEventCount()).isZero();
    }

    // ---------- seed·helpers (AdminDeliveryControllerIntegrationTest 패턴 1:1·inventory 그래프 확장) ----------

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
                USER_ID, pid("usr_", "ADIUSR"));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, '통합셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                SELLER_ID, pid("slr_", "ADISLR"));
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, '통합상품', 'SALE', 10000, NOW(6), NOW(6))",
                PRODUCT_ID, pid("prd_", "ADIPRD"), SELLER_ID, DUMMY_FK_ID);
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VCADI', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                VARIANT_ID, VARIANT_PID, PRODUCT_ID, DUMMY_FK_ID);
    }

    private void seedInventory(int onHand, int reserved, int available) {
        jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))",
                INVENTORY_ID, VARIANT_ID, onHand, reserved, available);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM inventory_history WHERE inventory_id = ?", INVENTORY_ID);
                jdbc.update("DELETE FROM inventory WHERE id = ?", INVENTORY_ID);
                jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id = ?", USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private String body(int quantityDelta, String reason) {
        return "{\"quantityDelta\":" + quantityDelta + ",\"reason\":\"" + reason + "\"}";
    }

    /** 판단 3 γ 검증: inventory 패키지에서 발행된 도메인 이벤트 수(0이어야 한다·E10 미발행). */
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

    private int adjustHistoryCount() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_history WHERE inventory_id = ? AND change_type = 'ADJUST'",
                Integer.class, INVENTORY_ID);
        return count == null ? 0 : count;
    }

    private Integer adjustHistoryDelta() {
        return jdbc.queryForObject(
                "SELECT quantity_delta FROM inventory_history WHERE inventory_id = ? AND change_type = 'ADJUST'",
                Integer.class, INVENTORY_ID);
    }

    private String adjustHistoryReason() {
        return jdbc.queryForObject(
                "SELECT reason FROM inventory_history WHERE inventory_id = ? AND change_type = 'ADJUST'",
                String.class, INVENTORY_ID);
    }

    private String adjustHistoryReferenceType() {
        return jdbc.queryForObject(
                "SELECT reference_type FROM inventory_history WHERE inventory_id = ? AND change_type = 'ADJUST'",
                String.class, INVENTORY_ID);
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
