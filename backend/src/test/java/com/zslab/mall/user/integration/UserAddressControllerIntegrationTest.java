package com.zslab.mall.user.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 배송지 주소록 CRUD endpoint E2E 통합 테스트(Track 58 BL-4·실 MariaDB). HTTP → UserAddressController → UserAddressService → DB.
 * 커버: 첫 주소 자동 기본·기본 요청 시 기존 강등(단일성)·목록·수정·삭제(soft 숨김)·기본설정 전환·소유권 스코프 404·미인증 401·검증 400.
 *
 * <p>seed는 소유자·타인 user 2행이다. 실 커밋을 JdbcTemplate 직접 조회로 검증하므로 클래스에 @Transactional을 두지 않는다.
 * {@code /api/v1/users/me/**}는 SecurityConfig authenticated(무변경)이며 소유권은 Service가 userId 스코프로 강제한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserAddressControllerIntegrationTest {

    private static final String URL = "/api/v1/users/me/addresses";

    private static final long BUYER_USER_ID = 9680L;
    private static final long OTHER_USER_ID = 9681L; // 소유권 스코프(타 user 접근 404) 검증용

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
        seed();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("(1) 첫 주소 생성 → 201·요청 isDefault=false여도 기본 강제(is_default=1)")
    void create_firstAddress_returns201_forcedDefault() throws Exception {
        long id = createAddress(BUYER_USER_ID, false, "수령인A");

        assertThat(count("SELECT COUNT(*) FROM user_address WHERE id=? AND user_id=? AND is_default=1",
                id, BUYER_USER_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("(2) 둘째 주소 기본 요청 → 첫 주소 강등·기본은 정확히 1개(단일성)")
    void create_secondDefault_demotesFirst() throws Exception {
        long first = createAddress(BUYER_USER_ID, false, "수령인1"); // 첫 주소 → 기본
        long second = createAddress(BUYER_USER_ID, true, "수령인2");  // 기본 요청 → first 강등

        assertThat(count("SELECT COUNT(*) FROM user_address WHERE id=? AND is_default=1", second)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM user_address WHERE id=? AND is_default=0", first)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM user_address WHERE user_id=? AND is_default=1 AND deleted_at IS NULL",
                BUYER_USER_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("(3) 목록 → 200·본인 주소 2건")
    void list_returnsOwnAddresses() throws Exception {
        createAddress(BUYER_USER_ID, false, "수령인1");
        createAddress(BUYER_USER_ID, false, "수령인2");

        mockMvc.perform(get(URL).headers(authHeaders.buyer(BUYER_USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("(4) 수정 → 200·DB recipient_name·zonecode 교체")
    void update_returns200_persists() throws Exception {
        long id = createAddress(BUYER_USER_ID, false, "수령인원본");

        mockMvc.perform(patch(URL + "/" + id)
                        .headers(authHeaders.buyer(BUYER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("수령인수정")))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT recipient_name FROM user_address WHERE id=?", String.class, id))
                .isEqualTo("수령인수정");
        assertThat(jdbc.queryForObject("SELECT zonecode FROM user_address WHERE id=?", String.class, id))
                .isEqualTo("54321");
    }

    @Test
    @DisplayName("(5) 타인 주소 수정 → 404 ADDRESS_NOT_FOUND·DB 불변")
    void update_notOwned_returns404() throws Exception {
        long id = createAddress(BUYER_USER_ID, false, "수령인원본");

        mockMvc.perform(patch(URL + "/" + id)
                        .headers(authHeaders.buyer(OTHER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("침해시도")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ADDRESS_NOT_FOUND"));

        assertThat(jdbc.queryForObject("SELECT recipient_name FROM user_address WHERE id=?", String.class, id))
                .isEqualTo("수령인원본");
    }

    @Test
    @DisplayName("(6) 삭제 → 204·soft(deleted_at 세팅·목록에서 숨김)")
    void delete_returns204_softHidden() throws Exception {
        long id = createAddress(BUYER_USER_ID, false, "삭제대상");

        mockMvc.perform(delete(URL + "/" + id).headers(authHeaders.buyer(BUYER_USER_ID)))
                .andExpect(status().isNoContent());

        assertThat(count("SELECT COUNT(*) FROM user_address WHERE id=? AND deleted_at IS NOT NULL", id))
                .isEqualTo(1);
        mockMvc.perform(get(URL).headers(authHeaders.buyer(BUYER_USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("(7) 타인 주소 삭제 → 404 ADDRESS_NOT_FOUND·미삭제")
    void delete_notOwned_returns404() throws Exception {
        long id = createAddress(BUYER_USER_ID, false, "삭제대상");

        mockMvc.perform(delete(URL + "/" + id).headers(authHeaders.buyer(OTHER_USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ADDRESS_NOT_FOUND"));

        assertThat(count("SELECT COUNT(*) FROM user_address WHERE id=? AND deleted_at IS NULL", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("(8) 기본설정 → 204·대상 승격·기존 기본 강등(단일성)")
    void setDefault_returns204_switches() throws Exception {
        long first = createAddress(BUYER_USER_ID, false, "수령인1"); // 첫 주소 → 기본
        long second = createAddress(BUYER_USER_ID, false, "수령인2"); // 비기본

        mockMvc.perform(patch(URL + "/" + second + "/default").headers(authHeaders.buyer(BUYER_USER_ID)))
                .andExpect(status().isNoContent());

        assertThat(count("SELECT COUNT(*) FROM user_address WHERE id=? AND is_default=1", second)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM user_address WHERE id=? AND is_default=0", first)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM user_address WHERE user_id=? AND is_default=1 AND deleted_at IS NULL",
                BUYER_USER_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("(9) 타인 주소 기본설정 → 404 ADDRESS_NOT_FOUND")
    void setDefault_notOwned_returns404() throws Exception {
        long id = createAddress(BUYER_USER_ID, false, "수령인1");

        mockMvc.perform(patch(URL + "/" + id + "/default").headers(authHeaders.buyer(OTHER_USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ADDRESS_NOT_FOUND"));
    }

    @Test
    @DisplayName("(10) 미인증(토큰 없음) → 401 UNAUTHENTICATED")
    void list_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(URL).headers(new HttpHeaders()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("(11) recipientName 누락 생성 → 400 VALIDATION_FAILED·미생성")
    void create_missingRecipientName_returns400() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("isDefault", false);
        body.put("recipientPhone", "010-1111-2222");
        body.put("zonecode", "12345");
        body.put("addressRoad", "서울시 강남구 테헤란로 1");

        mockMvc.perform(post(URL)
                        .headers(authHeaders.buyer(BUYER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(count("SELECT COUNT(*) FROM user_address WHERE user_id=?", BUYER_USER_ID)).isZero();
    }

    // ==================== helpers ====================

    /** POST로 주소를 생성하고 응답에서 생성 id를 반환한다(E2E·201 확인 포함). */
    private long createAddress(long userId, boolean isDefault, String recipientName) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("isDefault", isDefault);
        body.put("recipientName", recipientName);
        body.put("recipientPhone", "010-1111-2222");
        body.put("zonecode", "12345");
        body.put("addressRoad", "서울시 강남구 테헤란로 1");
        String json = mockMvc.perform(post(URL)
                        .headers(authHeaders.buyer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("id").asLong();
    }

    private String updateBody(String recipientName) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("recipientName", recipientName);
        body.put("recipientPhone", "010-3333-4444");
        body.put("zonecode", "54321");
        body.put("addressRoad", "부산시 해운대구 2");
        return objectMapper.writeValueAsString(body);
    }

    private int count(String sql, Object... args) {
        Integer result = jdbc.queryForObject(sql, Integer.class, args);
        return result == null ? 0 : result;
    }

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).
    private void seed() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        BUYER_USER_ID, pid("usr_", "ADRUSR"));
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        OTHER_USER_ID, pid("usr_", "ADROTH"));
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM user_address WHERE user_id IN (?, ?)", BUYER_USER_ID, OTHER_USER_ID);
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?)", BUYER_USER_ID, OTHER_USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
