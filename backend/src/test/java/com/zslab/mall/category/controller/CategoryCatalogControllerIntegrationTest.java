package com.zslab.mall.category.controller;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.support.AbstractIntegrationTest;
import java.sql.Timestamp;
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
 * 공개 카테고리 목록 endpoint 통합 테스트(Track 72·실 MariaDB·MockMvc). HTTP → {@code CategoryCatalogController} →
 * {@code CategoryCatalogService} → Repository 흐름을 실측한다({@code ProductCatalogControllerIntegrationTest} 픽스처 패턴).
 *
 * <p>커버: 비인증 200 / 루트(parent IS NULL)만 반환·자식·soft-delete 제외 / sort_order·id 오름차순 / 빈 목록.
 * 루트 판별은 depth가 아닌 parent이므로 시드 depth를 0·1로 섞어 depth에 의존하지 않음을 잠근다.
 */
@AutoConfigureMockMvc
class CategoryCatalogControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/categories";

    private static final long ROOT_B = 72101L;       // sort_order 20
    private static final long ROOT_A = 72102L;       // sort_order 10 → 첫 번째
    private static final long ROOT_C = 72103L;       // sort_order 20(ROOT_B와 동순위·id 큼 → ROOT_B 다음)
    private static final long CHILD_OF_A = 72104L;   // parent=ROOT_A → 제외
    private static final long ROOT_DELETED = 72105L; // soft-delete → 제외
    private static final List<Long> IDS = List.of(ROOT_B, ROOT_A, ROOT_C, CHILD_OF_A, ROOT_DELETED);

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
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("T1 비인증 200 — 토큰 없이 GET 목록 조회")
    void list_publicAccess_unauthenticated() throws Exception {
        seedFixtures();
        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("T2 루트만 반환 — 자식(parent 있음)·soft-delete 제외, depth 값(0/1 혼재)에 무관")
    void list_returnsRootsOnly() throws Exception {
        seedFixtures();
        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.categoryId == " + ROOT_A + ")]").exists())
                .andExpect(jsonPath("$[?(@.categoryId == " + ROOT_B + ")]").exists())
                .andExpect(jsonPath("$[?(@.categoryId == " + ROOT_C + ")]").exists())
                .andExpect(jsonPath("$[?(@.categoryId == " + CHILD_OF_A + ")]").doesNotExist())
                .andExpect(jsonPath("$[?(@.categoryId == " + ROOT_DELETED + ")]").doesNotExist())
                // 응답 필드 3개(categoryId·displayName·sortOrder)·depth·parent 미노출
                .andExpect(jsonPath("$[0].categoryId").exists())
                .andExpect(jsonPath("$[0].displayName").exists())
                .andExpect(jsonPath("$[0].sortOrder").exists())
                .andExpect(jsonPath("$[0].depth").doesNotExist());
    }

    @Test
    @DisplayName("T3 정렬 — sort_order 오름차순, 동순위는 id 오름차순(A(10) → B(20,id小) → C(20,id大))")
    void list_orderedBySortOrderThenId() throws Exception {
        seedFixtures();
        // 다른 테스트 잔여 루트가 섞이지 않도록 본 픽스처 3건만 추려 순서를 확인한다(displayName prefix 필터).
        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.displayName =~ /^트랙72.*/)].categoryId")
                        .value(contains(
                                (int) ROOT_A, (int) ROOT_B, (int) ROOT_C)));
    }

    @Test
    @DisplayName("T4 빈 목록 — 픽스처 없음: 응답 배열 길이가 DB 활성 루트 수와 일치(격리 시 0)")
    void list_empty() throws Exception {
        Integer rootCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM category WHERE parent_id IS NULL AND deleted_at IS NULL", Integer.class);
        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(rootCount));
    }

    // ==================== seed·helpers ====================

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).
    private void seedFixtures() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedCategory(ROOT_B, null, "트랙72루트B", 0, 20, false);
                seedCategory(ROOT_A, null, "트랙72루트A", 1, 10, false);
                seedCategory(ROOT_C, null, "트랙72루트C", 1, 20, false);
                seedCategory(CHILD_OF_A, ROOT_A, "트랙72자식A1", 2, 0, false);
                seedCategory(ROOT_DELETED, null, "트랙72삭제루트", 1, 0, true);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedCategory(long id, Long parentId, String displayName, int depth, int sortOrder, boolean deleted) {
        jdbc.update("INSERT INTO category (id, parent_id, display_name, depth, sort_order, created_at, updated_at, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6), ?)",
                id, parentId, displayName, depth, sortOrder, deleted ? Timestamp.valueOf("2026-07-01 00:00:00") : null);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                // IDS는 코드 상수(정적 long 리터럴)만 바인딩한다.
                jdbc.update("DELETE FROM category WHERE id IN (?, ?, ?, ?, ?)",
                        IDS.get(0), IDS.get(1), IDS.get(2), IDS.get(3), IDS.get(4));
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }
}
