package com.zslab.mall.claim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.claim.controller.request.ClaimRequestCommand;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimReasonCode;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.service.ClaimService;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.notification.adapter.SmsSender;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RETURN 클레임 전체 루프 E2E 통합 테스트(Track 14 PR-1 → Track 81-A D-170 확장·실 MariaDB·Flyway V24·실 MockPaymentGateway). 요청 조건(기한·
 * 사유·DELIVERED) → 승인 → 구매자 회수 송장 → 회수 확인 → 검수 PASS/FAIL → 환불 자동 완료(Mock 콜백) → RETURNED·재고 분기 / 거부·원복·재발송을
 * 실제 커밋·AFTER_COMMIT 핸들러 체인으로 구동한다(라이브 트랩 차단).
 *
 * <p><b>트랜잭션</b>: AFTER_COMMIT 핸들러는 실제 커밋 후 실행되므로 클래스에 {@code @Transactional}을 두지 않는다. 시드/정리는
 * {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}, 검증은 {@link JdbcTemplate} 직접 조회로 한다.
 *
 * <p><b>SMS</b>: {@link SmsSender}를 {@link MockitoBean}으로 대체해 승인·완료·불합격 거부 시점 발송을 검증한다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "zslab.order.auto-cancel.enabled=false",
        "zslab.refund.recovery.enabled=false",
        "zslab.order.expired-cleanup.enabled=false",
        "zslab.order.auto-confirm.enabled=false"
})
class ClaimReturnIntegrationTest extends AbstractIntegrationTest {

    private static final long ADMIN_ID = 9300L;
    private static final long USER_ID = 9302L;
    private static final long OTHER_USER_ID = 9303L;
    private static final long SELLER_ID = 9302L;
    private static final long SELLER_USER_ID = 9304L;
    private static final long PRODUCT_ID = 9302L;
    private static final long VARIANT_ID = 9302L;
    private static final long INVENTORY_ID = 9302L;
    private static final long ORDER_ID = 9302L;
    private static final long ORDER_ITEM_ID = 9302L;
    private static final long PAYMENT_ID = 9302L;
    private static final long OUTBOUND_DELIVERY_ID = 9302L;
    private static final long DUMMY_FK_ID = 9302L;
    private static final long ITEM_PRICE = 10_000L;
    private static final int ON_HAND = 8;
    private static final int THREADS = 4;
    private static final String BUYER_PHONE = "010-3333-4444";

    private static final String ORDER_ITEM_PID = pid("oit_", "RTNOIT");
    private static final String CLAIMS_URL = "/api/v1/claims";
    private static final String ADMIN_CLAIMS_URL = "/api/v1/admin/claims";
    private static final String RETURN_SHIPMENT_BODY = "{\"carrier\":\"CJ\",\"trackingNo\":\"RTN-TRACK-0001\"}";
    private static final String INSPECT_PASS_RESTOCK = "{\"result\":\"PASS\",\"restock\":true}";
    private static final String ATTACHMENTS_URL = CLAIMS_URL + "/attachments";
    private static final String ATT_PID_UNKNOWN = "att_" + ("RTNATTX" + "00000000000000000000000000").substring(0, 26);

    /** 반품 사진 저장 루트(Track 81-B). upload.path를 임시 디렉터리로 돌려 실 업로드 볼륨을 건드리지 않는다. */
    @TempDir
    static Path uploadRoot;

    @DynamicPropertySource
    static void uploadPath(DynamicPropertyRegistry registry) {
        registry.add("upload.path", () -> uploadRoot.toString());
    }
    private static final String INSPECT_PASS_NO_RESTOCK = "{\"result\":\"PASS\",\"restock\":false}";
    private static final String INSPECT_FAIL = "{\"result\":\"FAIL\",\"rejectReasonCode\":\"INSPECTION_FAILED\",\"memo\":\"사용 흔적\","
            + "\"reshipCarrier\":\"HANJIN\",\"reshipTrackingNo\":\"RESHIP-0001\"}";

    @MockitoBean
    private SmsSender smsSender;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private ClaimService claimService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        doNothing().when(smsSender).send(any(), any());
        cleanup();
        seed(() -> {
            seedCatalog();
            seedOrder("DELIVERED");
            seedOrderItem(OrderItemStatus.DELIVERED);
            seedPayment();
            seedOutboundDelivered(1);
        });
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    // ===== 요청 조건(R1·R2) =====

