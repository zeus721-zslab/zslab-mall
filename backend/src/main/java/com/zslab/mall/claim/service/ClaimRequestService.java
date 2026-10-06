package com.zslab.mall.claim.service;

import com.zslab.mall.attachment.entity.Attachment;
import com.zslab.mall.claim.controller.request.ClaimRequestCommand;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimInspectionResult;
import com.zslab.mall.claim.enums.ClaimReasonCode;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.event.ClaimRequested;
import com.zslab.mall.claim.exception.ClaimInvalidStateException;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.common.observability.TracedEventPublisher;
import com.zslab.mall.delivery.service.ReturnWindowPolicy;
import com.zslab.mall.inbox.stream.InboxSignalPublisher;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.order.service.OrderService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 클레임 요청(REQUESTED 생성) Application Service. {@link ClaimService}에서 분리했다(D-265). 트랜잭션 경계는 메서드 단위다(QB-1).
 *
 * <p><b>요청(request)</b>: type 무관 진입(D-98 Q4 게이트 제거·CANCEL/RETURN/EXCHANGE)·소유권 2단계 조회(Q8)·CLM-5 중복 차단·
 * type별 OrderItem 전이 가능성 검증 후 Claim INSERT(요청 시점 상태 스냅샷 저장·D-98 Q11).
 * 이벤트는 save→publish 순서로 발행한다(D-29·no flush). OrderItem.item_status 실제 전이는 핸들러 소관이며 본 메서드는 발행만 한다.
 *
 * <p>orderItemPublicId(oit_)는 진입점에서 {@link OrderItemRepository#findByPublicId}로 BIGINT id를 해소한다(D-64·D-65).
 */
@Service
@Transactional
public class ClaimRequestService {

    /** 사진 첨부를 허용하는 반품·교환 사유(Track 81-B D-171·R2 / Track 83 D-177 결정 3 동일 규칙). 단순변심 첨부는 400. */
    private static final Set<ClaimReasonCode> ATTACHABLE_RETURN_REASONS =
            Set.of(ClaimReasonCode.PRODUCT_DEFECT, ClaimReasonCode.WRONG_PRODUCT);

    private final ClaimRepository claimRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderRepository orderRepository;
    private final TracedEventPublisher eventPublisher;
    private final ReturnWindowPolicy returnWindowPolicy;
    private final ClaimAttachmentService claimAttachmentService;
    private final ClaimExchangeService claimExchangeService;
    private final EntityManager entityManager;
    private final OrderService orderService;
    private final InboxSignalPublisher inboxSignalPublisher;
    private final ClaimService claimService;

    public ClaimRequestService(
            ClaimRepository claimRepository,
            OrderItemRepository orderItemRepository,
            OrderRepository orderRepository,
            TracedEventPublisher eventPublisher,
            ReturnWindowPolicy returnWindowPolicy,
            ClaimAttachmentService claimAttachmentService,
            ClaimExchangeService claimExchangeService,
            EntityManager entityManager,
            OrderService orderService,
            InboxSignalPublisher inboxSignalPublisher,
            ClaimService claimService) {
        this.claimRepository = claimRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
        this.returnWindowPolicy = returnWindowPolicy;
        this.claimAttachmentService = claimAttachmentService;
        this.claimExchangeService = claimExchangeService;
        this.entityManager = entityManager;
        this.orderService = orderService;
        this.inboxSignalPublisher = inboxSignalPublisher;
        this.claimService = claimService;
    }

    /**
     * 클레임을 요청한다(REQUESTED 생성·D-89 Q1·Q6·Q8·CLM-5). save 직후 {@link ClaimRequested}를 발행한다(D-29 save→publish).
     *
     * @param command orderItemPublicId·claimType·reasonCode·buyerId·requestedAt
     * @return 생성된 Claim(public_id·id 부여 완료)
     * @throws ClaimNotFoundException     주문 품목이 없거나 소유자가 다른 경우(정보 노출 회피·T3)
     * @throws ClaimInvalidStateException 활성 클레임 중복(CLM-5)·OrderItem 상태가 해당 type 요청 전이 불가인 경우(422)
     * @throws MalformedRequestException  첨부 허용 조건(RETURN·EXCHANGE·불량/오배송) 위반·첨부 id 소유권/연결/중복 위반·교환 옵션 미지정(400)
     */
    public Claim request(ClaimRequestCommand command) {
        // Track 104-1 D-215(P5): 주문 쓰기 락이 첫 DB 접근이다(첨부·교환 옵션 읽기 검증과 품목 적재는 락 뒤).
        orderItemRepository.findOrderIdByPublicId(command.orderItemPublicId()).ifPresent(orderService::lockForWrite);
        // (0) 첨부(Track 81-B·D-177 결정 3): RETURN·EXCHANGE + 불량/오배송에서만 허용. 소유권·미연결 검증은 품목 행 락 전에(읽기만·주문 쓰기 락 뒤) 끝낸다.
        List<String> attachmentIds = command.attachmentIds();
        if (!attachmentIds.isEmpty()
                && (!command.claimType().isPickupBased() || !ATTACHABLE_RETURN_REASONS.contains(command.reasonCode()))) {
            throw new MalformedRequestException("사진 첨부는 반품·교환(상품불량·오배송) 요청에서만 허용됩니다.");
        }
        List<Attachment> attachments = claimAttachmentService.resolveForLink(command.buyerId(), attachmentIds);
        // (0-2) 교환 옵션(D-177 결정 9): EXCHANGE는 var_ public id 필수, 그 외 유형은 지정 불가. 존재·소유 검증은 락 뒤 createClaim에서.
        Long exchangeVariantId = claimExchangeService.resolveRequestedVariantId(command.claimType(), command.exchangeVariantPublicId());

        // (a) OrderItem public_id → id 해소(D-64·D-65). 미존재 시 404.
        OrderItem resolved = orderItemRepository.findByPublicId(command.orderItemPublicId())
                .orElseThrow(() -> new ClaimNotFoundException(
                        "주문 품목을 찾을 수 없습니다: " + command.orderItemPublicId()));

        // (b)(c) 소유권 검증: order_item → order → buyer_id 2단계 조회(Q8). 불일치 시 404(정보 누출 차단·T3).
        Long orderId = orderItemRepository.findOrderIdById(resolved.getId())
                .orElseThrow(() -> new ClaimNotFoundException(
                        "주문 품목을 찾을 수 없습니다: " + command.orderItemPublicId()));
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ClaimNotFoundException(
                        "주문을 찾을 수 없습니다: orderItemPublicId=" + command.orderItemPublicId()));
        if (!order.getBuyerId().equals(command.buyerId())) {
            throw new ClaimNotFoundException("주문 품목을 찾을 수 없습니다: " + command.orderItemPublicId());
        }

        Claim claim = createClaim(resolved.getId(), command.claimType(), command.reasonCode(), command.reasonDetail(),
                command.buyerId(), command.requestedAt(), exchangeVariantId);
        claimAttachmentService.link(attachments, claim.getId(), command.buyerId());
        return claim;
    }

    /**
     * 클레임 생성 공통 코어(Track 79 D-168·사용자 {@link #request}·관리자 {@link #requestByAdmin} 공유). 품목을
     * {@code refresh(PESSIMISTIC_WRITE)}로 잠그고 최신 상태로 재적재한 뒤(D·동시 요청 직렬화) CLM-5·type별 요청 전이 가능성을 검증하고
     * Claim을 저장·{@link ClaimRequested}를 발행한다. 동기 {@code ClaimRequestedHandler}가 같은 TX에서 품목을 *_REQUESTED로 전이하므로,
     * 락을 기다린 두 번째 요청은 전이 검증에서 422로 거부되고 Claim INSERT는 발생하지 않는다.
     *
     * <p><b>refresh인 이유</b>: 호출부(사용자 경로 findByPublicId·관리자 경로 Order fetch join)가 품목 엔티티를 이미 1차 캐시에
     * 올려둔 뒤라, 잠금 조회({@code SELECT ... FOR UPDATE})만으로는 캐시된 stale 상태가 덮어써지지 않는다. refresh는 잠금과 상태 재적재를
     * 한 번에 수행해 락 획득 직후의 최신 item_status로 검증하게 한다.
     *
     * @throws ClaimInvalidStateException 활성 클레임 중복(CLM-5)·OrderItem 상태가 해당 type 요청 전이 불가인 경우(422)
     */
    private Claim createClaim(Long orderItemId, ClaimType claimType, ClaimReasonCode reasonCode, String reasonDetail,
            Long requestedBy, LocalDateTime requestedAt, Long exchangeVariantId) {
        OrderItem orderItem = orderItemRepository.findById(orderItemId)
                .orElseThrow(() -> new ClaimNotFoundException("주문 품목을 찾을 수 없습니다: orderItemId=" + orderItemId));
        entityManager.refresh(orderItem, LockModeType.PESSIMISTIC_WRITE);

        // (d) CLM-5: 동일 OrderItem 활성 클레임(REQUESTED·APPROVED) 중복 차단(422).
        if (claimRepository.existsActiveByOrderItemId(orderItem.getId())) {
            throw new ClaimInvalidStateException("이미 진행 중인 클레임이 있습니다(CLM-5): orderItemId=" + orderItemId);
        }

        // (d-2) RETURN·EXCHANGE 요청 조건(Track 81-A D-170·R1·R2 / D-177 결정 7·11): 사유 3값 한정·품목 DELIVERED·원 발송 배송완료 후 7일 이내·
        //       검수 FAIL 이력 차단. EXCHANGE는 재교환 차단·교환 옵션(같은 상품·같은 가격·다른 옵션·판매 가능) 검증을 더한다.
        if (claimType.isPickupBased()) {
            validateReturnRequest(orderItem, claimType, reasonCode, requestedAt);
        }
        if (claimType == ClaimType.EXCHANGE) {
            claimExchangeService.validateExchangeRequest(orderItem, exchangeVariantId, requestedAt);
        }

        // (e) type별 진입 전이 대상 매핑 후 OrderItem 상태 전이 가능성 검증(D-98 Q4). 실제 전이는 동기 핸들러가 수행.
        OrderItemStatus targetStatus = switch (claimType) {
            case CANCEL -> OrderItemStatus.CANCEL_REQUESTED;
            case RETURN -> OrderItemStatus.RETURN_REQUESTED;
            case EXCHANGE -> OrderItemStatus.EXCHANGE_REQUESTED;
        };
        if (!orderItem.getItemStatus().canTransitionTo(targetStatus)) {
            throw new ClaimInvalidStateException(
                    "현재 주문 품목 상태에서 " + claimType + " 요청이 불가합니다: " + orderItem.getItemStatus());
        }

        // (f) Claim 생성·저장 후 이벤트 발행(D-29 save→publish·no flush). public_id·id는 save 시 부여된다.
        //     요청 시점 OrderItem 상태를 스냅샷으로 저장(D-98 Q11·REJECTED 원복용).
        Claim claim = Claim.create(
                orderItem.getId(),
                claimType,
                reasonCode.name(),
                reasonDetail,
                requestedBy,
                requestedAt,
                orderItem.getItemStatus(),
                exchangeVariantId);
        claimRepository.save(claim);
        eventPublisher.publishEvent(new ClaimRequested(
                claim.getId(),
                claim.getPublicId(),
                claim.getOrderItemId(),
                claim.getType(),
                claim.getStatus(),
                claim.getRequestedBy(),
                LocalDateTime.now()));
        // 관리자 클레임 접수 진입 · 셀러 발송 대기 이탈(취소 요청)
        inboxSignalPublisher.adminChanged();
        inboxSignalPublisher.sellerChanged(orderItem.getSellerId());
        return claim;
    }

    /**
     * RETURN·EXCHANGE 요청 조건 검증(Track 81-A D-170·보충 / Track 83 D-177 동일 적용). 사유는 {@link ClaimReasonCode#isApplicableTo}
     * (단순변심·상품불량·오배송), 품목은 DELIVERED(SHIPPING 중 요청은 배송완료 기준 기한을 셀 수 없어 차단 · 매트릭스도 D-266부터 불허),
     * 기한은 {@link ReturnWindowPolicy}(기준 발송 delivered_at + 7일·교환 발송 포함·검수 FAIL 재발송 제외), 검수 불합격(FAIL) 이력 품목은
     * 유형 무관 재요청 불가(같은 상품이 이미 불합격 판정을 받았고 재발송됐으므로 재요청은 운영 판단 영역·CLM-2 재요청 허용의 예외).
     *
     * @throws ClaimInvalidStateException 사유 부적합·미배송완료·기한 경과·FAIL 이력(422 CLAIM_STATE_INVALID 재사용)
     */
    private void validateReturnRequest(OrderItem orderItem, ClaimType claimType, ClaimReasonCode reasonCode,
            LocalDateTime requestedAt) {
        String label = claimType == ClaimType.EXCHANGE ? "교환" : "반품";
        if (!reasonCode.isApplicableTo(claimType)) {
            throw new ClaimInvalidStateException(label + " 사유로 사용할 수 없는 코드입니다: " + reasonCode);
        }
        if (orderItem.getItemStatus() != OrderItemStatus.DELIVERED) {
            throw new ClaimInvalidStateException(
                    label + "은 배송완료 품목만 요청할 수 있습니다: " + orderItem.getItemStatus());
        }
        if (claimRepository.existsByOrderItemIdAndInspectionResult(orderItem.getId(), ClaimInspectionResult.FAIL)) {
            throw new ClaimInvalidStateException(
                    "검수 불합격 이력이 있는 품목은 " + label + "을 다시 요청할 수 없습니다: orderItemId=" + orderItem.getId());
        }
        LocalDateTime deliveredAt = returnWindowPolicy.originalDeliveredAt(orderItem.getId())
                .orElseThrow(() -> new ClaimInvalidStateException(
                        "배송완료 기록이 없어 " + label + " 기한을 판정할 수 없습니다: orderItemId=" + orderItem.getId()));
        if (!ReturnWindowPolicy.isWithinWindow(deliveredAt, requestedAt)) {
            throw new ClaimInvalidStateException(
                    label + " 가능 기간(배송완료 후 " + ReturnWindowPolicy.WINDOW_DAYS + "일)이 지났습니다: 배송완료 " + deliveredAt);
        }
    }

    /**
     * 관리자 취소 진입점(Track 79 D-168·A). 소유 검증을 생략하고 requestedBy=관리자 user id로 Claim(CANCEL)을 생성한 뒤 같은
     * 트랜잭션에서 {@link ClaimService#approve}까지 진행한다(REQUESTED → APPROVED 1 TX). 정책(PAID·PREPARING 한정·항목 단위·CLM-5·사유 코드)은
     * 사용자 경로와 동일한 {@link #createClaim} 코어를 재사용한다. 이후 환불 initiate → 웹훅 완료 → COMPLETED → 항목 CANCELLED·재고
     * 복구는 기존 AFTER_COMMIT 경로 그대로다("즉시 완료"가 아니라 "승인까지 자동"·상태기계 §2 CANCEL COMPLETED = Refund COMPLETED 유지).
     *
     * @throws ClaimNotFoundException     주문 품목이 없는 경우(404)
     * @throws ClaimInvalidStateException 활성 클레임 중복(CLM-5)·품목 상태가 CANCEL 요청 전이 불가인 경우(422)
     */
    public Claim requestByAdmin(Long orderItemId, ClaimReasonCode reasonCode, String reasonDetail,
            Long adminUserId, LocalDateTime now) {
        orderItemRepository.findOrderIdById(orderItemId).ifPresent(orderService::lockForWrite); // Track 104-1 D-215(P5)
        Claim claim = createClaim(orderItemId, ClaimType.CANCEL, reasonCode, reasonDetail, adminUserId, now, null);
        claimService.approve(claim.getId(), now, null);
        return claim;
    }
}
