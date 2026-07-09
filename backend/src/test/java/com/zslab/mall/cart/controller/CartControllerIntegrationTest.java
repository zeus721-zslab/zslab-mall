package com.zslab.mall.cart.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.security.AuthHeaders;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.zslab.mall.support.AbstractIntegrationTest;

/**
 * 장바구니 담기 endpoint E2E 통합 테스트(Track 40·실 MariaDB). HTTP → {@code CartController} → {@code CartService} →
 * cart_item 원자 생성/누적 흐름을 실 커밋·HTTP 경유로 실측한다({@code ProductRegistrationControllerIntegrationTest} 픽스처
 * 패턴 정합). 커버: 신규 담기 201·동일 variant 재담기 수량 누적(M1α)·variant 미존재 404·미인증 401·비-BUYER 403·quantity<1 400.
 *
 * <p><b>seed</b>: buyer user 1행과 product_variant 1행을 실 seed한다. product_variant의 상위(product·option_value)는
 * cart_item FK 대상이 아니므로 {@code FOREIGN_KEY_CHECKS=0}(LT-02)으로 최소 seed한다(CartItemRepositoryTest 선례). 담기
 * 트랜잭션 커밋은 JdbcTemplate 직접 조회로 검증하므로 클래스에 {@code @Transactional}을 두지 않는다.
 */
@AutoConfigureMockMvc
class CartControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/cart/items";

    private static final long BUYER_USER_ID = 9640L;   // JWT subject(actorId)·cart_item.user_id
    private static final long VARIANT_ID = 9641L;       // seed된 product_variant 내부 PK(DB 검증용)
    private static final String VARIANT_PUBLIC_ID = pid("var_", "CRTVAR");  // 외부 대상키(요청·응답)
    private static final String MISSING_VARIANT_PUBLIC_ID = pid("var_", "CRTMIS"); // 미seed·404 검증용
    private static final long SELLER_ACTOR_ID = 9642L;  // 비-BUYER 403 검증용(필터 선차단·seed 불요)

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

    @Test
    @DisplayName("신규 담기 → 201 + 담김 상태(userId·variantPublicId·quantity·selected) · cart_item 1행 quantity=2")
    void addItem_new_returns201_persistsRow() throws Exception {
        add(authHeaders.buyer(BUYER_USER_ID), VARIANT_PUBLIC_ID, 2)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value((int) BUYER_USER_ID))
                .andExpect(jsonPath("$.variantPublicId").value(VARIANT_PUBLIC_ID))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.selected").value(true));

        assertThat(count("SELECT COUNT(*) FROM cart_item WHERE user_id=? AND variant_id=? AND quantity=2 AND selected=1",
                BUYER_USER_ID, VARIANT_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("동일 variant 재담기 → 수량 누적(M1α·별도 row 아님·UNIQUE 유지) · 최종 quantity=5·row 1개")
    void addItem_sameVariantAgain_accumulatesQuantity() throws Exception {
        add(authHeaders.buyer(BUYER_USER_ID), VARIANT_PUBLIC_ID, 2).andExpect(status().isCreated());
        add(authHeaders.buyer(BUYER_USER_ID), VARIANT_PUBLIC_ID, 3)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantity").value(5));

        assertThat(count("SELECT COUNT(*) FROM cart_item WHERE user_id=?", BUYER_USER_ID)).isEqualTo(1);
        assertThat(count("SELECT quantity FROM cart_item WHERE user_id=? AND variant_id=?",
                BUYER_USER_ID, VARIANT_ID)).isEqualTo(5);
    }

    @Test
    @DisplayName("존재하지 않는 variant → 404 PRODUCT_VARIANT_NOT_FOUND · cart_item 미생성")
    void addItem_unknownVariant_returns404() throws Exception {
        add(authHeaders.buyer(BUYER_USER_ID), MISSING_VARIANT_PUBLIC_ID, 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_VARIANT_NOT_FOUND"));

        assertThat(count("SELECT COUNT(*) FROM cart_item WHERE user_id=?", BUYER_USER_ID)).isZero();
    }

    @Test
    @DisplayName("미인증(토큰 없음) → 401 UNAUTHENTICATED")
    void addItem_unauthenticated_returns401() throws Exception {
        add(new HttpHeaders(), VARIANT_PUBLIC_ID, 1)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("비-BUYER role(SELLER 토큰) → 403 FORBIDDEN(SecurityConfig 필터 선차단)")
    void addItem_sellerRole_returns403() throws Exception {
        add(authHeaders.seller(SELLER_ACTOR_ID), VARIANT_PUBLIC_ID, 1)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("quantity<1(0) → 400 VALIDATION_FAILED(@Min(1)) · cart_item 미생성")
    void addItem_quantityBelowOne_returns400() throws Exception {
        add(authHeaders.buyer(BUYER_USER_ID), VARIANT_PUBLIC_ID, 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(count("SELECT COUNT(*) FROM cart_item WHERE user_id=?", BUYER_USER_ID)).isZero();
    }

    // ==================== helpers ====================

    private ResultActions add(HttpHeaders headers, Object variantPublicId, Object quantity) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("variantPublicId", variantPublicId);
        body.put("quantity", quantity);
        return mockMvc.perform(post(URL)
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private int count(String sql, Object... args) {
        Integer result = jdbc.queryForObject(sql, Integer.class, args);
        return result == null ? 0 : result;
    }

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).
    private void seedFixtures() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        BUYER_USER_ID, pid("usr_", "CRTUSR"));
                // product_variant 상위(product·option_value)는 cart_item FK 대상이 아니라 최소 seed(FK_CHECKS=0).
                jdbc.update("INSERT INTO product_variant "
                                + "(id, public_id, product_id, variant_code, additional_price, status, "
                                + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                                + "VALUES (?, ?, 1, 'CRT-VAR-1', 0, 'SALE', 0, 1, 1, NOW(6), NOW(6))",
                        VARIANT_ID, pid("var_", "CRTVAR"));
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM cart_item WHERE user_id=?", BUYER_USER_ID);
                jdbc.update("DELETE FROM product_variant WHERE id=?", VARIANT_ID);
                jdbc.update("DELETE FROM `user` WHERE id=?", BUYER_USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
