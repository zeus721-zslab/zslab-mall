package com.zslab.mall.claim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.event.ClaimApproved;
import com.zslab.mall.claim.event.ClaimRejected;
import com.zslab.mall.claim.service.ClaimService;
import com.zslab.mall.order.enums.OrderItemStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.zslab.mall.common.security.AuthHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 클레임 E2E 통합 테스트(실 MariaDB·Flyway V1~V5·CheckoutIntegrationTest 패턴 1:1·Track 9 PR-B Phase 2·D-89).
 * 실제 컨트롤러 → {@link ClaimService} → Repository → DB 흐름으로 요청·조회·승인/거절·권한 격리를 검증해 라이브 트랩을 차단한다.
 *
 * <p><b>트랜잭션</b>: 단일 트랜잭션(@Transactional) + {@code SET FOREIGN_KEY_CHECKS=0}으로 상위 그래프(user·product·variant·
 * seller) 없이 order·order_item·claim만 시딩한다(CheckoutIntegrationTest FK 비활성 패턴 준용). 테스트 종료 시 롤백된다.
 *
 * <p><b>LT-02 처치(Q9 α·live-traps.md)</b>: {@code SET FOREIGN_KEY_CHECKS=0}은 try-finally로 {@code =1} 복원과 1:1 짝을
 * 이뤄 HikariCP 커넥션 풀 잔류·후속 테스트 FK 오염을 차단한다. 기존 통합 테스트(Checkout·RefundWebhook)는 복원을 두지 않아
 * 본 테스트가 LT-02 try-finally 첫 명시 사례다.
 *
 * <p><b>이벤트 검증(D-29)</b>: approve/reject의 save→publish 발행은 동기 {@link EventListener} 레코더({@link ClaimEventRecorder})로
 * 포착한다. 승인/거절 endpoint는 Track 10 소관이므로 T7·T8은 Service 직접 호출이다(D-88 Q2·RefundWebhook initiate 패턴 정합).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ClaimIntegrationTest {

    private static final long BUYER_A = 8001L;
    private static final long BUYER_B = 8002L;

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
    private ClaimService claimService;

    @Autowired
    private ClaimEventRecorder recorder;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void resetRecorder() {
        recorder.clear();
    }

    // ===== T1~T4·T13·T15: 요청(POST /api/v1/claims) =====

    @Test
    @DisplayName("T1 요청: PAID 품목·본인·CANCEL → 201·REQUESTED·Location·claim INSERT 1건")
    void request_paidCancel_returns201AndInserts() throws Exception {
        long orderId = 7101L;
        long orderItemId = 7111L;
        String orderItemPid = pid("oit_", "T1PAID");
        seed(() -> {
            seedOrder(orderId, pid("ord_", "T1ORD"), BUYER_A);
            seedOrderItem(orderItemId, orderItemPid, orderId, OrderItemStatus.PAID);
        });

        mockMvc.perform(post("/api/v1/claims").headers(authHeaders.buyer(BUYER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(orderItemPid, "CANCEL", "BUYER_CHANGED_MIND")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", Matchers.startsWith("/api/v1/claims/clm_")))
                .andExpect(jsonPath("$.orderItemPublicId").value(orderItemPid))
                .andExpect(jsonPath("$.claimType").value("CANCEL"))
                .andExpect(jsonPath("$.status").value("REQUESTED"));

        assertThat(claimCount(orderItemId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("T2 요청: CONFIRMED 품목(전이 불가) → 422·CLAIM_STATE_INVALID·INSERT 없음")
    void request_confirmedItem_returns422() throws Exception {
        long orderId = 7201L;
        long orderItemId = 7211L;
        String orderItemPid = pid("oit_", "T2CONF");
        seed(() -> {
            seedOrder(orderId, pid("ord_", "T2ORD"), BUYER_A);
            seedOrderItem(orderItemId, orderItemPid, orderId, OrderItemStatus.CONFIRMED);
        });

        mockMvc.perform(post("/api/v1/claims").headers(authHeaders.buyer(BUYER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(orderItemPid, "CANCEL", "BUYER_CHANGED_MIND")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLAIM_STATE_INVALID"));

        assertThat(claimCount(orderItemId)).isZero();
    }

    @Test
    @DisplayName("T3 요청: 타 buyer 품목 → 404·CLAIM_NOT_FOUND(정보 누출 차단·Q8)·INSERT 없음")
    void request_otherBuyerItem_returns404() throws Exception {
        long orderId = 7301L;
        long orderItemId = 7311L;
        String orderItemPid = pid("oit_", "T3OTH");
        seed(() -> {
            seedOrder(orderId, pid("ord_", "T3ORD"), BUYER_B); // 주문 소유자는 B
            seedOrderItem(orderItemId, orderItemPid, orderId, OrderItemStatus.PAID);
        });

        mockMvc.perform(post("/api/v1/claims").headers(authHeaders.buyer(BUYER_A)) // 요청자는 A
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(orderItemPid, "CANCEL", "BUYER_CHANGED_MIND")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));

        assertThat(claimCount(orderItemId)).isZero();
    }

    @Test
    @DisplayName("T4 요청: X-Buyer-Id 누락 → 401·UNAUTHENTICATED")
    void request_missingBuyerId_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(pid("oit_", "T4"), "CANCEL", "BUYER_CHANGED_MIND")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("T13 요청: RETURN·DELIVERED → 201(D-98 Q4 게이트 제거)·RETURN·PAID → 422(전이 불가·type별 검증)")
    void request_returnType_transitionAware() throws Exception {
        long orderId = 7131L;
        long deliveredItemId = 7132L;
        long paidItemId = 7133L;
        String deliveredPid = pid("oit_", "T13DLV");
        String paidPid = pid("oit_", "T13PAID");
        seed(() -> {
            seedOrder(orderId, pid("ord_", "T13ORD"), BUYER_A);
            seedOrderItem(deliveredItemId, deliveredPid, orderId, OrderItemStatus.DELIVERED);
            seedOrderItem(paidItemId, paidPid, orderId, OrderItemStatus.PAID);
        });

        // 게이트 제거 후 type 무관 진입 허용·DELIVERED는 RETURN_REQUESTED 전이 가능(D-98 Q4·Q7) → 201
        mockMvc.perform(post("/api/v1/claims").headers(authHeaders.buyer(BUYER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(deliveredPid, "RETURN", "BUYER_CHANGED_MIND")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.claimType").value("RETURN"))
                .andExpect(jsonPath("$.status").value("REQUESTED"));

        // PAID는 RETURN_REQUESTED 전이 불가(매트릭스) → 422(type 게이트가 아닌 전이 검증으로 차단)
        mockMvc.perform(post("/api/v1/claims").headers(authHeaders.buyer(BUYER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(paidPid, "RETURN", "BUYER_CHANGED_MIND")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLAIM_STATE_INVALID"));

        assertThat(claimCount(deliveredItemId)).isEqualTo(1L);
        assertThat(claimCount(paidItemId)).isZero();
    }

    @Test
    @DisplayName("T15 요청: 활성 클레임(APPROVED) 존재 상태 재요청 → 422·CLAIM_STATE_INVALID(CLM-5)·신규 INSERT 없음")
    void request_existingActiveClaim_returns422() throws Exception {
        long orderId = 7151L;
        long orderItemId = 7152L;
        long activeClaimId = 7153L;
        String orderItemPid = pid("oit_", "T15");
        seed(() -> {
            seedOrder(orderId, pid("ord_", "T15ORD"), BUYER_A);
            seedOrderItem(orderItemId, orderItemPid, orderId, OrderItemStatus.PAID);
            // APPROVED = 활성 클레임(CLM-5). 동일 OrderItem 재요청 차단 대상.
            seedClaim(activeClaimId, pid("clm_", "T15CLM"), orderItemId, ClaimType.CANCEL,
                    ClaimStatus.APPROVED, BUYER_A, "활성 클레임");
        });

        mockMvc.perform(post("/api/v1/claims").headers(authHeaders.buyer(BUYER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(orderItemPid, "CANCEL", "BUYER_CHANGED_MIND")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLAIM_STATE_INVALID"));

        assertThat(claimCount(orderItemId)).isEqualTo(1L); // 신규 행 없음·기존 1건 유지
    }

    @Test
    @DisplayName("T9 요청: REJECTED 후 동일 품목 재요청 → 201·새 REQUESTED 행(이전 REJECTED 보존·CLM-2)")
    void request_afterRejected_returns201NewRow() throws Exception {
        long orderId = 7901L;
        long orderItemId = 7902L;
        long rejectedClaimId = 7903L;
        String orderItemPid = pid("oit_", "T9");
        seed(() -> {
            seedOrder(orderId, pid("ord_", "T9ORD"), BUYER_A);
            seedOrderItem(orderItemId, orderItemPid, orderId, OrderItemStatus.PAID);
            // REJECTED = 비활성(CLM-2). 재요청이 허용되어야 한다.
            seedClaim(rejectedClaimId, pid("clm_", "T9CLM"), orderItemId, ClaimType.CANCEL,
                    ClaimStatus.REJECTED, BUYER_A, "거절 이력");
        });

        mockMvc.perform(post("/api/v1/claims").headers(authHeaders.buyer(BUYER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(orderItemPid, "CANCEL", "ORDER_MISTAKE")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED"));

        assertThat(claimCount(orderItemId)).isEqualTo(2L); // REJECTED 보존 + 신규 REQUESTED
        assertThat(claimCountByStatus(orderItemId, "REJECTED")).isEqualTo(1L);
        assertThat(claimCountByStatus(orderItemId, "REQUESTED")).isEqualTo(1L);
    }

    @Test
    @DisplayName("T10 요청: 동일 품목 연속 요청 → 1차 201·2차 422(REQUESTED 활성·CLM-5)·총 1건")
    void request_duplicateActive_secondReturns422() throws Exception {
        long orderId = 7011L;
        long orderItemId = 7012L;
        String orderItemPid = pid("oit_", "T10");
        seed(() -> {
            seedOrder(orderId, pid("ord_", "T10ORD"), BUYER_A);
            seedOrderItem(orderItemId, orderItemPid, orderId, OrderItemStatus.PAID);
        });

        mockMvc.perform(post("/api/v1/claims").headers(authHeaders.buyer(BUYER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(orderItemPid, "CANCEL", "BUYER_CHANGED_MIND")))
                .andExpect(status().isCreated());

        // 1차 REQUESTED는 활성 → 2차는 CLM-5로 차단(단일 스레드 순차 검증·race 본질).
        mockMvc.perform(post("/api/v1/claims").headers(authHeaders.buyer(BUYER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(orderItemPid, "CANCEL", "DUPLICATE_ORDER")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLAIM_STATE_INVALID"));

        assertThat(claimCount(orderItemId)).isEqualTo(1L);
    }

    // ===== T5·T17-4: 단건 조회(GET /api/v1/claims/{claimPublicId}) =====

    @Test
    @DisplayName("T5 단건: 본인 클레임 → 200·publicId·reasonDetail 노출·requestedBy 미노출(Q7)")
    void getOne_ownClaim_returns200_hidesRequestedBy() throws Exception {
        long orderId = 7501L;
        long orderItemId = 7502L;
        long claimId = 7503L;
        String orderItemPid = pid("oit_", "T5OIT");
        String claimPid = pid("clm_", "T5CLM");
        seed(() -> {
            seedOrder(orderId, pid("ord_", "T5ORD"), BUYER_A);
            seedOrderItem(orderItemId, orderItemPid, orderId, OrderItemStatus.PAID);
            seedClaim(claimId, claimPid, orderItemId, ClaimType.CANCEL, ClaimStatus.REQUESTED,
                    BUYER_A, "상세 사유 노출");
        });

        mockMvc.perform(get("/api/v1/claims/" + claimPid).headers(authHeaders.buyer(BUYER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(claimPid))
                .andExpect(jsonPath("$.orderItemPublicId").value(orderItemPid))
                .andExpect(jsonPath("$.reasonDetail").value("상세 사유 노출"))
                .andExpect(jsonPath("$.requestedBy").doesNotExist()); // 내부 buyerId 누출 차단(Q7)
    }

    @Test
    @DisplayName("T17-4 단건: 타 buyer 클레임 직접 접근 → 404·CLAIM_NOT_FOUND(J1·정보 누출 차단)")
    void getOne_otherBuyerClaim_returns404() throws Exception {
        long orderId = 7741L;
        long orderItemId = 7742L;
        long claimId = 7743L;
        String claimPid = pid("clm_", "T174");
        seed(() -> {
            seedOrder(orderId, pid("ord_", "T174ORD"), BUYER_B);
            seedOrderItem(orderItemId, pid("oit_", "T174OIT"), orderId, OrderItemStatus.PAID);
            seedClaim(claimId, claimPid, orderItemId, ClaimType.CANCEL, ClaimStatus.REQUESTED,
                    BUYER_B, "타인 클레임"); // 소유자 B
        });

        mockMvc.perform(get("/api/v1/claims/" + claimPid).headers(authHeaders.buyer(BUYER_A))) // 접근자 A
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
    }

    // ===== T6·T17-1·T17-2·T17-3: 목록 조회(GET /api/v1/claims) =====

    @Test
    @DisplayName("T6 목록: 본인 클레임 → 200·PagedResponse 구조(items·page·size·totalCount·hasNext)")
    void list_ownClaims_returnsPaged() throws Exception {
        long orderId = 7601L;
        long orderItemId = 7602L;
        long claimId = 7603L;
        String claimPid = pid("clm_", "T6CLM");
        seed(() -> {
            seedOrder(orderId, pid("ord_", "T6ORD"), BUYER_A);
            seedOrderItem(orderItemId, pid("oit_", "T6OIT"), orderId, OrderItemStatus.PAID);
            seedClaim(claimId, claimPid, orderItemId, ClaimType.CANCEL, ClaimStatus.REQUESTED,
                    BUYER_A, "목록 시드");
        });

        mockMvc.perform(get("/api/v1/claims").headers(authHeaders.buyer(BUYER_A))
                        .param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].publicId").value(claimPid))
                .andExpect(jsonPath("$.items[0].status").value("REQUESTED"));
    }

    @Test
    @DisplayName("T17-1 목록: buyerA 조회 → buyerA 데이터만(buyerB 클레임 부재·totalCount 1)")
    void list_buyerIsolation_onlyOwnData() throws Exception {
        long orderA = 7711L;
        long orderItemA = 7712L;
        long claimA = 7713L;
        long orderB = 7714L;
        long orderItemB = 7715L;
        long claimB = 7716L;
        String claimAPid = pid("clm_", "T171A");
        seed(() -> {
            seedOrder(orderA, pid("ord_", "T171OA"), BUYER_A);
            seedOrderItem(orderItemA, pid("oit_", "T171IA"), orderA, OrderItemStatus.PAID);
            seedClaim(claimA, claimAPid, orderItemA, ClaimType.CANCEL, ClaimStatus.REQUESTED, BUYER_A, "A");
            seedOrder(orderB, pid("ord_", "T171OB"), BUYER_B);
            seedOrderItem(orderItemB, pid("oit_", "T171IB"), orderB, OrderItemStatus.PAID);
            seedClaim(claimB, pid("clm_", "T171B"), orderItemB, ClaimType.CANCEL, ClaimStatus.REQUESTED, BUYER_B, "B");
        });

        mockMvc.perform(get("/api/v1/claims").headers(authHeaders.buyer(BUYER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1)) // B 클레임 미포함
                .andExpect(jsonPath("$.items[0].publicId").value(claimAPid));
    }

    @Test
    @DisplayName("T17-2 목록: buyerA size=100·buyerB 클레임 존재 → 누출 0건(totalCount 2·B publicId 부재)")
    void list_buyerIsolation_size100_noLeak() throws Exception {
        long orderA = 7721L;
        long orderItemA = 7722L;
        long orderB = 7725L;
        long orderItemB = 7726L;
        long claimB = 7727L;
        String claimBPid = pid("clm_", "T172B");
        seed(() -> {
            seedOrder(orderA, pid("ord_", "T172OA"), BUYER_A);
            seedOrderItem(orderItemA, pid("oit_", "T172IA"), orderA, OrderItemStatus.PAID);
            seedClaim(7723L, pid("clm_", "T172A1"), orderItemA, ClaimType.CANCEL, ClaimStatus.REQUESTED, BUYER_A, "A1");
            seedClaim(7724L, pid("clm_", "T172A2"), orderItemA, ClaimType.CANCEL, ClaimStatus.REJECTED, BUYER_A, "A2");
            seedOrder(orderB, pid("ord_", "T172OB"), BUYER_B);
            seedOrderItem(orderItemB, pid("oit_", "T172IB"), orderB, OrderItemStatus.PAID);
            seedClaim(claimB, claimBPid, orderItemB, ClaimType.CANCEL, ClaimStatus.REQUESTED, BUYER_B, "B");
        });

        mockMvc.perform(get("/api/v1/claims").headers(authHeaders.buyer(BUYER_A)).param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items[*].publicId", Matchers.not(Matchers.hasItem(claimBPid))));
    }

    @Test
    @DisplayName("T17-3 목록: sort 파라미터 미노출(Q4)·권한 필터 우선 → 200·본인 데이터만·sort 무시")
    void list_sortParamIgnored_filteredByBuyer() throws Exception {
        long orderA = 7731L;
        long orderItemA = 7732L;
        long orderB = 7735L;
        long orderItemB = 7736L;
        seed(() -> {
            seedOrder(orderA, pid("ord_", "T173OA"), BUYER_A);
            seedOrderItem(orderItemA, pid("oit_", "T173IA"), orderA, OrderItemStatus.PAID);
            seedClaim(7733L, pid("clm_", "T173A1"), orderItemA, ClaimType.CANCEL, ClaimStatus.REQUESTED, BUYER_A, "A1");
            seedClaim(7734L, pid("clm_", "T173A2"), orderItemA, ClaimType.CANCEL, ClaimStatus.REJECTED, BUYER_A, "A2");
            seedOrder(orderB, pid("ord_", "T173OB"), BUYER_B);
            seedOrderItem(orderItemB, pid("oit_", "T173IB"), orderB, OrderItemStatus.PAID);
            seedClaim(7737L, pid("clm_", "T173B"), orderItemB, ClaimType.CANCEL, ClaimStatus.REQUESTED, BUYER_B, "B");
        });

        // sort 파라미터는 컨트롤러가 받지 않으므로 무시된다(Q4 서버 고정 정렬). 권한 필터(requested_by=A)가 우선 적용된다.
        mockMvc.perform(get("/api/v1/claims").headers(authHeaders.buyer(BUYER_A))
                        .param("sort", "requestedAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2)) // A의 2건만·B 미포함
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    // ===== T7·T8: 승인/거절(Service 직접 호출·이벤트 발행 검증·D-29) =====

    @Test
    @DisplayName("T7 승인: ClaimService.approve → APPROVED 전이·ClaimApproved 발행(D-29 save→publish)")
    void approve_serviceDirect_transitionsAndPublishes() {
        long orderId = 7071L;
        long orderItemId = 7072L;
        long claimId = 7073L;
        seed(() -> {
            seedOrder(orderId, pid("ord_", "T7ORD"), BUYER_A);
            seedOrderItem(orderItemId, pid("oit_", "T7OIT"), orderId, OrderItemStatus.PAID);
            seedClaim(claimId, pid("clm_", "T7CLM"), orderItemId, ClaimType.CANCEL, ClaimStatus.REQUESTED,
                    BUYER_A, "승인 대상");
        });

        claimService.approve(claimId, LocalDateTime.now(), null);
        entityManager.flush();

        assertThat(claimStatus(claimId)).isEqualTo("APPROVED");
        assertThat(recorder.approved()).hasSize(1);
        assertThat(recorder.approved().get(0).claimId()).isEqualTo(claimId);
        assertThat(recorder.approved().get(0).status()).isEqualTo(ClaimStatus.APPROVED);
    }

    @Test
    @DisplayName("T8 거절: ClaimService.reject → REJECTED 전이·ClaimRejected 발행(CLM-2 이력 보존)")
    void reject_serviceDirect_transitionsAndPublishes() {
        long orderId = 7081L;
        long orderItemId = 7082L;
        long claimId = 7083L;
        seed(() -> {
            seedOrder(orderId, pid("ord_", "T8ORD"), BUYER_A);
            seedOrderItem(orderItemId, pid("oit_", "T8OIT"), orderId, OrderItemStatus.PAID);
            seedClaim(claimId, pid("clm_", "T8CLM"), orderItemId, ClaimType.CANCEL, ClaimStatus.REQUESTED,
                    BUYER_A, "거절 대상");
        });

        claimService.reject(claimId, LocalDateTime.now());
        entityManager.flush();

        assertThat(claimStatus(claimId)).isEqualTo("REJECTED");
        assertThat(recorder.rejected()).hasSize(1);
        assertThat(recorder.rejected().get(0).claimId()).isEqualTo(claimId);
        assertThat(recorder.rejected().get(0).status()).isEqualTo(ClaimStatus.REJECTED);
    }

    // ===== 시드·검증 헬퍼 =====

    /**
     * FK 비활성 상태로 시드 작업을 수행하고 {@code SET FOREIGN_KEY_CHECKS=1}로 복원한다(LT-02 try-finally·Q9 α).
     * 복원 누락 시 HikariCP 커넥션 풀에 FK 비활성이 잔류해 후속 테스트를 오염시킨다.
     */
    private void seed(Runnable seedingWork) {
        try {
            execute("SET FOREIGN_KEY_CHECKS = 0");
            seedingWork.run();
        } finally {
            execute("SET FOREIGN_KEY_CHECKS = 1");
        }
        entityManager.flush();
        entityManager.clear();
    }

    // 모든 시드 INSERT는 ?n positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).
    private void seedOrder(long id, String publicId, long buyerId) {
        entityManager.createNativeQuery(
                        "INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                                + "discount_amount, shipping_fee, created_at, updated_at) "
                                + "VALUES (?1, ?2, ?3, ?4, 'PAID', 10000, 0, 0, NOW(6), NOW(6))")
                .setParameter(1, id)
                .setParameter(2, publicId)
                .setParameter(3, buyerId)
                .setParameter(4, "ORDIT" + id)
                .executeUpdate();
    }

    private void seedOrderItem(long id, String publicId, long orderId, OrderItemStatus itemStatus) {
        entityManager.createNativeQuery(
                        "INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                                + "quantity, unit_price, total_price, item_status, created_at, updated_at) "
                                + "VALUES (?1, ?2, ?3, 1, 1, 1, 1, 10000, 10000, ?4, NOW(6), NOW(6))")
                .setParameter(1, id)
                .setParameter(2, publicId)
                .setParameter(3, orderId)
                .setParameter(4, itemStatus.name())
                .executeUpdate();
    }

    private void seedClaim(long id, String publicId, long orderItemId, ClaimType type, ClaimStatus status,
            long requestedBy, String reasonDetail) {
        entityManager.createNativeQuery(
                        "INSERT INTO claim (id, public_id, order_item_id, type, reason_code, reason_detail, status, "
                                + "previous_order_item_status, requested_by, requested_at, created_at, updated_at) "
                                + "VALUES (?1, ?2, ?3, ?4, 'BUYER_CHANGED_MIND', ?5, ?6, 'PAID', ?7, NOW(6), NOW(6), NOW(6))")
                .setParameter(1, id)
                .setParameter(2, publicId)
                .setParameter(3, orderItemId)
                .setParameter(4, type.name())
                .setParameter(5, reasonDetail)
                .setParameter(6, status.name())
                .setParameter(7, requestedBy)
                .executeUpdate();
    }

    private void execute(String sql) {
        entityManager.createNativeQuery(sql).executeUpdate();
    }

    private long claimCount(long orderItemId) {
        return ((Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM claim WHERE order_item_id = ?1")
                .setParameter(1, orderItemId).getSingleResult()).longValue();
    }

    private long claimCountByStatus(long orderItemId, String status) {
        return ((Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM claim WHERE order_item_id = ?1 AND status = ?2")
                .setParameter(1, orderItemId).setParameter(2, status).getSingleResult()).longValue();
    }

    private String claimStatus(long claimId) {
        return (String) entityManager.createNativeQuery(
                        "SELECT status FROM claim WHERE id = ?1")
                .setParameter(1, claimId).getSingleResult();
    }

    private String requestBody(String orderItemPublicId, String claimType, String reasonCode) {
        return """
                {
                  "orderItemPublicId": "%s",
                  "claimType": "%s",
                  "reasonCode": "%s",
                  "reasonDetail": "통합 테스트"
                }
                """.formatted(orderItemPublicId, claimType, reasonCode);
    }

    /** prefix(예: {@code oit_}) + 26자 본문(tag 대문자 + '0' 패딩·[0-9A-Z])으로 30자 public_id를 만든다(@Pattern 정합). */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }

    /**
     * approve/reject의 동기 이벤트 발행을 포착하는 테스트 레코더(@EventListener spy·D-29). 발행은 동기이므로 테스트
     * 트랜잭션 내에서 즉시 누적된다. 소비 핸들러는 PR-C 소관이라 운영 코드에 부재하므로 검증 전용으로 등록한다.
     */
    @TestConfiguration
    static class RecorderConfig {
        @Bean
        ClaimEventRecorder claimEventRecorder() {
            return new ClaimEventRecorder();
        }
    }

    static class ClaimEventRecorder {
        private final List<ClaimApproved> approved = new CopyOnWriteArrayList<>();
        private final List<ClaimRejected> rejected = new CopyOnWriteArrayList<>();

        @EventListener
        void onApproved(ClaimApproved event) {
            approved.add(event);
        }

        @EventListener
        void onRejected(ClaimRejected event) {
            rejected.add(event);
        }

        List<ClaimApproved> approved() {
            return approved;
        }

        List<ClaimRejected> rejected() {
            return rejected;
        }

        void clear() {
            approved.clear();
            rejected.clear();
        }
    }
}
