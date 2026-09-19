package com.zslab.mall.seller.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.crypto.AesGcmTextEncryptor;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.zslab.mall.seller.repository.WithdrawnSellerRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 셀러 관리 통합 테스트(Track 89-D·D-187·실 MariaDB·HTTP 경유·실 커밋). 페이징 목록·상세(집계·종료 가능 여부 미리보기)·상태 전이
 * (허용 6종·금지·가드 G1~G3·감사·WithdrawnSeller)·정보 수정(PUT)·비-ACTIVE 셀러 상품 등록 차단·카탈로그 노출 연동을 검증한다.
 *
 * <p>핵심 트랩: 결제 만료(PAYMENT_EXPIRED) 주문의 품목은 {@code item_status=ORDERED}로 남는다 — G2가 주문 상태를 조인해 이를 제외해야
 * 하며(정찰 실측·셀러 1에 5건), T4·T5가 그 경계를 직접 시드해 단언한다.
 *
 * <p>트랜잭션: 명령의 @Transactional 커밋·롤백을 JdbcTemplate로 검증하므로 클래스에 {@code @Transactional}을 두지 않는다. 시드·정리는
 * {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally). 모든 SQL은 ? positional 바인딩(SQL injection 없음).
 */
@AutoConfigureMockMvc
class AdminSellerManagementControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/admin/sellers";
    private static final String CATALOG_URL = "/api/v1/products";
    private static final String KEYWORD_PREFIX = "89D셀러";

    private static final long ADMIN_ID = 8990L;
    private static final long BUYER_ID = 8991L;
    private static final long OWNER_USER_ID = 8911L;      // 8901 소속 owner(활성)
    private static final long WITHDRAWN_USER_ID = 8912L;  // 8901 소속 manager(탈퇴 회원)
    private static final long ORDER_BUYER_ID = 8913L;

    private static final long S_ACTIVE = 8901L;      // 기본 셀러: 상품 3·계좌·구성원 2·PAID 정산·CONFIRMED 주문
    private static final long S_PENDING = 8902L;     // PENDING→ACTIVE
    private static final long S_SUSPENDED = 8903L;   // SUSPENDED→TERMINATED(활동 없음)
    private static final long S_TERMINATED = 8904L;  // 불가역
    private static final long S_BUSY = 8905L;        // 가드 3종 전부 위반
    private static final long S_CLEAN = 8906L;       // 만료 주문 ORDERED 품목·PAID 정산·COMPLETED 클레임만 → 종료 가능
    private static final long S_PENDING2 = 8907L;    // PENDING→TERMINATED(승인 거부)
    private static final long[] SELLER_IDS = {S_ACTIVE, S_PENDING, S_SUSPENDED, S_TERMINATED, S_BUSY, S_CLEAN, S_PENDING2};

    private static final long CATEGORY_ID = 8981L;
    private static final long PRODUCT_A1 = 8971L;    // 8901 SALE(카탈로그 노출)
    private static final long PRODUCT_A2 = 8972L;    // 8901 SALE
    private static final long PRODUCT_A3 = 8973L;    // 8901 STOPPED
    private static final long PRODUCT_B1 = 8974L;    // 8905 SALE
    private static final long VARIANT_A1 = 8976L;
    private static final long ORDER_CONFIRMED = 8921L;   // 8913 · 8901 CONFIRMED 품목
    private static final long ORDER_EXPIRED_CLEAN = 8922L; // PAYMENT_EXPIRED · 8906 ORDERED 품목(트랩)
    private static final long ORDER_PAID_BUSY = 8923L;   // PAID · 8905 PAID 품목 2
    private static final long ORDER_EXPIRED_BUSY = 8924L; // PAYMENT_EXPIRED · 8905 ORDERED 품목(트랩·집계 제외)
    private static final long ORDER_CONFIRMED_CLEAN = 8925L; // CONFIRMED · 8906 CONFIRMED 품목(COMPLETED 클레임)
    private static final long ITEM_A_CONFIRMED = 8931L;
    private static final long ITEM_CLEAN_EXPIRED = 8932L;
    private static final long ITEM_BUSY_PAID_1 = 8933L;
    private static final long ITEM_BUSY_PAID_2 = 8934L;
    private static final long ITEM_BUSY_EXPIRED = 8935L;
    private static final long ITEM_CLEAN_CONFIRMED = 8936L;
    private static final long CLAIM_COMPLETED = 8941L;
    private static final long CLAIM_REQUESTED = 8942L;
    private static final long SETTLEMENT_A_PAID = 8951L;
    private static final long SETTLEMENT_BUSY_PENDING = 8952L;
    private static final long SETTLEMENT_BUSY_CONFIRMED = 8953L;
    private static final long SETTLEMENT_CLEAN_PAID = 8954L;
    private static final long BANK_ACCOUNT_A = 8961L;

    private static final String BUSINESS_NO_A = "890-89-00001";
    private static final String BUSINESS_NO_BUSY = "890-89-00005";
    private static final long CONFIRMED_AMOUNT_A = 45_000L;
    private static final long SETTLEMENT_NET_A = 40_500L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    // Track 89-F: 계좌번호 컬럼은 v1: 암호문(Converter strict) → 시드도 암호화해 INSERT한다
    @Autowired
    private AesGcmTextEncryptor bankAccountEncryptor;
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoSpyBean
    private WithdrawnSellerRepository withdrawnSellerRepository;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        seedAll();
    }

    @AfterEach
    void tearDown() {
        Mockito.reset(withdrawnSellerRepository);
        cleanup();
    }

    // ==================== T1 권한 ====================

    @Test
    @DisplayName("T1 권한: 목록·상세·전이·수정 — 무인증 401 / BUYER 403 / ADMIN 200·204")
    void authorization() throws Exception {
        mockMvc.perform(get(URL + "/page")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/page").headers(authHeaders.buyer(BUYER_ID))).andExpect(status().isForbidden());
        mockMvc.perform(get(URL + "/" + pid(S_ACTIVE)).headers(authHeaders.buyer(BUYER_ID))).andExpect(status().isForbidden());
        mockMvc.perform(patch(URL + "/" + pid(S_ACTIVE) + "/status").headers(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(statusBody("SUSPENDED", "사유")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(URL + "/" + pid(S_ACTIVE)).headers(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody("89D셀러A", BUSINESS_NO_A, null, null)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(URL + "/page").headers(admin())).andExpect(status().isOk());
        mockMvc.perform(get(URL + "/" + pid(S_ACTIVE)).headers(admin())).andExpect(status().isOk());
        assertThat(sellerStatus(S_ACTIVE)).isEqualTo("ACTIVE");
    }

    // ==================== T2 목록 ====================

    @Test
    @DisplayName("T2 목록: keyword 7건·등록일 desc·상품 수/계좌 여부 배치 집계 · status 필터 · 사업자번호/이메일 검색 · 페이징 · 오값 400")
    void list_filtersAndAggregates() throws Exception {
        JsonNode page = readJson(mockMvc.perform(get(URL + "/page").headers(admin())
                        .param("keyword", KEYWORD_PREFIX).param("size", "10"))
                .andExpect(status().isOk()));
        assertThat(page.get("totalCount").asLong()).isEqualTo(7);
        assertThat(page.get("items")).hasSize(7);
        // 등록일 desc: 시드 created_at = 2026-01-0N(N=id 끝자리) → 8907이 최신
        assertThat(page.get("items").get(0).get("sellerPublicId").asText()).isEqualTo(pid(S_PENDING2));
        JsonNode rowA = findRow(page, pid(S_ACTIVE));
        assertThat(rowA.get("companyName").asText()).isEqualTo("89D셀러A");
        assertThat(rowA.get("businessNo").asText()).isEqualTo(BUSINESS_NO_A);
        assertThat(rowA.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(rowA.get("productCount").asLong()).isEqualTo(3);
        assertThat(rowA.get("hasPrimaryBankAccount").asBoolean()).isTrue();
        assertThat(rowA.get("contactEmail").asText()).isEqualTo("seller-a@89d.test");
        JsonNode rowBusy = findRow(page, pid(S_BUSY));
        assertThat(rowBusy.get("productCount").asLong()).isEqualTo(1);
        assertThat(rowBusy.get("hasPrimaryBankAccount").asBoolean()).isFalse();
        assertThat(findRow(page, pid(S_TERMINATED)).get("productCount").asLong()).isZero();

        // status 필터
        JsonNode suspended = readJson(mockMvc.perform(get(URL + "/page").headers(admin())
                        .param("keyword", KEYWORD_PREFIX).param("status", "SUSPENDED"))
                .andExpect(status().isOk()));
        assertThat(suspended.get("totalCount").asLong()).isEqualTo(1);
        assertThat(suspended.get("items").get(0).get("sellerPublicId").asText()).isEqualTo(pid(S_SUSPENDED));
        JsonNode pending = readJson(mockMvc.perform(get(URL + "/page").headers(admin())
                .param("keyword", KEYWORD_PREFIX).param("status", "PENDING")).andExpect(status().isOk()));
        assertThat(pending.get("totalCount").asLong()).isEqualTo(2);

        // 사업자번호·이메일 검색(부분일치)
        assertThat(readJson(mockMvc.perform(get(URL + "/page").headers(admin()).param("keyword", "890-89-0000"))
                .andExpect(status().isOk())).get("totalCount").asLong()).isEqualTo(2);
        assertThat(readJson(mockMvc.perform(get(URL + "/page").headers(admin()).param("keyword", "seller-a@89d"))
                .andExpect(status().isOk())).get("totalCount").asLong()).isEqualTo(1);
        // LIKE 이스케이프: '_'는 리터럴(전체 매칭 아님)
        assertThat(readJson(mockMvc.perform(get(URL + "/page").headers(admin()).param("keyword", "89D셀러_"))
                .andExpect(status().isOk())).get("totalCount").asLong()).isZero();

        // 페이징
        JsonNode second = readJson(mockMvc.perform(get(URL + "/page").headers(admin())
                .param("keyword", KEYWORD_PREFIX).param("page", "1").param("size", "5")).andExpect(status().isOk()));
        assertThat(second.get("items")).hasSize(2);
        assertThat(second.get("hasNext").asBoolean()).isFalse();

        // 오값
        mockMvc.perform(get(URL + "/page").headers(admin()).param("status", "FOO")).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL + "/page").headers(admin()).param("keyword", "x".repeat(51)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        // 기존 드롭다운 전량 목록은 형태(배열·3필드) 유지
        mockMvc.perform(get(URL).headers(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sellerPublicId").exists())
                .andExpect(jsonPath("$[0].companyName").exists())
                .andExpect(jsonPath("$[0].status").exists());
    }

    // ==================== T3 상세 ====================

    @Test
    @DisplayName("T3 상세: 기본정보(마스킹 없음)·구성원 2(탈퇴 표기)·주 계좌 끝4자리·상품 상태별·주문 수/구매확정 매출·정산 상태별·종료 가능·경고 / 미존재 404")
    void detail_aggregates() throws Exception {
        JsonNode detail = readJson(mockMvc.perform(get(URL + "/" + pid(S_ACTIVE)).headers(admin()))
                .andExpect(status().isOk()));
        assertThat(detail.get("companyName").asText()).isEqualTo("89D셀러A");
        assertThat(detail.get("businessNo").asText()).isEqualTo(BUSINESS_NO_A);
        assertThat(detail.get("ceoName").asText()).isEqualTo("대표A");
        assertThat(detail.get("contactEmail").asText()).isEqualTo("seller-a@89d.test");
        assertThat(detail.get("contactPhone").asText()).isEqualTo("02-1000-8901");
        assertThat(detail.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(detail.get("commissionRate").asInt()).isEqualTo(1200);

        // 구성원: owner(활성) + manager(탈퇴·withdrawnAt 표기·행 유지)
        assertThat(detail.get("members")).hasSize(2);
        JsonNode owner = findMember(detail, pid("usr_", "89DOWN"));
        assertThat(owner.get("email").asText()).isEqualTo("owner@89d.test");
        assertThat(owner.get("roleCode").asText()).isEqualTo("SELLER_OWNER");
        assertThat(owner.has("withdrawnAt")).isFalse(); // NON_NULL 직렬화 → 키 생략
        JsonNode manager = findMember(detail, pid("usr_", "89DWDR"));
        assertThat(manager.get("roleCode").asText()).isEqualTo("SELLER_MANAGER");
        assertThat(manager.get("withdrawnAt").asText()).startsWith("2026-09-01");

        // 주 계좌: 끝 4자리만
        assertThat(detail.get("primaryBankAccount").get("accountNumberSuffix").asText()).isEqualTo("3456");
        assertThat(detail.get("primaryBankAccount").get("bankCode").asText()).isEqualTo("KB");
        assertThat(detail.get("primaryBankAccount").get("status").asText()).isEqualTo("VERIFIED");
        assertThat(detail.get("primaryBankAccount").has("accountNumber")).isFalse();

        // 집계
        assertThat(detail.get("productCount").asLong()).isEqualTo(3);
        assertThat(detail.get("productCountByStatus").get("SALE").asLong()).isEqualTo(2);
        assertThat(detail.get("productCountByStatus").get("STOPPED").asLong()).isEqualTo(1);
        assertThat(detail.get("orderCount").asLong()).isEqualTo(1);
        assertThat(detail.get("confirmedSalesAmount").asLong()).isEqualTo(CONFIRMED_AMOUNT_A);
        assertThat(detail.get("settlements")).hasSize(1);
        assertThat(detail.get("settlements").get(0).get("status").asText()).isEqualTo("PAID");
        assertThat(detail.get("settlements").get(0).get("count").asLong()).isEqualTo(1);
        assertThat(detail.get("settlements").get(0).get("netAmount").asLong()).isEqualTo(SETTLEMENT_NET_A);

        // 종료 가능(PAID 정산·종결 품목·활성 클레임 없음)·경고
        assertThat(detail.get("terminable").asBoolean()).isTrue();
        assertThat(detail.get("terminationBlocks")).isEmpty();
        assertThat(detail.get("warnings").get("primaryBankAccountMissing").asBoolean()).isFalse();
        assertThat(detail.get("warnings").get("saleProductCount").asLong()).isEqualTo(2);

        // 계좌·구성원·정산 없는 셀러: null·빈 배열·경고 true
        JsonNode busy = readJson(mockMvc.perform(get(URL + "/" + pid(S_BUSY)).headers(admin())).andExpect(status().isOk()));
        assertThat(busy.has("primaryBankAccount")).isFalse(); // NON_NULL 직렬화 → 키 생략
        assertThat(busy.get("members")).isEmpty();
        assertThat(busy.get("warnings").get("primaryBankAccountMissing").asBoolean()).isTrue();

        mockMvc.perform(get(URL + "/slr_NOPE00000000000000000000000").headers(admin()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SELLER_NOT_FOUND"));
    }

    // ==================== T4 종료 가드 ====================

    @Test
    @DisplayName("T4 종료 가드: 상세 미리보기 blocks(G1 2·G2 2[만료 ORDERED 제외]·G3 1) = PATCH TERMINATED 409 blocks · 상태·감사·아카이브 불변")
    void terminate_blockedByGuards_previewMatchesActual() throws Exception {
        JsonNode detail = readJson(mockMvc.perform(get(URL + "/" + pid(S_BUSY)).headers(admin())).andExpect(status().isOk()));
        assertThat(detail.get("terminable").asBoolean()).isFalse();
        Map<String, Long> preview = blocks(detail.get("terminationBlocks"));
        assertThat(preview).containsExactlyInAnyOrderEntriesOf(Map.of(
                "UNPAID_SETTLEMENT", 2L, "ORDER_ITEM_IN_PROGRESS", 2L, "CLAIM_ACTIVE", 1L));

        JsonNode conflict = readJson(mockMvc.perform(patch(URL + "/" + pid(S_BUSY) + "/status").headers(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(statusBody("TERMINATED", "정책 위반 종료")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELLER_ACTIVITY_IN_PROGRESS")));
        assertThat(blocks(conflict.get("blocks"))).isEqualTo(preview);

        assertThat(sellerStatus(S_BUSY)).isEqualTo("ACTIVE");
        assertThat(withdrawnCount(S_BUSY)).isZero();
        assertThat(auditRows(S_BUSY)).isEmpty();
        // 정지(SUSPENDED)는 가드 대상이 아니다
        mockMvc.perform(patch(URL + "/" + pid(S_BUSY) + "/status").headers(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(statusBody("SUSPENDED", "정지")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUSPENDED"));
    }

    @Test
    @DisplayName("T4-2 가드 단일 위반: G1만(PAID→PENDING 변경) / G2만 / G3만 각각 409 blocks 1건 · PAID만·종결 품목만·COMPLETED 클레임만이면 통과")
    void terminate_singleGuards() throws Exception {
        // 8901: 정산 PAID·CONFIRMED 품목·클레임 없음 → 종료 가능 상태에서 조건을 하나씩 켠다
        updateWithoutFk("UPDATE settlement SET status = 'PENDING' WHERE id = ?", SETTLEMENT_A_PAID);
        assertBlocked(S_ACTIVE, Map.of("UNPAID_SETTLEMENT", 1L));
        updateWithoutFk("UPDATE settlement SET status = 'PAID' WHERE id = ?", SETTLEMENT_A_PAID);

        updateWithoutFk("UPDATE order_item SET item_status = 'SHIPPING' WHERE id = ?", ITEM_A_CONFIRMED);
        updateWithoutFk("UPDATE `order` SET status = 'SHIPPING' WHERE id = ?", ORDER_CONFIRMED);
        assertBlocked(S_ACTIVE, Map.of("ORDER_ITEM_IN_PROGRESS", 1L));
        // 같은 품목이라도 주문이 취소(CANCELLED)면 진행 중 아님
        updateWithoutFk("UPDATE `order` SET status = 'CANCELLED' WHERE id = ?", ORDER_CONFIRMED);
        assertThat(readJson(mockMvc.perform(get(URL + "/" + pid(S_ACTIVE)).headers(admin())).andExpect(status().isOk()))
                .get("terminable").asBoolean()).isTrue();
        updateWithoutFk("UPDATE order_item SET item_status = 'CONFIRMED' WHERE id = ?", ITEM_A_CONFIRMED);
        updateWithoutFk("UPDATE `order` SET status = 'CONFIRMED' WHERE id = ?", ORDER_CONFIRMED);

        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedClaim(8943L, ITEM_A_CONFIRMED, "APPROVED");
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
        assertBlocked(S_ACTIVE, Map.of("CLAIM_ACTIVE", 1L));
        updateWithoutFk("UPDATE claim SET status = 'REJECTED' WHERE id = ?", 8943L);
        assertThat(readJson(mockMvc.perform(get(URL + "/" + pid(S_ACTIVE)).headers(admin())).andExpect(status().isOk()))
                .get("terminable").asBoolean()).isTrue();
    }

    // ==================== T5 허용 전이 ====================

    @Test
    @DisplayName("T5 허용 전이 6종 200·응답=전이 후 상세·감사 UPDATE(before/after status+reason)·TERMINATED는 withdrawn_seller 1행(사유·5년 보관)")
    void transitions_allowed() throws Exception {
        // PENDING → ACTIVE → SUSPENDED (2회 전이·감사 2건)
        transition(S_PENDING, "ACTIVE", "입점 승인").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        JsonNode activated = readJson(transition(S_PENDING, "SUSPENDED", "재전이용").andExpect(status().isOk()));
        assertThat(activated.get("status").asText()).isEqualTo("SUSPENDED");
        assertThat(sellerStatus(S_PENDING)).isEqualTo("SUSPENDED");
        List<Map<String, Object>> audits = auditRows(S_PENDING);
        assertThat(audits).hasSize(2);
        assertThat((String) audits.get(0).get("diff_json")).contains("PENDING").contains("ACTIVE").contains("입점 승인");
        assertThat((String) audits.get(1).get("diff_json")).contains("SUSPENDED").contains("재전이용");
        // SUSPENDED → ACTIVE
        assertThat(readJson(transition(S_PENDING, "ACTIVE", "정지 해제").andExpect(status().isOk()))
                .get("status").asText()).isEqualTo("ACTIVE");

        // ACTIVE → SUSPENDED (8901·응답 상세에 집계 유지)
        JsonNode suspended = readJson(transition(S_ACTIVE, "SUSPENDED", "정책 위반").andExpect(status().isOk()));
        assertThat(suspended.get("status").asText()).isEqualTo("SUSPENDED");
        assertThat(suspended.get("productCount").asLong()).isEqualTo(3);
        assertThat(suspended.get("terminable").asBoolean()).isTrue();
        // 상품 상태는 바꾸지 않는다(확정 사항)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE seller_id = ? AND status = 'SALE'",
                Integer.class, S_ACTIVE)).isEqualTo(2);

        // SUSPENDED → TERMINATED (8903·활동 없음)
        JsonNode terminated = readJson(transition(S_SUSPENDED, "TERMINATED", "강제 종료").andExpect(status().isOk()));
        assertThat(terminated.get("status").asText()).isEqualTo("TERMINATED");
        assertWithdrawnArchive(S_SUSPENDED, "강제 종료");

        // ACTIVE → TERMINATED (8906: 만료 주문 ORDERED 품목·PAID 정산·COMPLETED 클레임 → 가드 통과가 핵심)
        JsonNode cleanDetail = readJson(mockMvc.perform(get(URL + "/" + pid(S_CLEAN)).headers(admin())).andExpect(status().isOk()));
        assertThat(cleanDetail.get("terminable").asBoolean()).isTrue();
        assertThat(cleanDetail.get("orderCount").asLong()).isEqualTo(1); // 만료 주문은 주문 수에서도 제외
        readJson(transition(S_CLEAN, "TERMINATED", "탈퇴 요청").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED")));
        assertWithdrawnArchive(S_CLEAN, "탈퇴 요청");

        // PENDING → TERMINATED (승인 거부)
        readJson(transition(S_PENDING2, "TERMINATED", "승인 거부").andExpect(status().isOk()));
        assertWithdrawnArchive(S_PENDING2, "승인 거부");
        assertThat(sellerStatus(S_PENDING2)).isEqualTo("TERMINATED");
    }

    @Test
    @DisplayName("T5-2 원자성: TERMINATED 전이 중 withdrawn_seller INSERT 실패 → 500·seller 상태·감사 모두 롤백(같은 TX)")
    void terminate_archiveInsertFails_rollsBackStatusAndAudit() throws Exception {
        Mockito.doThrow(new RuntimeException("archive insert failure(test)"))
                .when(withdrawnSellerRepository).save(Mockito.any());

        transition(S_SUSPENDED, "TERMINATED", "강제 종료").andExpect(status().isInternalServerError());

        assertThat(sellerStatus(S_SUSPENDED)).isEqualTo("SUSPENDED");
        assertThat(auditRows(S_SUSPENDED)).isEmpty();
        assertThat(withdrawnCount(S_SUSPENDED)).isZero();
    }

    // ==================== T6 금지 전이·검증 ====================

    @Test
    @DisplayName("T6 금지 전이: TERMINATED→any 422·같은 상태 422·PENDING→SUSPENDED 422 / status PENDING·오값·사유 공백 400 / 미존재 404 · 상태·감사·아카이브 불변")
    void transitions_forbidden() throws Exception {
        for (String target : new String[] {"ACTIVE", "SUSPENDED", "TERMINATED"}) {
            transition(S_TERMINATED, target, "재활성 시도").andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("SELLER_INVALID_STATE"));
        }
        transition(S_ACTIVE, "ACTIVE", "같은 상태").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SELLER_INVALID_STATE"));
        transition(S_PENDING, "SUSPENDED", "심사 중 정지").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SELLER_INVALID_STATE"));

        transition(S_ACTIVE, "PENDING", "되돌리기").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        transition(S_ACTIVE, "FOO", "오값").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        transition(S_ACTIVE, "SUSPENDED", "   ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        transition(S_ACTIVE, "SUSPENDED", "x".repeat(201)).andExpect(status().isBadRequest());
        mockMvc.perform(patch(URL + "/slr_NOPE00000000000000000000000/status").headers(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(statusBody("SUSPENDED", "사유")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SELLER_NOT_FOUND"));

        assertThat(sellerStatus(S_TERMINATED)).isEqualTo("TERMINATED");
        assertThat(sellerStatus(S_ACTIVE)).isEqualTo("ACTIVE");
        assertThat(sellerStatus(S_PENDING)).isEqualTo("PENDING");
        for (long sellerId : SELLER_IDS) {
            assertThat(auditRows(sellerId)).isEmpty();
            assertThat(withdrawnCount(sellerId)).isZero();
        }
    }

    // ==================== T7 카탈로그 연동 ====================

    @Test
    @DisplayName("T7 카탈로그 연동: ACTIVE→SUSPENDED 후 구매자 목록 0건·상세 404 → ACTIVE 복귀 후 재노출(상품 자동 변경 없이 쿼리 조건만)")
    void catalog_hiddenOnSuspend_shownOnResume() throws Exception {
        mockMvc.perform(get(CATALOG_URL).param("keyword", "89D상품A")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2));
        mockMvc.perform(get(CATALOG_URL + "/" + pid("prd_", "89DPA1"))).andExpect(status().isOk());

        transition(S_ACTIVE, "SUSPENDED", "정지").andExpect(status().isOk());
        mockMvc.perform(get(CATALOG_URL).param("keyword", "89D상품A")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0));
        mockMvc.perform(get(CATALOG_URL + "/" + pid("prd_", "89DPA1"))).andExpect(status().isNotFound());

        transition(S_ACTIVE, "ACTIVE", "해제").andExpect(status().isOk());
        mockMvc.perform(get(CATALOG_URL).param("keyword", "89D상품A")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2));
    }

    // ==================== T8 정보 수정 ====================

    @Test
    @DisplayName("T8 PUT 수정: 연락처만 204·감사(사유 없음) / 율 변경 사유 없음 400 / 율+사유 204·감사 reason / 무변경 감사 skip / 사업자번호 중복 409 / 율 범위 400 / 404")
    void update_fields() throws Exception {
        mockMvc.perform(put(URL + "/" + pid(S_ACTIVE)).headers(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("89D셀러A", BUSINESS_NO_A, 1200, null, "02-2000-0000", null)))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT contact_phone FROM seller WHERE id = ?", String.class, S_ACTIVE))
                .isEqualTo("02-2000-0000");
        List<Map<String, Object>> audits = auditRows(S_ACTIVE);
        assertThat(audits).hasSize(1);
        assertThat((String) audits.get(0).get("diff_json")).contains("contactPhone").doesNotContain("reason");

        mockMvc.perform(put(URL + "/" + pid(S_ACTIVE)).headers(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("89D셀러A", BUSINESS_NO_A, 1500, null, "02-2000-0000", null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        assertThat(jdbc.queryForObject("SELECT commission_rate FROM seller WHERE id = ?", Integer.class, S_ACTIVE)).isEqualTo(1200);

        mockMvc.perform(put(URL + "/" + pid(S_ACTIVE)).headers(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("89D셀러A", BUSINESS_NO_A, 1500, "협의율 변경", "02-2000-0000", null)))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT commission_rate FROM seller WHERE id = ?", Integer.class, S_ACTIVE)).isEqualTo(1500);
        audits = auditRows(S_ACTIVE);
        assertThat(audits).hasSize(2);
        assertThat((String) audits.get(1).get("diff_json")).contains("commissionRate").contains("1500").contains("협의율 변경");

        // null 환원(미설정) + 사유
        mockMvc.perform(put(URL + "/" + pid(S_ACTIVE)).headers(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("89D셀러A", BUSINESS_NO_A, null, "개별 계약 해지", "02-2000-0000", null)))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT commission_rate FROM seller WHERE id = ?", Integer.class, S_ACTIVE)).isNull();

        // 무변경 → 204·감사 skip(3건 유지)
        mockMvc.perform(put(URL + "/" + pid(S_ACTIVE)).headers(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("89D셀러A", BUSINESS_NO_A, null, null, "02-2000-0000", null)))
                .andExpect(status().isNoContent());
        assertThat(auditRows(S_ACTIVE)).hasSize(3);

        // 사업자번호 중복(8905) 409·불변
        mockMvc.perform(put(URL + "/" + pid(S_ACTIVE)).headers(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("89D셀러A", BUSINESS_NO_BUSY, null, null, "02-2000-0000", null)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELLER_BUSINESS_NO_DUPLICATE"));
        assertThat(jdbc.queryForObject("SELECT business_no FROM seller WHERE id = ?", String.class, S_ACTIVE)).isEqualTo(BUSINESS_NO_A);

        // 범위·형식 400
        mockMvc.perform(put(URL + "/" + pid(S_ACTIVE)).headers(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("89D셀러A", BUSINESS_NO_A, 10_001, "사유", "02-2000-0000", null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("commissionRate"));
        mockMvc.perform(put(URL + "/" + pid(S_ACTIVE)).headers(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("89D셀러A", BUSINESS_NO_A, null, null, null, "not-an-email")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(put(URL + "/slr_NOPE00000000000000000000000").headers(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("x", null, null, null, null, null)))
                .andExpect(status().isNotFound());
    }

    // ==================== T9 비-ACTIVE 셀러 상품 등록 ====================

    @Test
    @DisplayName("T9 관리자 상품 등록: SUSPENDED·PENDING·TERMINATED 셀러 → 422 SELLER_INVALID_STATE·product 미생성 / ACTIVE 201")
    void adminProductCreate_nonActiveSeller_returns422() throws Exception {
        for (long sellerId : new long[] {S_SUSPENDED, S_PENDING, S_TERMINATED}) {
            mockMvc.perform(post("/api/v1/admin/products").headers(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content(productCreateBody(pid(sellerId), "89D등록차단상품")))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("SELLER_INVALID_STATE"));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE name = '89D등록차단상품'", Integer.class)).isZero();

        mockMvc.perform(post("/api/v1/admin/products").headers(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(productCreateBody(pid(S_ACTIVE), "89D등록허용상품")))
                .andExpect(status().isCreated());
    }

    // ==================== helpers ====================

    private HttpHeaders admin() {
        return authHeaders.admin(ADMIN_ID);
    }

    private ResultActions transition(long sellerId, String status, String reason) throws Exception {
        return mockMvc.perform(patch(URL + "/" + pid(sellerId) + "/status").headers(admin())
                .contentType(MediaType.APPLICATION_JSON).content(statusBody(status, reason)));
    }

    private void assertBlocked(long sellerId, Map<String, Long> expected) throws Exception {
        JsonNode detail = readJson(mockMvc.perform(get(URL + "/" + pid(sellerId)).headers(admin())).andExpect(status().isOk()));
        assertThat(detail.get("terminable").asBoolean()).isFalse();
        assertThat(blocks(detail.get("terminationBlocks"))).isEqualTo(expected);
        JsonNode conflict = readJson(transition(sellerId, "TERMINATED", "종료").andExpect(status().isConflict()));
        assertThat(blocks(conflict.get("blocks"))).isEqualTo(expected);
        assertThat(sellerStatus(sellerId)).isEqualTo("ACTIVE");
    }

    private void assertWithdrawnArchive(long sellerId, String reason) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT terminate_reason, legal_retention_until, anonymized_at FROM withdrawn_seller WHERE original_seller_id = ?",
                sellerId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("terminate_reason")).isEqualTo(reason);
        LocalDate retention = ((Timestamp) rows.get(0).get("legal_retention_until")).toLocalDateTime().toLocalDate();
        assertThat(retention).isEqualTo(LocalDate.now().plusYears(5));
        assertThat(rows.get(0).get("anonymized_at")).isNull();
        assertThat(sellerStatus(sellerId)).isEqualTo("TERMINATED");
        List<Map<String, Object>> audits = auditRows(sellerId);
        assertThat((String) audits.get(audits.size() - 1).get("diff_json")).contains("TERMINATED").contains(reason);
    }

    private static Map<String, Long> blocks(JsonNode array) {
        java.util.Map<String, Long> result = new java.util.HashMap<>();
        for (JsonNode node : array) {
            result.put(node.get("code").asText(), node.get("count").asLong());
        }
        return result;
    }

    private static JsonNode findRow(JsonNode page, String sellerPublicId) {
        for (JsonNode row : page.get("items")) {
            if (row.get("sellerPublicId").asText().equals(sellerPublicId)) {
                return row;
            }
        }
        throw new AssertionError("목록에 없음: " + sellerPublicId);
    }

    private static JsonNode findMember(JsonNode detail, String userPublicId) {
        for (JsonNode member : detail.get("members")) {
            if (userPublicId.equals(member.get("userPublicId").asText())) {
                return member;
            }
        }
        throw new AssertionError("구성원에 없음: " + userPublicId);
    }

    private JsonNode readJson(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private static String statusBody(String status, String reason) {
        return "{\"status\":\"" + status + "\",\"reason\":\"" + reason + "\"}";
    }

    private static String updateBody(String companyName, String businessNo, Integer commissionRate, String reason) {
        return updateBody(companyName, businessNo, commissionRate, reason, "02-1000-8901", "seller-a@89d.test");
    }

    private static String updateBody(String companyName, String businessNo, Integer commissionRate, String reason,
            String contactPhone, String contactEmail) {
        return "{\"companyName\":\"" + companyName + "\","
                + "\"businessNo\":" + json(businessNo) + ","
                + "\"ceoName\":\"대표A\","
                + "\"contactEmail\":" + json(contactEmail) + ","
                + "\"contactPhone\":" + json(contactPhone) + ","
                + "\"commissionRate\":" + (commissionRate == null ? "null" : commissionRate) + ","
                + "\"reason\":" + json(reason) + "}";
    }

    private static String productCreateBody(String sellerPublicId, String name) {
        return "{\"sellerPublicId\":\"" + sellerPublicId + "\",\"categoryId\":" + CATEGORY_ID + ",\"name\":\"" + name + "\","
                + "\"description\":\"설명\",\"basePrice\":10000,\"supplyPrice\":7000,\"thumbnailUrl\":\"/api/v1/files/products/2026/09/new.jpg\","
                + "\"saleStartAt\":null,\"saleEndAt\":null,\"optionGroups\":[],"
                + "\"variants\":[{\"variantCode\":\"SKU-89D\",\"additionalPrice\":0,\"displayOrder\":0,\"initialStock\":10,\"optionKeys\":[]}]}";
    }

    private static String json(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private String sellerStatus(long sellerId) {
        return jdbc.queryForObject("SELECT status FROM seller WHERE id = ?", String.class, sellerId);
    }

    private int withdrawnCount(long sellerId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM withdrawn_seller WHERE original_seller_id = ?",
                Integer.class, sellerId);
        return count == null ? 0 : count;
    }

    private List<Map<String, Object>> auditRows(long sellerId) {
        return jdbc.queryForList("SELECT action, diff_json FROM audit_log WHERE target_type = 'SELLER' AND target_id = ? "
                + "AND actor_user_id = ? ORDER BY id", sellerId, ADMIN_ID);
    }

    private static String pid(long sellerId) {
        return pid("slr_", "89DS" + sellerId);
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }

    private void updateWithoutFk(String sql, Object... args) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update(sql, args);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    // ---------- seed (모든 INSERT는 ? positional 바인딩·정적 SQL) ----------

    private void seedAll() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedUser(OWNER_USER_ID, pid("usr_", "89DOWN"), "owner@89d.test", "오너", null);
                seedUser(WITHDRAWN_USER_ID, pid("usr_", "89DWDR"), "manager@89d.test", "매니저",
                        Timestamp.valueOf("2026-09-01 00:00:00"));
                seedUser(ORDER_BUYER_ID, pid("usr_", "89DBUY"), "buyer@89d.test", "구매자", null);

                seedSeller(S_ACTIVE, "89D셀러A", BUSINESS_NO_A, "대표A", "seller-a@89d.test", "02-1000-8901", "ACTIVE", 1200, 1);
                seedSeller(S_PENDING, "89D셀러P", null, "대표P", null, null, "PENDING", null, 2);
                seedSeller(S_SUSPENDED, "89D셀러S", null, "대표S", null, null, "SUSPENDED", null, 3);
                seedSeller(S_TERMINATED, "89D셀러T", null, "대표T", null, null, "TERMINATED", null, 4);
                seedSeller(S_BUSY, "89D셀러B", BUSINESS_NO_BUSY, "대표B", "seller-b@89d.test", null, "ACTIVE", null, 5);
                seedSeller(S_CLEAN, "89D셀러C", null, "대표C", null, null, "ACTIVE", null, 6);
                seedSeller(S_PENDING2, "89D셀러Q", null, "대표Q", null, null, "PENDING", null, 7);

                jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                        + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'", OWNER_USER_ID, S_ACTIVE);
                jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                        + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_MANAGER'", WITHDRAWN_USER_ID, S_ACTIVE);

                jdbc.update("INSERT INTO seller_bank_account (id, seller_id, bank_code, account_number, account_holder, "
                        + "is_primary, status, verified_at, created_at, updated_at) "
                        + "VALUES (?, ?, 'KB', ?, '대표A', 1, 'VERIFIED', NOW(6), NOW(6), NOW(6))",
                        BANK_ACCOUNT_A, S_ACTIVE, bankAccountEncryptor.encrypt("1234567890123456"));

                jdbc.update("INSERT INTO category (id, display_name, depth, sort_order, created_at, updated_at) "
                        + "VALUES (?, '89D카테고리', 1, 0, NOW(6), NOW(6))", CATEGORY_ID);
                seedProduct(PRODUCT_A1, pid("prd_", "89DPA1"), S_ACTIVE, "89D상품A1", "SALE");
                seedProduct(PRODUCT_A2, pid("prd_", "89DPA2"), S_ACTIVE, "89D상품A2", "SALE");
                seedProduct(PRODUCT_A3, pid("prd_", "89DPA3"), S_ACTIVE, "89D상품A3", "STOPPED");
                seedProduct(PRODUCT_B1, pid("prd_", "89DPB1"), S_BUSY, "89D상품B1", "SALE");
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SKU-89D-A1', 0, 'SALE', 0, 1, 1, NOW(6), NOW(6))",
                        VARIANT_A1, pid("var_", "89DVA1"), PRODUCT_A1);
                jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, "
                        + "created_at, updated_at) VALUES (?, ?, 10, 0, 10, NOW(6), NOW(6))", VARIANT_A1, VARIANT_A1);

                // 주문·품목: 8901 CONFIRMED / 8906 PAYMENT_EXPIRED(ORDERED 트랩) + CONFIRMED / 8905 PAID 2 + PAYMENT_EXPIRED(ORDERED 트랩)
                seedOrder(ORDER_CONFIRMED, "CONFIRMED");
                seedOrderItem(ITEM_A_CONFIRMED, ORDER_CONFIRMED, PRODUCT_A1, VARIANT_A1, S_ACTIVE, "CONFIRMED", CONFIRMED_AMOUNT_A, true);
                seedOrder(ORDER_EXPIRED_CLEAN, "PAYMENT_EXPIRED");
                seedOrderItem(ITEM_CLEAN_EXPIRED, ORDER_EXPIRED_CLEAN, PRODUCT_A1, VARIANT_A1, S_CLEAN, "ORDERED", 10_000L, false);
                seedOrder(ORDER_PAID_BUSY, "PAID");
                seedOrderItem(ITEM_BUSY_PAID_1, ORDER_PAID_BUSY, PRODUCT_B1, VARIANT_A1, S_BUSY, "PAID", 10_000L, false);
                seedOrderItem(ITEM_BUSY_PAID_2, ORDER_PAID_BUSY, PRODUCT_B1, VARIANT_A1, S_BUSY, "PAID", 10_000L, false);
                seedOrder(ORDER_EXPIRED_BUSY, "PAYMENT_EXPIRED");
                seedOrderItem(ITEM_BUSY_EXPIRED, ORDER_EXPIRED_BUSY, PRODUCT_B1, VARIANT_A1, S_BUSY, "ORDERED", 10_000L, false);
                seedOrder(ORDER_CONFIRMED_CLEAN, "CONFIRMED");
                seedOrderItem(ITEM_CLEAN_CONFIRMED, ORDER_CONFIRMED_CLEAN, PRODUCT_A1, VARIANT_A1, S_CLEAN, "CONFIRMED", 20_000L, true);

                seedClaim(CLAIM_COMPLETED, ITEM_CLEAN_CONFIRMED, "COMPLETED");
                seedClaim(CLAIM_REQUESTED, ITEM_BUSY_PAID_1, "REQUESTED");

                seedSettlement(SETTLEMENT_A_PAID, S_ACTIVE, "PAID", CONFIRMED_AMOUNT_A, 4_500L, 8);
                seedSettlement(SETTLEMENT_BUSY_PENDING, S_BUSY, "PENDING", 10_000L, 1_000L, 8);
                seedSettlement(SETTLEMENT_BUSY_CONFIRMED, S_BUSY, "CONFIRMED", 10_000L, 1_000L, 7);
                seedSettlement(SETTLEMENT_CLEAN_PAID, S_CLEAN, "PAID", 20_000L, 2_000L, 8);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedUser(long id, String publicId, String email, String name, Timestamp withdrawnAt) {
        jdbc.update("INSERT INTO `user` (id, public_id, email, name, withdrawn_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))", id, publicId, email, name, withdrawnAt);
    }

    private void seedSeller(long id, String companyName, String businessNo, String ceoName, String email, String phone,
            String status, Integer commissionRate, int dayOfJanuary) {
        jdbc.update("INSERT INTO seller (id, public_id, company_name, business_no, ceo_name, contact_email, contact_phone, "
                + "status, commission_rate, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(6))",
                id, pid(id), companyName, businessNo, ceoName, email, phone, status, commissionRate,
                Timestamp.valueOf(LocalDateTime.of(2026, 1, dayOfJanuary, 10, 0)));
    }

    private void seedProduct(long id, String publicId, long sellerId, String name, String status) {
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, 10000, NOW(6), NOW(6))", id, publicId, sellerId, CATEGORY_ID, name, status);
    }

    private void seedOrder(long id, String status) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 0, 0, 0, NOW(6), NOW(6))",
                id, pid("ord_", "89DO" + id), ORDER_BUYER_ID, "89D-" + id, status);
    }

    private void seedOrderItem(long id, long orderId, long productId, long variantId, long sellerId, String itemStatus,
            long totalPrice, boolean confirmed) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                + "total_price, item_status, confirmed_at, product_name, commission_rate, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, '89D품목', 1000, NOW(6), NOW(6))",
                id, pid("oit_", "89DI" + id), orderId, productId, variantId, sellerId, totalPrice, totalPrice, itemStatus,
                confirmed ? Timestamp.valueOf("2026-08-15 10:00:00") : null);
    }

    private void seedClaim(long id, long orderItemId, String status) {
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, reason_detail, status, "
                + "previous_order_item_status, requested_by, requested_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'CANCEL', 'BUYER_CHANGED_MIND', '사유', ?, 'PAID', ?, NOW(6), NOW(6), NOW(6))",
                id, pid("clm_", "89DC" + id), orderItemId, status, ORDER_BUYER_ID);
    }

    /** uk_settlement_seller_period(셀러·기간 UNIQUE)를 피하기 위해 month(7·8)로 기간을 나눈다. */
    private void seedSettlement(long id, long sellerId, String status, long gross, long fee, int month) {
        LocalDate periodStart = LocalDate.of(2026, month, 1);
        LocalDate periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth());
        jdbc.update("INSERT INTO settlement (id, seller_id, bank_account_id, period_start, period_end, gross_amount, fee_amount, "
                + "refund_amount, net_amount, status, paid_at, scheduled_pay_date, created_at, updated_at) "
                + "VALUES (?, ?, NULL, ?, ?, ?, ?, 0, ?, ?, ?, ?, NOW(6), NOW(6))",
                id, sellerId, Timestamp.valueOf(periodStart.atStartOfDay()), Timestamp.valueOf(periodEnd.atTime(23, 59, 59)),
                gross, fee, gross - fee, status,
                "PAID".equals(status) ? Timestamp.valueOf("2026-09-20 10:00:00") : null,
                java.sql.Date.valueOf(periodEnd.plusDays(20)));
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM audit_log WHERE actor_user_id = ?", ADMIN_ID);
                jdbc.update("DELETE FROM withdrawn_seller WHERE original_seller_id BETWEEN 8901 AND 8907");
                jdbc.update("DELETE FROM claim WHERE id BETWEEN 8941 AND 8949");
                jdbc.update("DELETE FROM settlement WHERE id BETWEEN 8951 AND 8959");
                jdbc.update("DELETE FROM order_item WHERE id BETWEEN 8931 AND 8939");
                jdbc.update("DELETE FROM `order` WHERE id BETWEEN 8921 AND 8929");
                jdbc.update("DELETE FROM inventory WHERE variant_id IN (SELECT id FROM product_variant WHERE product_id IN "
                        + "(SELECT id FROM product WHERE seller_id BETWEEN 8901 AND 8907))");
                jdbc.update("DELETE FROM product_variant WHERE product_id IN (SELECT id FROM product WHERE seller_id BETWEEN 8901 AND 8907)");
                jdbc.update("DELETE FROM product WHERE seller_id BETWEEN 8901 AND 8907");
                jdbc.update("DELETE FROM category WHERE id = ?", CATEGORY_ID);
                jdbc.update("DELETE FROM seller_bank_account WHERE seller_id BETWEEN 8901 AND 8907");
                jdbc.update("DELETE FROM seller_user WHERE seller_id BETWEEN 8901 AND 8907");
                jdbc.update("DELETE FROM seller WHERE id BETWEEN 8901 AND 8907");
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?, ?)", OWNER_USER_ID, WITHDRAWN_USER_ID, ORDER_BUYER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }
}
