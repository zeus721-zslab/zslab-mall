package com.zslab.mall.claim.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.claim.controller.request.ClaimRequestCommand;
import com.zslab.mall.claim.controller.response.ClaimResponse;
import com.zslab.mall.claim.controller.response.ClaimSummaryResponse;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimReasonCode;
import com.zslab.mall.claim.enums.ClaimRejectReasonCode;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.event.ClaimApproved;
import com.zslab.mall.claim.event.ClaimCompleted;
import com.zslab.mall.claim.event.ClaimRejected;
import com.zslab.mall.claim.event.ClaimRequested;
import com.zslab.mall.claim.exception.ClaimInvalidStateException;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.delivery.service.ReturnWindowPolicy;
import com.zslab.mall.common.observability.TracedEventPublisher;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.repository.OrderItemOrderProjection;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.refund.repository.RefundRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * {@link ClaimService} 단위 검증(Mockito·OrderServiceTest 패턴 1:1). request 가드 a~g(D-89 Q1·Q6·Q8·CLM-5)·
 * approve·reject·getClaim·listClaims·markCompleted·이벤트 발행(D-29 save→publish)을 mock 경계에서 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ClaimServiceTest {

    private static final String ORDER_ITEM_PUBLIC_ID = "oit_01ARZ3NDEKTSV4RRFFQ69G5FAV";
    private static final String CLAIM_PUBLIC_ID = "clm_01ARZ3NDEKTSV4RRFFQ69G5FAV";
    private static final Long ORDER_ITEM_ID = 10L;
    private static final Long ORDER_ID = 50L;
    private static final Long BUYER_ID = 100L;
    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 6, 29, 9, 0);
    private static final LocalDateTime PROCESSED_AT = LocalDateTime.of(2026, 6, 29, 10, 0);
    private static final AuditContext AUDIT_CONTEXT = AuditContext.of(9001L, "ADMIN");

    @Mock
    private ClaimRepository claimRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private TracedEventPublisher eventPublisher;
    @Mock
    private RefundRepository refundRepository;
    @Mock
    private DeliveryRepository deliveryRepository;
    @Mock
    private ReturnWindowPolicy returnWindowPolicy;
    @Mock
    private EntityManager entityManager;

    @Mock
    private ClaimAttachmentService claimAttachmentService;
    @Mock
    private ClaimExchangeService claimExchangeService;
    // Track 101-A: 관리자 클레임 조작 감사 적재 의존. 전이 검증 케이스에서는 호출 여부만 충족하면 된다.
    @Mock
    private AuditRecorder auditRecorder;

    @InjectMocks
    private ClaimService claimService;

    @org.junit.jupiter.api.BeforeEach
    void stubNoExchangeVariant() {
        // Mockito 기본값이 Long 0L이라 비교환 요청이 Claim.create 검증(exchangeVariantId null 요구)에 걸리지 않도록 null 고정(D-177)
        org.mockito.Mockito.lenient().when(claimExchangeService.resolveRequestedVariantId(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(null);
    }

    private ClaimRequestCommand command(ClaimType claimType) {
        return new ClaimRequestCommand(
                ORDER_ITEM_PUBLIC_ID, claimType, ClaimReasonCode.BUYER_CHANGED_MIND, "단순 변심", BUYER_ID, REQUESTED_AT);
    }

    /** REQUESTED 상태의 영속 전 Claim 시드(approve·reject·getClaim·markCompleted 검증용). */
    private Claim requestedClaim() {
        return Claim.create(
                ORDER_ITEM_ID, ClaimType.CANCEL, ClaimReasonCode.BUYER_CHANGED_MIND.name(), "단순 변심", BUYER_ID,
                REQUESTED_AT, OrderItemStatus.PAID);
    }

    // ===== request: D-89 Q1·Q6·Q8·CLM-5 =====

    @Test
    @DisplayName("request: 정상(CANCEL·소유 일치·활성 없음·전이 가능) → save·ClaimRequested 발행·REQUESTED 반환")
    void request_happyPath() {
        OrderItem orderItem = org.mockito.Mockito.mock(OrderItem.class);
        when(orderItem.getId()).thenReturn(ORDER_ITEM_ID);
        when(orderItem.getItemStatus()).thenReturn(OrderItemStatus.PAID);
        when(orderItemRepository.findByPublicId(ORDER_ITEM_PUBLIC_ID)).thenReturn(Optional.of(orderItem));
        when(orderItemRepository.findOrderIdById(ORDER_ITEM_ID)).thenReturn(Optional.of(ORDER_ID));
        Order order = org.mockito.Mockito.mock(Order.class);
        when(order.getBuyerId()).thenReturn(BUYER_ID);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));
        when(claimRepository.existsActiveByOrderItemId(ORDER_ITEM_ID)).thenReturn(false);
        when(claimRepository.save(any(Claim.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Claim result = claimService.request(command(ClaimType.CANCEL));

        assertThat(result.getType()).isEqualTo(ClaimType.CANCEL);
        assertThat(result.getStatus()).isEqualTo(ClaimStatus.REQUESTED);
        assertThat(result.getOrderItemId()).isEqualTo(ORDER_ITEM_ID);
        assertThat(result.getRequestedBy()).isEqualTo(BUYER_ID);
        assertThat(result.getReasonCode()).isEqualTo(ClaimReasonCode.BUYER_CHANGED_MIND.name());
        verify(claimRepository).save(any(Claim.class));
        verify(eventPublisher).publishEvent(any(ClaimRequested.class));
    }

    @Test
    @DisplayName("request: 주문 품목 미존재(findByPublicId empty) → ClaimNotFoundException·save·publish 미호출")
    void request_orderItemNotFound_throws() {
        when(orderItemRepository.findByPublicId(ORDER_ITEM_PUBLIC_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> claimService.request(command(ClaimType.CANCEL)))
                .isInstanceOf(ClaimNotFoundException.class);
        verify(claimRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("request: order_id 해소 실패(findOrderIdById empty) → ClaimNotFoundException")
    void request_orderIdUnresolved_throws() {
        OrderItem orderItem = org.mockito.Mockito.mock(OrderItem.class);
        when(orderItem.getId()).thenReturn(ORDER_ITEM_ID);
        when(orderItemRepository.findByPublicId(ORDER_ITEM_PUBLIC_ID)).thenReturn(Optional.of(orderItem));
        when(orderItemRepository.findOrderIdById(ORDER_ITEM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> claimService.request(command(ClaimType.CANCEL)))
                .isInstanceOf(ClaimNotFoundException.class);
    }

    @Test
    @DisplayName("request: 주문 미존재(orderRepository.findById empty) → ClaimNotFoundException")
    void request_orderNotFound_throws() {
        OrderItem orderItem = org.mockito.Mockito.mock(OrderItem.class);
        when(orderItem.getId()).thenReturn(ORDER_ITEM_ID);
        when(orderItemRepository.findByPublicId(ORDER_ITEM_PUBLIC_ID)).thenReturn(Optional.of(orderItem));
        when(orderItemRepository.findOrderIdById(ORDER_ITEM_ID)).thenReturn(Optional.of(ORDER_ID));
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> claimService.request(command(ClaimType.CANCEL)))
                .isInstanceOf(ClaimNotFoundException.class);
    }

    @Test
    @DisplayName("request: 타인 소유 주문(buyerId 불일치) → ClaimNotFoundException(정보 누출 차단·Q8)")
    void request_otherOwner_throws() {
        OrderItem orderItem = org.mockito.Mockito.mock(OrderItem.class);
        when(orderItem.getId()).thenReturn(ORDER_ITEM_ID);
        when(orderItemRepository.findByPublicId(ORDER_ITEM_PUBLIC_ID)).thenReturn(Optional.of(orderItem));
        when(orderItemRepository.findOrderIdById(ORDER_ITEM_ID)).thenReturn(Optional.of(ORDER_ID));
        Order order = org.mockito.Mockito.mock(Order.class);
        when(order.getBuyerId()).thenReturn(999L);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> claimService.request(command(ClaimType.CANCEL)))
                .isInstanceOf(ClaimNotFoundException.class);
        verify(claimRepository, never()).existsActiveByOrderItemId(any());
    }

    @Test
    @DisplayName("request: RETURN·DELIVERED OrderItem → 정상 처리(D-98 Q4 게이트 제거)·스냅샷(DELIVERED) 저장·save·발행")
    void request_returnType_succeeds() {
        OrderItem orderItem = org.mockito.Mockito.mock(OrderItem.class);
        when(orderItem.getId()).thenReturn(ORDER_ITEM_ID);
        when(orderItem.getItemStatus()).thenReturn(OrderItemStatus.DELIVERED);
        when(orderItemRepository.findByPublicId(ORDER_ITEM_PUBLIC_ID)).thenReturn(Optional.of(orderItem));
        when(orderItemRepository.findOrderIdById(ORDER_ITEM_ID)).thenReturn(Optional.of(ORDER_ID));
        Order order = org.mockito.Mockito.mock(Order.class);
        when(order.getBuyerId()).thenReturn(BUYER_ID);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));
        when(claimRepository.existsActiveByOrderItemId(ORDER_ITEM_ID)).thenReturn(false);
        when(claimRepository.save(any(Claim.class))).thenAnswer(invocation -> invocation.getArgument(0));
        // Track 81-A: 반품 기한은 원 발송(OUTBOUND·claim_id NULL·DELIVERED) delivered_at + 7일(ReturnWindowPolicy) — 요청 3일 전 배송완료로 스텁
        when(returnWindowPolicy.originalDeliveredAt(ORDER_ITEM_ID)).thenReturn(Optional.of(REQUESTED_AT.minusDays(3)));

        Claim result = claimService.request(command(ClaimType.RETURN));

        assertThat(result.getType()).isEqualTo(ClaimType.RETURN);
        assertThat(result.getPreviousOrderItemStatus()).isEqualTo(OrderItemStatus.DELIVERED);
        verify(claimRepository).save(any(Claim.class));
        verify(eventPublisher).publishEvent(any(ClaimRequested.class));
    }

    @Test
    @DisplayName("request: 동일 OrderItem 활성 클레임 존재 → ClaimInvalidStateException(CLM-5)")
    void request_activeClaimExists_throws() {
        OrderItem orderItem = org.mockito.Mockito.mock(OrderItem.class);
        when(orderItem.getId()).thenReturn(ORDER_ITEM_ID);
        when(orderItemRepository.findByPublicId(ORDER_ITEM_PUBLIC_ID)).thenReturn(Optional.of(orderItem));
        when(orderItemRepository.findOrderIdById(ORDER_ITEM_ID)).thenReturn(Optional.of(ORDER_ID));
        Order order = org.mockito.Mockito.mock(Order.class);
        when(order.getBuyerId()).thenReturn(BUYER_ID);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));
        when(claimRepository.existsActiveByOrderItemId(ORDER_ITEM_ID)).thenReturn(true);

        assertThatThrownBy(() -> claimService.request(command(ClaimType.CANCEL)))
                .isInstanceOf(ClaimInvalidStateException.class);
        verify(claimRepository, never()).save(any());
    }

    @Test
    @DisplayName("request: OrderItem 상태가 취소 요청 불가(CONFIRMED) → ClaimInvalidStateException")
    void request_itemNotCancellable_throws() {
        OrderItem orderItem = org.mockito.Mockito.mock(OrderItem.class);
        when(orderItem.getId()).thenReturn(ORDER_ITEM_ID);
        when(orderItem.getItemStatus()).thenReturn(OrderItemStatus.CONFIRMED);
        when(orderItemRepository.findByPublicId(ORDER_ITEM_PUBLIC_ID)).thenReturn(Optional.of(orderItem));
        when(orderItemRepository.findOrderIdById(ORDER_ITEM_ID)).thenReturn(Optional.of(ORDER_ID));
        Order order = org.mockito.Mockito.mock(Order.class);
        when(order.getBuyerId()).thenReturn(BUYER_ID);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));
        when(claimRepository.existsActiveByOrderItemId(ORDER_ITEM_ID)).thenReturn(false);

        assertThatThrownBy(() -> claimService.request(command(ClaimType.CANCEL)))
                .isInstanceOf(ClaimInvalidStateException.class);
        verify(claimRepository, never()).save(any());
    }

    @Test
    @DisplayName("request: save 직후 publishEvent 발행(D-29 InOrder)·payload 정합")
    void request_publishesAfterSave_withPayload() {
        OrderItem orderItem = org.mockito.Mockito.mock(OrderItem.class);
        when(orderItem.getId()).thenReturn(ORDER_ITEM_ID);
        when(orderItem.getItemStatus()).thenReturn(OrderItemStatus.PAID);
        when(orderItemRepository.findByPublicId(ORDER_ITEM_PUBLIC_ID)).thenReturn(Optional.of(orderItem));
        when(orderItemRepository.findOrderIdById(ORDER_ITEM_ID)).thenReturn(Optional.of(ORDER_ID));
        Order order = org.mockito.Mockito.mock(Order.class);
        when(order.getBuyerId()).thenReturn(BUYER_ID);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));
        when(claimRepository.existsActiveByOrderItemId(ORDER_ITEM_ID)).thenReturn(false);
        when(claimRepository.save(any(Claim.class))).thenAnswer(invocation -> invocation.getArgument(0));

        claimService.request(command(ClaimType.CANCEL));

        InOrder inOrder = inOrder(claimRepository, eventPublisher);
        inOrder.verify(claimRepository).save(any(Claim.class));
        ArgumentCaptor<ClaimRequested> captor = ArgumentCaptor.forClass(ClaimRequested.class);
        inOrder.verify(eventPublisher).publishEvent(captor.capture());
        ClaimRequested event = captor.getValue();
        assertThat(event.orderItemId()).isEqualTo(ORDER_ITEM_ID);
        assertThat(event.claimType()).isEqualTo(ClaimType.CANCEL);
        assertThat(event.status()).isEqualTo(ClaimStatus.REQUESTED);
        assertThat(event.buyerId()).isEqualTo(BUYER_ID);
    }

    // ===== approve =====

    @Test
    @DisplayName("approve: REQUESTED → APPROVED 전이·save·ClaimApproved 발행")
    void approve_happyPath() {
        Claim claim = requestedClaim();
        when(claimRepository.findById(1L)).thenReturn(Optional.of(claim));

        claimService.approve(1L, PROCESSED_AT, null);

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(claim.getProcessedAt()).isEqualTo(PROCESSED_AT);
        verify(claimRepository).save(claim);
        ArgumentCaptor<ClaimApproved> captor = ArgumentCaptor.forClass(ClaimApproved.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(ClaimStatus.APPROVED);
    }

    @Test
    @DisplayName("approve: 클레임 미존재 → ClaimNotFoundException")
    void approve_notFound_throws() {
        when(claimRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> claimService.approve(999L, PROCESSED_AT, null))
                .isInstanceOf(ClaimNotFoundException.class);
        verify(claimRepository, never()).save(any());
    }

    @Test
    @DisplayName("approve: 이미 APPROVED 상태에서 재승인 → ClaimInvalidStateException(CLM-4)")
    void approve_alreadyApproved_throws() {
        Claim claim = requestedClaim();
        claim.approve(PROCESSED_AT); // 시드: REQUESTED → APPROVED
        when(claimRepository.findById(1L)).thenReturn(Optional.of(claim));

        assertThatThrownBy(() -> claimService.approve(1L, PROCESSED_AT, null))
                .isInstanceOf(ClaimInvalidStateException.class);
        verify(claimRepository, never()).save(any());
    }

    // ===== reject =====

    @Test
    @DisplayName("reject: REQUESTED → REJECTED 전이·save·ClaimRejected 발행")
    void reject_happyPath() {
        Claim claim = requestedClaim();
        when(claimRepository.findById(1L)).thenReturn(Optional.of(claim));

        claimService.reject(1L, ClaimRejectReasonCode.OUT_OF_POLICY, null, PROCESSED_AT);

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REJECTED);
        assertThat(claim.getProcessedAt()).isEqualTo(PROCESSED_AT);
        verify(claimRepository).save(claim);
        ArgumentCaptor<ClaimRejected> captor = ArgumentCaptor.forClass(ClaimRejected.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(ClaimStatus.REJECTED);
    }

    @Test
    @DisplayName("reject: 클레임 미존재 → ClaimNotFoundException")
    void reject_notFound_throws() {
        when(claimRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> claimService.reject(999L, ClaimRejectReasonCode.OUT_OF_POLICY, null, PROCESSED_AT))
                .isInstanceOf(ClaimNotFoundException.class);
        verify(claimRepository, never()).save(any());
    }

    @Test
    @DisplayName("reject: 종결(COMPLETED) 상태에서 거절 → ClaimInvalidStateException(CLM-4)")
    void reject_completed_throws() {
        Claim claim = requestedClaim();
        claim.approve(PROCESSED_AT); // 시드: REQUESTED → APPROVED
        claim.markCompleted(PROCESSED_AT); // 시드: APPROVED → COMPLETED
        when(claimRepository.findById(1L)).thenReturn(Optional.of(claim));

        assertThatThrownBy(() -> claimService.reject(1L, ClaimRejectReasonCode.OUT_OF_POLICY, null, PROCESSED_AT))
                .isInstanceOf(ClaimInvalidStateException.class);
        verify(claimRepository, never()).save(any());
    }

    // ===== approveByAdmin·rejectByAdmin: Track 10-B·D-93 Q3 전체 접근·권한 검증 단락 부재 =====

    @Test
    @DisplayName("approveByAdmin: REQUESTED → APPROVED 전이·save·ClaimApproved 발행·seller scope 검증 없음(D-93 Q3)")
    void approveByAdmin_happyPath_noSellerScope() {
        Claim claim = requestedClaim();
        when(claimRepository.findById(1L)).thenReturn(Optional.of(claim));

        claimService.approveByAdmin(1L, PROCESSED_AT, null, AUDIT_CONTEXT);

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(claim.getProcessedAt()).isEqualTo(PROCESSED_AT);
        verify(claimRepository).save(claim);
        ArgumentCaptor<ClaimApproved> captor = ArgumentCaptor.forClass(ClaimApproved.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(ClaimStatus.APPROVED);
        // D-93 Q3·Q5: Admin은 cross-tenant 검증 부재 → authorizeSellerAccess 경로(orderItemRepository) 미진입
        verify(orderItemRepository, never()).findById(any());
    }

    @Test
    @DisplayName("approveByAdmin: 클레임 미존재 → ClaimNotFoundException·save 미호출")
    void approveByAdmin_notFound_throws() {
        when(claimRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> claimService.approveByAdmin(999L, PROCESSED_AT, null, AUDIT_CONTEXT))
                .isInstanceOf(ClaimNotFoundException.class);
        verify(claimRepository, never()).save(any());
    }

    @Test
    @DisplayName("approveByAdmin: 이미 APPROVED 상태 → ClaimInvalidStateException(CLM-4·422)")
    void approveByAdmin_alreadyApproved_throws() {
        Claim claim = requestedClaim();
        claim.approve(PROCESSED_AT); // 시드: REQUESTED → APPROVED
        when(claimRepository.findById(1L)).thenReturn(Optional.of(claim));

        assertThatThrownBy(() -> claimService.approveByAdmin(1L, PROCESSED_AT, null, AUDIT_CONTEXT))
                .isInstanceOf(ClaimInvalidStateException.class);
        verify(claimRepository, never()).save(any());
    }

    @Test
    @DisplayName("rejectByAdmin: REQUESTED → REJECTED 전이·save·ClaimRejected 발행·seller scope 검증 없음(D-93 Q3)")
    void rejectByAdmin_happyPath_noSellerScope() {
        Claim claim = requestedClaim();
        when(claimRepository.findById(1L)).thenReturn(Optional.of(claim));

        claimService.rejectByAdmin(1L, ClaimRejectReasonCode.OUT_OF_POLICY, null, PROCESSED_AT, AUDIT_CONTEXT);

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REJECTED);
        assertThat(claim.getProcessedAt()).isEqualTo(PROCESSED_AT);
        verify(claimRepository).save(claim);
        ArgumentCaptor<ClaimRejected> captor = ArgumentCaptor.forClass(ClaimRejected.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(ClaimStatus.REJECTED);
        // D-93 Q3·Q5: Admin은 cross-tenant 검증 부재 → authorizeSellerAccess 경로(orderItemRepository) 미진입
        verify(orderItemRepository, never()).findById(any());
    }

    @Test
    @DisplayName("rejectByAdmin: 클레임 미존재 → ClaimNotFoundException·save 미호출")
    void rejectByAdmin_notFound_throws() {
        when(claimRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> claimService.rejectByAdmin(999L, ClaimRejectReasonCode.OUT_OF_POLICY, null, PROCESSED_AT, AUDIT_CONTEXT))
                .isInstanceOf(ClaimNotFoundException.class);
        verify(claimRepository, never()).save(any());
    }

    // ===== getClaim: J2·Q8 =====

    @Test
    @DisplayName("getClaim: 본인 정상 조회 → ClaimResponse·orderItemPublicId 해소")
    void getClaim_happyPath() {
        // 미영속 Claim은 id가 null이라 환불 상태 배치 조회 키(List.of(id))에 실을 수 없으므로 spy로 id를 부여한다(Track 80)
        Claim claim = org.mockito.Mockito.spy(requestedClaim());
        org.mockito.Mockito.doReturn(1L).when(claim).getId();
        when(claimRepository.findByPublicId(CLAIM_PUBLIC_ID)).thenReturn(Optional.of(claim));
        // Track 101-B: 소유 판정 기준이 claim.requested_by가 아니라 주문의 구매자다.
        when(claimRepository.findOrderBuyerIdByClaimId(1L)).thenReturn(Optional.of(BUYER_ID));
        OrderItem orderItem = org.mockito.Mockito.mock(OrderItem.class);
        when(orderItem.getPublicId()).thenReturn(ORDER_ITEM_PUBLIC_ID);
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));

        ClaimResponse response = claimService.getClaim(CLAIM_PUBLIC_ID, BUYER_ID);

        assertThat(response.orderItemPublicId()).isEqualTo(ORDER_ITEM_PUBLIC_ID);
        assertThat(response.claimType()).isEqualTo(ClaimType.CANCEL);
        assertThat(response.status()).isEqualTo(ClaimStatus.REQUESTED);
        assertThat(response.reasonCode()).isEqualTo(ClaimReasonCode.BUYER_CHANGED_MIND.name());
        assertThat(response.reasonDetail()).isEqualTo("단순 변심");
    }

    @Test
    @DisplayName("getClaim: 미존재 → ClaimNotFoundException")
    void getClaim_notFound_throws() {
        when(claimRepository.findByPublicId(CLAIM_PUBLIC_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> claimService.getClaim(CLAIM_PUBLIC_ID, BUYER_ID))
                .isInstanceOf(ClaimNotFoundException.class);
    }

    @Test
    @DisplayName("getClaim: 타인 소유(주문 구매자가 다름) → ClaimNotFoundException(정보 누출 차단·Q8)")
    void getClaim_otherOwner_throws() {
        // 해소는 되고 구매자만 다른 상태를 만든다 — 스텁을 비우면 Optional.empty()가 돌아와 "해소 실패" 경로를 타므로
        // 소유권 판정 자체가 검증되지 않는다(외부 검토 반영·해소 실패는 아래 별도 케이스).
        Claim claim = org.mockito.Mockito.spy(requestedClaim());
        org.mockito.Mockito.doReturn(1L).when(claim).getId();
        when(claimRepository.findByPublicId(CLAIM_PUBLIC_ID)).thenReturn(Optional.of(claim));
        when(claimRepository.findOrderBuyerIdByClaimId(1L)).thenReturn(Optional.of(BUYER_ID));

        assertThatThrownBy(() -> claimService.getClaim(CLAIM_PUBLIC_ID, 999L))
                .isInstanceOf(ClaimNotFoundException.class);
        verify(orderItemRepository, never()).findById(any());
    }

    @Test
    @DisplayName("getClaim: 주문 구매자 해소 실패(품목·주문 없음) → ClaimNotFoundException(404 은닉)")
    void getClaim_orderBuyerUnresolved_throws() {
        Claim claim = org.mockito.Mockito.spy(requestedClaim());
        org.mockito.Mockito.doReturn(1L).when(claim).getId();
        when(claimRepository.findByPublicId(CLAIM_PUBLIC_ID)).thenReturn(Optional.of(claim));
        when(claimRepository.findOrderBuyerIdByClaimId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> claimService.getClaim(CLAIM_PUBLIC_ID, BUYER_ID))
                .isInstanceOf(ClaimNotFoundException.class);
        verify(orderItemRepository, never()).findById(any());
    }

    @Test
    @DisplayName("getClaim: 주문 품목 해소 실패(이상 케이스) → IllegalStateException")
    void getClaim_orderItemMissing_throws() {
        Claim claim = org.mockito.Mockito.spy(requestedClaim());
        org.mockito.Mockito.doReturn(1L).when(claim).getId();
        when(claimRepository.findByPublicId(CLAIM_PUBLIC_ID)).thenReturn(Optional.of(claim));
        when(claimRepository.findOrderBuyerIdByClaimId(1L)).thenReturn(Optional.of(BUYER_ID));
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> claimService.getClaim(CLAIM_PUBLIC_ID, BUYER_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    // ===== listClaims: D-54 =====

    @Test
    @DisplayName("listClaims: 정상 목록 → PagedResponse<ClaimSummaryResponse> 매핑(주문 구매자 기준 조회)")
    void listClaims_happyPath() {
        Claim claim = requestedClaim();
        when(claimRepository.findAllByOrderBuyerId(eq(BUYER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(claim), PageRequest.of(0, 20), 1));

        PagedResponse<ClaimSummaryResponse> response = claimService.listClaims(BUYER_ID, null, 0, 20);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).claimType()).isEqualTo(ClaimType.CANCEL);
        assertThat(response.items().get(0).status()).isEqualTo(ClaimStatus.REQUESTED);
        assertThat(response.totalCount()).isEqualTo(1L);
        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(20);
    }

    @Test
    @DisplayName("listClaims: 주문번호·상품명을 주문 요약 projection 1쿼리로 채운다(품목 엔티티 미적재)")
    void listClaims_enrichesFromSingleProjection() {
        Claim claim = requestedClaim();
        when(claimRepository.findAllByOrderBuyerId(eq(BUYER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(claim), PageRequest.of(0, 20), 1));
        OrderItemOrderProjection projection = org.mockito.Mockito.mock(OrderItemOrderProjection.class);
        when(projection.getOrderItemId()).thenReturn(ORDER_ITEM_ID);
        when(projection.getOrderNo()).thenReturn("ORD20260629001");
        when(projection.getProductName()).thenReturn("검수용 상품");
        when(orderItemRepository.findOrderSummariesByIdIn(List.of(ORDER_ITEM_ID))).thenReturn(List.of(projection));

        PagedResponse<ClaimSummaryResponse> response = claimService.listClaims(BUYER_ID, null, 0, 20);

        assertThat(response.items().get(0).orderNo()).isEqualTo("ORD20260629001");
        assertThat(response.items().get(0).productName()).isEqualTo("검수용 상품");
        // 외부 검토 반영: 같은 itemIds로 품목 엔티티를 다시 적재하던 조회가 없어졌다.
        verify(orderItemRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("listClaims: 주문 요약 해소 실패 → 주문번호·상품명 null(목록 자체는 내려간다)")
    void listClaims_unresolvedOrderSummary_leavesNulls() {
        Claim claim = requestedClaim();
        when(claimRepository.findAllByOrderBuyerId(eq(BUYER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(claim), PageRequest.of(0, 20), 1));
        when(orderItemRepository.findOrderSummariesByIdIn(List.of(ORDER_ITEM_ID))).thenReturn(List.of());

        PagedResponse<ClaimSummaryResponse> response = claimService.listClaims(BUYER_ID, null, 0, 20);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).orderNo()).isNull();
        assertThat(response.items().get(0).productName()).isNull();
    }

    @Test
    @DisplayName("listClaims: 유형 지정 → 유형 필터 쿼리로 분기(Track 101-B 탭)")
    void listClaims_withType_usesTypeQuery() {
        when(claimRepository.findAllByOrderBuyerIdAndType(eq(BUYER_ID), eq(ClaimType.RETURN), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        claimService.listClaims(BUYER_ID, ClaimType.RETURN, 0, 20);

        verify(claimRepository).findAllByOrderBuyerIdAndType(eq(BUYER_ID), eq(ClaimType.RETURN), any(Pageable.class));
        verify(claimRepository, never()).findAllByOrderBuyerId(any(), any());
    }

    @Test
    @DisplayName("listClaims: size 클램프(200→100·0→20·-1→20)")
    void listClaims_clampsSize() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        when(claimRepository.findAllByOrderBuyerId(eq(BUYER_ID), captor.capture()))
                .thenReturn(new PageImpl<>(List.of()));

        claimService.listClaims(BUYER_ID, null, 0, 200);
        claimService.listClaims(BUYER_ID, null, 0, 0);
        claimService.listClaims(BUYER_ID, null, 0, -1);

        List<Pageable> pageables = captor.getAllValues();
        assertThat(pageables.get(0).getPageSize()).isEqualTo(100);
        assertThat(pageables.get(1).getPageSize()).isEqualTo(20);
        assertThat(pageables.get(2).getPageSize()).isEqualTo(20);
    }

    // ===== markCompleted: Track 5 기존·보존(단위 미커버 → 멱등 분기 보강) =====

    @Test
    @DisplayName("markCompleted: 이미 COMPLETED → 멱등 NO-OP·save·publish 미호출(CLM-1)")
    void markCompleted_alreadyCompleted_noOp() {
        Claim claim = requestedClaim();
        claim.approve(PROCESSED_AT); // 시드: REQUESTED → APPROVED
        claim.markCompleted(PROCESSED_AT); // 시드: APPROVED → COMPLETED
        when(claimRepository.findById(1L)).thenReturn(Optional.of(claim));

        claimService.markCompleted(1L);

        verify(claimRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("markCompleted: APPROVED → COMPLETED 종결·save·ClaimCompleted 발행(D-29 save→publish·D-90 Q4)")
    void markCompleted_approved_transitionsAndPublishes() {
        Claim claim = requestedClaim();
        claim.approve(PROCESSED_AT); // 시드: REQUESTED → APPROVED
        when(claimRepository.findById(1L)).thenReturn(Optional.of(claim));

        claimService.markCompleted(1L);

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.COMPLETED);
        verify(claimRepository).save(claim);
        ArgumentCaptor<ClaimCompleted> captor = ArgumentCaptor.forClass(ClaimCompleted.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(ClaimStatus.COMPLETED);
        assertThat(captor.getValue().orderItemId()).isEqualTo(ORDER_ITEM_ID);
        assertThat(captor.getValue().claimType()).isEqualTo(ClaimType.CANCEL);
    }
}