    @Test
    @DisplayName("T1 요청 조건: 배송완료 1일 → 201 / 8일 경과 → 422 / 사유 STOCK_DELAY → 422 / CONFIRMED 품목 → 422")
    void request_windowReasonConfirmedGuards() throws Exception {
        mockMvc.perform(post(CLAIMS_URL).headers(authHeaders.buyer(USER_ID)).contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("RETURN", "STOCK_DELAY")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLAIM_STATE_INVALID"));

        mockMvc.perform(post(CLAIMS_URL).headers(authHeaders.buyer(USER_ID)).contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("RETURN", "PRODUCT_DEFECT")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.claimType").value("RETURN"))
                .andExpect(jsonPath("$.returnShipmentRequired").value(false)); // 아직 REQUESTED
        assertThat(orderItemStatus()).isEqualTo("RETURN_REQUESTED");
        verify(smsSender).send(eq(BUYER_PHONE), contains("반품 요청이 접수되었습니다."));

        // 기한 경과: 별도 품목 없이 같은 품목을 되돌리고 배송완료를 8일 전으로 이동
        jdbc.update("DELETE FROM claim WHERE order_item_id = ?", ORDER_ITEM_ID);
        jdbc.update("UPDATE order_item SET item_status = 'DELIVERED' WHERE id = ?", ORDER_ITEM_ID);
        jdbc.update("UPDATE delivery SET delivered_at = NOW(6) - INTERVAL 8 DAY WHERE id = ?", OUTBOUND_DELIVERY_ID);
        mockMvc.perform(post(CLAIMS_URL).headers(authHeaders.buyer(USER_ID)).contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("RETURN", "BUYER_CHANGED_MIND")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLAIM_STATE_INVALID"));

        // 구매확정 품목은 종결 상태라 전이 불가(기존 매트릭스)
        jdbc.update("UPDATE order_item SET item_status = 'CONFIRMED', confirmed_at = NOW(6) WHERE id = ?", ORDER_ITEM_ID);
        jdbc.update("UPDATE delivery SET delivered_at = NOW(6) - INTERVAL 1 DAY WHERE id = ?", OUTBOUND_DELIVERY_ID);
        mockMvc.perform(post(CLAIMS_URL).headers(authHeaders.buyer(USER_ID)).contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("RETURN", "BUYER_CHANGED_MIND")))
                .andExpect(status().isUnprocessableEntity());
        assertThat(claimCount()).isZero();
    }

    // ===== 회수 송장(R4) =====

    @Test
    @DisplayName("T2 회수 송장: REQUESTED 422 → 승인(승인 SMS) → 타인 404 → 본인 200(RETURN Delivery·품목 상태 불변) → 중복 422 → 사용자 응답 returnShipment")
    void returnShipment_ownerOnlyOnceAfterApproval() throws Exception {
        Long claimId = requestReturn();
        String claimPid = claimPid(claimId);

        mockMvc.perform(post(CLAIMS_URL + "/" + claimPid + "/return-shipment").headers(authHeaders.buyer(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(RETURN_SHIPMENT_BODY))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(post(ADMIN_CLAIMS_URL + "/" + claimPid + "/approve").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        verify(smsSender).send(eq(BUYER_PHONE), contains("반품 요청이 승인되었습니다."));
        assertThat(refundCount(claimId)).isZero(); // 승인만으로 환불 없음(RETURN)

        mockMvc.perform(get(CLAIMS_URL + "/" + claimPid).headers(authHeaders.buyer(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnShipmentRequired").value(true))
                .andExpect(jsonPath("$.returnShipment").doesNotExist());

        mockMvc.perform(post(CLAIMS_URL + "/" + claimPid + "/return-shipment").headers(authHeaders.buyer(OTHER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(RETURN_SHIPMENT_BODY))
                .andExpect(status().isNotFound());

        mockMvc.perform(post(CLAIMS_URL + "/" + claimPid + "/return-shipment").headers(authHeaders.buyer(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(RETURN_SHIPMENT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.direction").value("RETURN"))
                .andExpect(jsonPath("$.status").value("SHIPPING"))
                .andExpect(jsonPath("$.trackingNo").value("RTN-TRACK-0001"));
        assertThat(returnDeliveryCount(claimId)).isEqualTo(1);
        assertThat(orderItemStatus()).isEqualTo("RETURN_REQUESTED"); // 발송 핸들러 무반응(direction=RETURN)

        mockMvc.perform(post(CLAIMS_URL + "/" + claimPid + "/return-shipment").headers(authHeaders.buyer(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"carrier\":\"CJ\",\"trackingNo\":\"RTN-TRACK-0002\"}"))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(get(CLAIMS_URL + "/" + claimPid).headers(authHeaders.buyer(USER_ID)))
                .andExpect(jsonPath("$.returnShipmentRequired").value(false))
                .andExpect(jsonPath("$.returnShipment.trackingNo").value("RTN-TRACK-0001"));
    }

    // ===== 회수 확인·검수 PASS(R5) =====

    @Test
    @DisplayName("T3 전 루프 PASS(restock): 회수 확인 전 검수 422 → 회수 송장 없이 확인 422 → 송장 → 관리자 회수 확인(환불 0) → 검수 PASS → 환불 자동 완료·RETURNED·재고 +1·완료 SMS")
    void fullLoop_passWithRestock() throws Exception {
        Long claimId = approvedReturn();
        String claimPid = claimPid(claimId);

        mockMvc.perform(post(ADMIN_CLAIMS_URL + "/" + claimPid + "/inspect").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(INSPECT_PASS_RESTOCK))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post(ADMIN_CLAIMS_URL + "/" + claimPid + "/confirm-pickup").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isUnprocessableEntity()); // 회수 송장 부재

        registerReturnShipment(claimPid);
        mockMvc.perform(post(ADMIN_CLAIMS_URL + "/" + claimPid + "/confirm-pickup").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pickedUpAt").exists());
        assertThat(refundCount(claimId)).isZero(); // 수거 확인만으로 환불 발생하지 않음(Track 81-A 트리거 이동)
        assertThat(returnDeliveryStatus(claimId)).isEqualTo("DELIVERED");
        assertThat(orderItemStatus()).isEqualTo("RETURN_REQUESTED"); // 회수 DELIVERED가 품목을 바꾸지 않음

        // 관리자 목록 액션: 회수 확인 후 미검수 → INSPECT
        mockMvc.perform(get(ADMIN_CLAIMS_URL).headers(authHeaders.admin(ADMIN_ID)).param("type", "RETURN"))
                .andExpect(jsonPath("$.items[0].availableActions[0]").value("INSPECT"))
                .andExpect(jsonPath("$.items[0].returnShipment.trackingNo").value("RTN-TRACK-0001"));

        mockMvc.perform(post(ADMIN_CLAIMS_URL + "/" + claimPid + "/inspect").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(INSPECT_PASS_RESTOCK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inspectionResult").value("PASS"))
                .andExpect(jsonPath("$.status").value("COMPLETED")); // Mock 자동 콜백으로 요청 스레드 내 수렴

        assertThat(refundStatus(claimId)).isEqualTo("COMPLETED");
        assertThat(claimStatus(claimId)).isEqualTo("COMPLETED");
        assertThat(orderItemStatus()).isEqualTo("RETURNED");
        assertThat(onHand()).isEqualTo(ON_HAND + 1);
        assertThat(historyCount("RETURN")).isEqualTo(1);
        verify(smsSender).send(eq(BUYER_PHONE), contains("반품 및 환불이 완료되었습니다."));
        // 사용자 상세(FE-29): PASS는 재발송 없음(null → 필드 생략)
        mockMvc.perform(get(CLAIMS_URL + "/" + claimPid).headers(authHeaders.buyer(USER_ID)))
                .andExpect(jsonPath("$.reshipment").doesNotExist())
                .andExpect(jsonPath("$.inspectionResult").value("PASS"));

        // 재검수 422
        mockMvc.perform(post(ADMIN_CLAIMS_URL + "/" + claimPid + "/inspect").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(INSPECT_PASS_RESTOCK))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("T4 PASS(restock=false·불량 폐기): 환불 완료·RETURNED·재고 불변·history 0")
    void pass_noRestock_keepsInventory() throws Exception {
        Long claimId = pickedUpReturn();
        String claimPid = claimPid(claimId);

        mockMvc.perform(post(ADMIN_CLAIMS_URL + "/" + claimPid + "/inspect").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(INSPECT_PASS_NO_RESTOCK))
                .andExpect(status().isOk());

        assertThat(refundStatus(claimId)).isEqualTo("COMPLETED");
        assertThat(orderItemStatus()).isEqualTo("RETURNED");
        assertThat(onHand()).isEqualTo(ON_HAND);
        assertThat(historyCount("RETURN")).isZero();
        assertThat(jdbc.queryForObject("SELECT restock FROM claim WHERE id = ?", Integer.class, claimId)).isZero();
    }

    // ===== 검수 FAIL(R5) =====

    @Test
    @DisplayName("T5 FAIL: 조건부 필수 누락 400 → FAIL(사유·재발송) → REJECTED·품목 DELIVERED 원복·재발송 OUTBOUND Delivery·환불 0·거부 SMS(검수 불합격)")
    void fail_rejectsRestoresReships() throws Exception {
        Long claimId = pickedUpReturn();
        String claimPid = claimPid(claimId);

        mockMvc.perform(post(ADMIN_CLAIMS_URL + "/" + claimPid + "/inspect").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"FAIL\",\"rejectReasonCode\":\"INSPECTION_FAILED\"}"))
                .andExpect(status().isBadRequest()); // 재발송 택배사·송장 누락
        mockMvc.perform(post(ADMIN_CLAIMS_URL + "/" + claimPid + "/inspect").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"PASS\"}"))
                .andExpect(status().isBadRequest()); // restock 누락
        assertThat(claimStatus(claimId)).isEqualTo("APPROVED");

        mockMvc.perform(post(ADMIN_CLAIMS_URL + "/" + claimPid + "/inspect").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(INSPECT_FAIL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.inspectionResult").value("FAIL"))
                .andExpect(jsonPath("$.rejectReasonCode").value("INSPECTION_FAILED"))
                .andExpect(jsonPath("$.rejectMemo").value("사용 흔적"));

        assertThat(claimStatus(claimId)).isEqualTo("REJECTED");
        assertThat(orderItemStatus()).isEqualTo("DELIVERED"); // 스냅샷 원복
        assertThat(refundCount(claimId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM delivery WHERE claim_id = ? AND direction = 'OUTBOUND' AND status = 'SHIPPING' "
                + "AND tracking_no = 'RESHIP-0001'", Integer.class, claimId)).isEqualTo(1);
        assertThat(onHand()).isEqualTo(ON_HAND);
        verify(smsSender).send(eq(BUYER_PHONE), contains("반품 요청이 거부되었습니다. 사유: 검수 불합격"));

        // 사용자 상세(FE-29): 검수 불합격 재발송 송장 노출·회수 송장 유지
        mockMvc.perform(get(CLAIMS_URL + "/" + claimPid).headers(authHeaders.buyer(USER_ID)))
                .andExpect(jsonPath("$.reshipment.direction").value("OUTBOUND"))
                .andExpect(jsonPath("$.reshipment.carrier").value("HANJIN"))
                .andExpect(jsonPath("$.reshipment.trackingNo").value("RESHIP-0001"))
                .andExpect(jsonPath("$.returnShipment.trackingNo").value("RTN-TRACK-0001"));

        // 관리자 주문 상세 클레임 행: 회수 송장·검수 결과 노출
        mockMvc.perform(get("/api/v1/admin/orders/" + pid("ord_", "RTNORD")).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(jsonPath("$.items[0].claims[0].inspectionResult").value("FAIL"))
                .andExpect(jsonPath("$.items[0].claims[0].returnTrackingNo").value("RTN-TRACK-0001"))
                .andExpect(jsonPath("$.items[0].delivery.trackingNo").value("RESHIP-0001")); // 품목 배송 = 최신 발송(OUTBOUND) = 재발송·회수(RETURN)는 제외

        // FAIL 이력 품목 반품 재요청 → 422(기한 안·DELIVERED여도 차단)
        mockMvc.perform(post(CLAIMS_URL).headers(authHeaders.buyer(USER_ID)).contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("RETURN", "PRODUCT_DEFECT")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLAIM_STATE_INVALID"));
        assertThat(claimCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("T8 기한 기준 = 원 주문 발송: 재발송(claim_id 있음)이 오늘 배송완료돼도 원 발송 8일 경과면 422·FAIL 이력이 없어도 차단")
    void window_usesOriginalOutboundOnly() throws Exception {
        // 원 발송은 8일 전 완료, 클레임 연결 발송(교환/재발송 성격·claim_id 있음)은 오늘 완료 → 기한 판정은 원 발송만
        jdbc.update("UPDATE delivery SET delivered_at = NOW(6) - INTERVAL 8 DAY WHERE id = ?", OUTBOUND_DELIVERY_ID);
        // 클레임 연결 발송(claim_id는 FK라 FOREIGN_KEY_CHECKS=0 시드·실 클레임 불필요)
        seed(() -> jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, direction, carrier, tracking_no, status, shipped_at, "
                        + "delivered_at, claim_id, created_at, updated_at) VALUES (?, ?, ?, 'OUTBOUND', 'CJ', 'RESHIP-TODAY', 'DELIVERED', "
                        + "NOW(6) - INTERVAL 1 HOUR, NOW(6), ?, NOW(6), NOW(6))",
                OUTBOUND_DELIVERY_ID + 1, pid("dlv_", "RTNDLV2"), ORDER_ITEM_ID, 999_999L));

        mockMvc.perform(post(CLAIMS_URL).headers(authHeaders.buyer(USER_ID)).contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("RETURN", "BUYER_CHANGED_MIND")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLAIM_STATE_INVALID"));
        assertThat(claimCount()).isZero();

        // 원 발송이 기한 안이면 클레임 연결 발송과 무관하게 201
        jdbc.update("UPDATE delivery SET delivered_at = NOW(6) - INTERVAL 1 DAY WHERE id = ?", OUTBOUND_DELIVERY_ID);
        mockMvc.perform(post(CLAIMS_URL).headers(authHeaders.buyer(USER_ID)).contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("RETURN", "BUYER_CHANGED_MIND")))
                .andExpect(status().isCreated());
    }

    // ===== 동시 검수·권한 =====

    @Test
    @DisplayName("T6 동시 검수 4스레드(PASS) → 1건만 200·나머지 422·환불 1건")
    void concurrentInspect_onlyOneSucceeds() throws Exception {
        Long claimId = pickedUpReturn();
        String claimPid = claimPid(claimId);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            Callable<Integer> worker = () -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return mockMvc.perform(post(ADMIN_CLAIMS_URL + "/" + claimPid + "/inspect").headers(authHeaders.admin(ADMIN_ID))
                                .contentType(MediaType.APPLICATION_JSON).content(INSPECT_PASS_RESTOCK))
                        .andReturn().getResponse().getStatus();
            };
            results.add(pool.submit(worker));
        }
        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : results) {
            statuses.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertThat(statuses.stream().filter(status -> status == 200).count()).isEqualTo(1L);
        assertThat(statuses.stream().filter(status -> status == 422).count()).isEqualTo(THREADS - 1L);
        assertThat(refundCount(claimId)).isEqualTo(1);
        assertThat(onHand()).isEqualTo(ON_HAND + 1);
    }

    @Test
    @DisplayName("T7 권한: 소유 셀러 회수 확인·검수 403(Track 92 셀러 처리 endpoint 제거) / 셀러 회수 송장 403")
    void authorization_sellerScopeAndRoles() throws Exception {
        Long claimId = approvedReturn();
        String claimPid = claimPid(claimId);
        registerReturnShipment(claimPid);

        mockMvc.perform(post(CLAIMS_URL + "/" + claimPid + "/confirm-pickup").headers(authHeaders.seller(SELLER_USER_ID)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(CLAIMS_URL + "/" + claimPid + "/inspect").headers(authHeaders.seller(SELLER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(INSPECT_PASS_RESTOCK))
                .andExpect(status().isForbidden());
        // BUYER 토큰의 /api/v1/claims/{id}/inspect는 경로 부재(NoResourceFoundException → GEH catch-all 500·LT-27)라 인가 단언 대상이 아니다.
        // 매핑 부재는 ClaimProcessingMappingAbsenceTest가 감시한다(제거된 처리 매핑·경로 변경·역할 무관 부활 → RED).
        mockMvc.perform(post(CLAIMS_URL + "/" + claimPid + "/return-shipment").headers(authHeaders.seller(SELLER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(RETURN_SHIPMENT_BODY))
                .andExpect(status().isForbidden());
        assertThat(claimStatus(claimId)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT picked_up_at FROM claim WHERE id = ?", LocalDateTime.class, claimId)).isNull();
    }

    // ===== 반품 사진 첨부(Track 81-B D-171) =====

    @Test
    @DisplayName("T9 첨부: 업로드 2장(미연결·uploaded_by) → 불량 사유 요청에 [2,1] 순서 연결·응답 attachmentUrls → 상세·관리자 목록 개수·주문 상세 URL → 재사용 400")
    void attachments_uploadLinkAndExpose() throws Exception {
        String first = uploadOne(USER_ID, "defect-1.png");
        String second = uploadOne(USER_ID, "defect-2.png");
        assertThat(jdbc.queryForObject("SELECT target_id FROM attachment WHERE public_id = ?", Long.class, first)).isNull();
        assertThat(jdbc.queryForObject("SELECT uploaded_by FROM attachment WHERE public_id = ?", Long.class, first)).isEqualTo(USER_ID);
        String secondUrl = jdbc.queryForObject("SELECT file_path FROM attachment WHERE public_id = ?", String.class, second);
        assertThat(secondUrl).startsWith("/api/v1/files/claims/");

        mockMvc.perform(post(CLAIMS_URL).headers(authHeaders.buyer(USER_ID)).contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("RETURN", "PRODUCT_DEFECT", second, first)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachmentUrls.length()").value(2))
                .andExpect(jsonPath("$.attachmentUrls[0]").value(secondUrl));
        Long claimId = jdbc.queryForObject("SELECT id FROM claim WHERE order_item_id = ?", Long.class, ORDER_ITEM_ID);
        assertThat(jdbc.queryForObject("SELECT display_order FROM attachment WHERE public_id = ?", Integer.class, second)).isZero();
        assertThat(jdbc.queryForObject("SELECT display_order FROM attachment WHERE public_id = ?", Integer.class, first)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT target_id FROM attachment WHERE public_id = ?", Long.class, first)).isEqualTo(claimId);

        mockMvc.perform(get(CLAIMS_URL + "/" + claimPid(claimId)).headers(authHeaders.buyer(USER_ID)))
                .andExpect(jsonPath("$.attachmentUrls.length()").value(2))
                .andExpect(jsonPath("$.attachmentUrls[0]").value(secondUrl));
        mockMvc.perform(get(ADMIN_CLAIMS_URL).headers(authHeaders.admin(ADMIN_ID)).param("type", "RETURN"))
                .andExpect(jsonPath("$.items[0].attachmentCount").value(2));
        mockMvc.perform(get("/api/v1/admin/orders/" + pid("ord_", "RTNORD")).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(jsonPath("$.items[0].claims[0].attachmentUrls.length()").value(2))
                .andExpect(jsonPath("$.items[0].claims[0].attachmentUrls[0]").value(secondUrl));
        // D-176 인가 서빙: 익명 404·클레임 소유 구매자 200·ADMIN 200(연결 첨부)
        mockMvc.perform(get(secondUrl)).andExpect(status().isNotFound());
        mockMvc.perform(get(secondUrl).headers(authHeaders.buyer(USER_ID))).andExpect(status().isOk());
        mockMvc.perform(get(secondUrl).headers(authHeaders.admin(ADMIN_ID))).andExpect(status().isOk());

        // 이미 연결된 첨부 재사용 → 400(첨부 검증이 품목 상태 검증보다 앞)
        mockMvc.perform(post(CLAIMS_URL).headers(authHeaders.buyer(USER_ID)).contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("RETURN", "PRODUCT_DEFECT", first)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("T10 첨부 400/403: 단순변심 첨부·CANCEL 첨부·타인 파일·미존재·중복 id·6장 업로드 → 400 / SELLER 업로드 403 / 첨부 없는 불량 요청 201")
    void attachments_rejections() throws Exception {
        String mine = uploadOne(USER_ID, "mine.png");
        String others = uploadOne(OTHER_USER_ID, "others.png");

        for (String body : List.of(
                requestBody("RETURN", "BUYER_CHANGED_MIND", mine),
                requestBody("CANCEL", "PRODUCT_DEFECT", mine),
                requestBody("RETURN", "WRONG_PRODUCT", others),
                requestBody("RETURN", "WRONG_PRODUCT", ATT_PID_UNKNOWN),
                requestBody("RETURN", "WRONG_PRODUCT", mine, mine))) {
            mockMvc.perform(post(CLAIMS_URL).headers(authHeaders.buyer(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(claimCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT target_id FROM attachment WHERE public_id = ?", Long.class, mine)).isNull();

        MockMultipartHttpServletRequestBuilder tooMany = multipart(ATTACHMENTS_URL);
        for (int i = 0; i < 6; i++) {
            tooMany.file(new MockMultipartFile("files", "f" + i + ".png", "image/png", png()));
        }
        mockMvc.perform(tooMany.headers(authHeaders.buyer(USER_ID))).andExpect(status().isBadRequest());
        mockMvc.perform(multipart(ATTACHMENTS_URL).file(new MockMultipartFile("files", "s.png", "image/png", png()))
                        .headers(authHeaders.seller(SELLER_USER_ID)))
                .andExpect(status().isForbidden());

        // 사진은 선택 입력: 첨부 없는 불량 반품은 그대로 201
        mockMvc.perform(post(CLAIMS_URL).headers(authHeaders.buyer(USER_ID)).contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("RETURN", "PRODUCT_DEFECT")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachmentUrls.length()").value(0));
    }

    /** 구매자 사진 1장 업로드 → attachmentId(att_). */
    private String uploadOne(long userId, String fileName) throws Exception {
        String body = mockMvc.perform(multipart(ATTACHMENTS_URL).file(new MockMultipartFile("files", fileName, "image/png", png()))
                        .headers(authHeaders.buyer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1))
                .andReturn().getResponse().getContentAsString();
        return com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(body)
                .get("results").get(0).get("attachmentId").asText();
    }

    private static byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.ORANGE);
        graphics.fillRect(0, 0, 8, 8);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    // ---------- 흐름 헬퍼 ----------

    private Long requestReturn() {
        Claim claim = claimService.request(new ClaimRequestCommand(
                ORDER_ITEM_PID, ClaimType.RETURN, ClaimReasonCode.PRODUCT_DEFECT, "하자", USER_ID, LocalDateTime.now()));
        return claim.getId();
    }

    private Long approvedReturn() {
        Long claimId = requestReturn();
        claimService.approve(claimId, LocalDateTime.now(), null);
        return claimId;
    }

    private Long pickedUpReturn() throws Exception {
        Long claimId = approvedReturn();
        registerReturnShipment(claimPid(claimId));
        claimService.confirmPickupByAdmin(claimId, LocalDateTime.now());
        return claimId;
    }

    private void registerReturnShipment(String claimPid) throws Exception {
        mockMvc.perform(post(CLAIMS_URL + "/" + claimPid + "/return-shipment").headers(authHeaders.buyer(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(RETURN_SHIPMENT_BODY))
                .andExpect(status().isOk());
    }

    private String requestBody(String type, String reasonCode) {
        return "{\"orderItemPublicId\":\"" + ORDER_ITEM_PID + "\",\"claimType\":\"" + type + "\",\"reasonCode\":\"" + reasonCode + "\"}";
    }

    private String requestBody(String type, String reasonCode, String... attachmentIds) {
        String ids = String.join(",", java.util.Arrays.stream(attachmentIds).map(id -> "\"" + id + "\"").toList());
        return "{\"orderItemPublicId\":\"" + ORDER_ITEM_PID + "\",\"claimType\":\"" + type + "\",\"reasonCode\":\"" + reasonCode
                + "\",\"attachmentIds\":[" + ids + "]}";
    }

    // ---------- seed·helpers ----------

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
        jdbc.update("INSERT INTO `user` (id, public_id, email, name, phone, created_at, updated_at) VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))",
                USER_ID, pid("usr_", "RTNUSR"), "rtn@example.test", "반품구매자", BUYER_PHONE);
        jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                OTHER_USER_ID, pid("usr_", "RTNUSR2"));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, '통합셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                SELLER_ID, pid("slr_", "RTNSLR"));
        seedSellerUser(SELLER_USER_ID, SELLER_ID);
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, '통합상품', 'SALE', 10000, NOW(6), NOW(6))",
                PRODUCT_ID, pid("prd_", "RTNPRD"), SELLER_ID, DUMMY_FK_ID);
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VCRTN', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                VARIANT_ID, pid("var_", "RTNVAR"), PRODUCT_ID, DUMMY_FK_ID);
        jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 0, ?, NOW(6), NOW(6))",
                INVENTORY_ID, VARIANT_ID, ON_HAND, ON_HAND);
    }

    private void seedSellerUser(long userId, long sellerId) {
        jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'", userId, sellerId);
    }

    private void seedOrder(String status) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                        + "discount_amount, shipping_fee, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, 0, NOW(6), NOW(6))",
                ORDER_ID, pid("ord_", "RTNORD"), USER_ID, "ORDRTN" + ORDER_ID, status, ITEM_PRICE);
    }

    private void seedOrderItem(OrderItemStatus itemStatus) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                        + "quantity, unit_price, total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(6), NOW(6), '테스트 상품', 1000)",
                ORDER_ITEM_ID, ORDER_ITEM_PID, ORDER_ID, PRODUCT_ID, VARIANT_ID, SELLER_ID,
                ITEM_PRICE, ITEM_PRICE, itemStatus.name());
    }

    private void seedPayment() {
        jdbc.update("INSERT INTO payment (id, public_id, order_id, method, amount, status, pg_provider, pg_tid, "
                        + "payment_attempt_key, paid_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CARD', ?, 'PAID', 'MOCK_PG', 'tid_rtn_0001', 'pat_rtn_0001', NOW(6), NOW(6), NOW(6))",
                PAYMENT_ID, pid("pay_", "RTNPAY"), ORDER_ID, ITEM_PRICE);
    }

    /** 발송(OUTBOUND) 배송완료 Delivery — 반품 기한 기준(delivered_at = 지금 − daysAgo일). */
    private void seedOutboundDelivered(int daysAgo) {
        jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, direction, carrier, tracking_no, status, shipped_at, delivered_at, "
                        + "created_at, updated_at) VALUES (?, ?, ?, 'OUTBOUND', 'CJ', 'OUT-TRACK-0001', 'DELIVERED', "
                        + "NOW(6) - INTERVAL ? DAY - INTERVAL 1 DAY, NOW(6) - INTERVAL ? DAY, NOW(6), NOW(6))",
                OUTBOUND_DELIVERY_ID, pid("dlv_", "RTNDLV"), ORDER_ITEM_ID, daysAgo, daysAgo);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", USER_ID);
                jdbc.update("DELETE FROM attachment WHERE uploaded_by IN (?, ?)", USER_ID, OTHER_USER_ID);
                jdbc.update("DELETE FROM refund WHERE claim_id IN (SELECT id FROM claim WHERE order_item_id = ?)", ORDER_ITEM_ID);
                jdbc.update("DELETE FROM delivery WHERE order_item_id = ?", ORDER_ITEM_ID);
                jdbc.update("DELETE FROM claim WHERE order_item_id = ?", ORDER_ITEM_ID);
                jdbc.update("DELETE FROM inventory_history WHERE inventory_id = ?", INVENTORY_ID);
                jdbc.update("DELETE FROM inventory WHERE id = ?", INVENTORY_ID);
                jdbc.update("DELETE FROM payment WHERE id = ?", PAYMENT_ID);
                jdbc.update("DELETE FROM order_item WHERE id = ?", ORDER_ITEM_ID);
                jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
                jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller_user WHERE user_id = ?", SELLER_USER_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?)", USER_ID, OTHER_USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private String orderItemStatus() {
        return jdbc.queryForObject("SELECT item_status FROM order_item WHERE id = ?", String.class, ORDER_ITEM_ID);
    }

    private String refundStatus(Long claimId) {
        return jdbc.queryForObject("SELECT status FROM refund WHERE claim_id = ? ORDER BY id DESC LIMIT 1", String.class, claimId);
    }

    private int refundCount(Long claimId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM refund WHERE claim_id = ?", Integer.class, claimId);
    }

    private String claimStatus(Long claimId) {
        return jdbc.queryForObject("SELECT status FROM claim WHERE id = ?", String.class, claimId);
    }

    private String claimPid(Long claimId) {
        return jdbc.queryForObject("SELECT public_id FROM claim WHERE id = ?", String.class, claimId);
    }

    private long claimCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM claim WHERE order_item_id = ?", Long.class, ORDER_ITEM_ID);
    }

    private int returnDeliveryCount(Long claimId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM delivery WHERE claim_id = ? AND direction = 'RETURN'", Integer.class, claimId);
    }

    private String returnDeliveryStatus(Long claimId) {
        return jdbc.queryForObject("SELECT status FROM delivery WHERE claim_id = ? AND direction = 'RETURN'", String.class, claimId);
    }

    private int onHand() {
        return jdbc.queryForObject("SELECT quantity_on_hand FROM inventory WHERE id = ?", Integer.class, INVENTORY_ID);
    }

    private int historyCount(String changeType) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inventory_history WHERE inventory_id = ? AND change_type = ?",
                Integer.class, INVENTORY_ID, changeType);
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
