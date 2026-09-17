package com.zslab.mall.claim.service;

import com.zslab.mall.claim.controller.request.ClaimRequestCommand;
import com.zslab.mall.claim.controller.response.ClaimResponse;
import com.zslab.mall.claim.controller.response.ClaimSummaryResponse;
import com.zslab.mall.attachment.entity.Attachment;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimInspectionResult;
import com.zslab.mall.claim.enums.ClaimReasonCode;
import com.zslab.mall.claim.enums.ClaimRejectReasonCode;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.event.ClaimApproved;
import com.zslab.mall.claim.event.ClaimCompleted;
import com.zslab.mall.claim.event.ClaimInspectionPassed;
import com.zslab.mall.claim.event.ClaimPickedUp;
import com.zslab.mall.claim.event.ClaimRejected;
import com.zslab.mall.claim.event.ClaimRequested;
import com.zslab.mall.claim.exception.ClaimInvalidStateException;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.common.observability.TracedEventPublisher;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.delivery.service.DeliveryService;
import com.zslab.mall.delivery.service.ReturnWindowPolicy;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.refund.entity.Refund;
import com.zslab.mall.refund.enums.RefundStatus;
import com.zslab.mall.refund.repository.RefundRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 클레임 Application Service(Track 9 PR-B·D-89). 요청·승인·거절·종결·조회를 담당한다. 트랜잭션 경계는 메서드 단위다(QB-1).
 *
 * <p><b>요청(request)</b>: type 무관 진입(D-98 Q4 게이트 제거·CANCEL/RETURN/EXCHANGE)·소유권 2단계 조회(Q8)·CLM-5 중복 차단·
 * type별 OrderItem 전이 가능성 검증 후 Claim INSERT(요청 시점 상태 스냅샷 저장·D-98 Q11).
 * 이벤트는 save→publish 순서로 발행한다(D-29·no flush). OrderItem.item_status 실제 전이는 핸들러 소관이며 본 메서드는 발행만 한다.
 *
 * <p><b>승인/거절(approve·reject)</b>: 본 PR은 Service 계층만 완성하며 endpoint는 Track 10 소관이다(D-88 Q2). E2E는 Service 직접 호출.
 *
 * <p>orderItemPublicId(oit_)는 진입점에서 {@link OrderItemRepository#findByPublicId}로 BIGINT id를 해소한다(D-64·D-65).
 */
@Slf4j
@Service
@Transactional
public class ClaimService {

    /** 사진 첨부를 허용하는 반품·교환 사유(Track 81-B D-171·R2 / Track 83 D-177 결정 3 동일 규칙). 단순변심 첨부는 400. */
    private static final Set<ClaimReasonCode> ATTACHABLE_RETURN_REASONS =
            Set.of(ClaimReasonCode.PRODUCT_DEFECT, ClaimReasonCode.WRONG_PRODUCT);

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ClaimRepository claimRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderRepository orderRepository;
    private final TracedEventPublisher eventPublisher;
    private final DeliveryService deliveryService;
    private final RefundRepository refundRepository;
    private final DeliveryRepository deliveryRepository;
    private final ReturnWindowPolicy returnWindowPolicy;
    private final ClaimAttachmentService claimAttachmentService;
    private final ClaimExchangeService claimExchangeService;
    private final EntityManager entityManager;

    public ClaimService(
            ClaimRepository claimRepository,
            OrderItemRepository orderItemRepository,
            OrderRepository orderRepository,
            TracedEventPublisher eventPublisher,
            DeliveryService deliveryService,
            RefundRepository refundRepository,
            DeliveryRepository deliveryRepository,
            ReturnWindowPolicy returnWindowPolicy,
            ClaimAttachmentService claimAttachmentService,
            ClaimExchangeService claimExchangeService,
            EntityManager entityManager) {
        this.claimRepository = claimRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
        this.deliveryService = deliveryService;
        this.refundRepository = refundRepository;
        this.deliveryRepository = deliveryRepository;
        this.returnWindowPolicy = returnWindowPolicy;
        this.claimAttachmentService = claimAttachmentService;
        this.claimExchangeService = claimExchangeService;
        this.entityManager = entityManager;
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
        // (0) 첨부(Track 81-B·D-177 결정 3): RETURN·EXCHANGE + 불량/오배송에서만 허용. 소유권·미연결 검증은 락 전에(읽기만) 끝낸다.
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
        return claim;
    }

    /**
     * RETURN·EXCHANGE 요청 조건 검증(Track 81-A D-170·보충 / Track 83 D-177 동일 적용). 사유는 {@link ClaimReasonCode#isApplicableTo}
     * (단순변심·상품불량·오배송), 품목은 DELIVERED(SHIPPING 중 요청은 매트릭스상 가능하나 배송완료 기준 기한을 셀 수 없어 서비스에서 차단),
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
     * 트랜잭션에서 {@link #approve}까지 진행한다(REQUESTED → APPROVED 1 TX). 정책(PAID·PREPARING 한정·항목 단위·CLM-5·사유 코드)은
     * 사용자 경로와 동일한 {@link #createClaim} 코어를 재사용한다. 이후 환불 initiate → 웹훅 완료 → COMPLETED → 항목 CANCELLED·재고
     * 복구는 기존 AFTER_COMMIT 경로 그대로다("즉시 완료"가 아니라 "승인까지 자동"·상태기계 §2 CANCEL COMPLETED = Refund COMPLETED 유지).
     *
     * @throws ClaimNotFoundException     주문 품목이 없는 경우(404)
     * @throws ClaimInvalidStateException 활성 클레임 중복(CLM-5)·품목 상태가 CANCEL 요청 전이 불가인 경우(422)
     */
    public Claim requestByAdmin(Long orderItemId, ClaimReasonCode reasonCode, String reasonDetail,
            Long adminUserId, LocalDateTime now) {
        Claim claim = createClaim(orderItemId, ClaimType.CANCEL, reasonCode, reasonDetail, adminUserId, now, null);
        approve(claim.getId(), now, null);
        return claim;
    }

    /**
     * Claim 승인 도메인 전이 primitive. save 직후 {@link ClaimApproved}를 발행한다(D-29).
     *
     * <p>외부 HTTP 진입점 직접 호출 금지. 외부 액터(Seller/Admin) 호출은 wrapper 메서드(approveBySeller 등) 경유 의무.
     * 본 primitive는 권한 검증 미수행·도메인 상태 전이 단독 책임이다. 직접 호출은 도메인 내부 호출 또는 통합 테스트
     * 순수 상태 전이 검증에 한정한다.
     *
     * <p>D-92 횡단 원칙 정합: 도메인 상태 전이 메서드는 actor 식별자가 상태 자체에 포함되지 않는 한
     * actor 비의존 시그니처를 우선한다.
     *
     * <p>교환 차액 환불(D-115)은 D-177(같은 가격 옵션만 교환)로 폐기됐다. {@code refundAmount}는 호환 인자로만 남기며 값이 실려오면
     * 400으로 거절한다(결정 5). EXCHANGE 승인은 클레임 행을 잠가(동시 승인 직렬화·늦은 쪽 422) 교환 옵션을 재검증하고 재고를 예약한다
     * (결정 8·9·10·락 순서 Claim → OrderItem 읽기 → Inventory 최후).
     *
     * @throws ClaimNotFoundException     클레임이 없는 경우
     * @throws ClaimInvalidStateException REQUESTED가 아닌 경우(CLM-4)·교환 옵션 부적합(422)
     * @throws MalformedRequestException  refundAmount가 전달된 경우(400·D-115 폐기)
     * @throws com.zslab.mall.inventory.exception.InventoryInvariantViolationException 교환 옵션 재고 부족(422)
     */
    public void approve(Long claimId, LocalDateTime processedAt, Long refundAmount) {
        if (refundAmount != null) {
            throw new MalformedRequestException("refundAmount는 더 이상 지원하지 않습니다(교환 차액 환불 폐기·D-177).");
        }
        Claim claim = findClaim(claimId);
        if (claim.getType() == ClaimType.EXCHANGE) {
            entityManager.refresh(claim, LockModeType.PESSIMISTIC_WRITE);
        }
        claim.approve(processedAt);
        if (claim.getType() == ClaimType.EXCHANGE) {
            claimExchangeService.reserveExchangeStock(claim, processedAt);
        }
        claimRepository.save(claim);
        eventPublisher.publishEvent(new ClaimApproved(
                claim.getId(), claim.getPublicId(), claim.getOrderItemId(),
                claim.getType(), claim.getStatus(), LocalDateTime.now()));
    }

    /**
     * Claim 거부 도메인 전이 primitive. save 직후 {@link ClaimRejected}를 발행한다(D-29·CLM-2 이력 보존).
     *
     * <p>외부 HTTP 진입점 직접 호출 금지. 외부 액터(Seller/Admin) 호출은 wrapper 메서드(rejectBySeller 등) 경유 의무.
     * 본 primitive는 권한 검증 미수행·도메인 상태 전이 단독 책임이다. 직접 호출은 도메인 내부 호출 또는 통합 테스트
     * 순수 상태 전이 검증에 한정한다.
     *
     * <p>D-92 횡단 원칙 정합: 도메인 상태 전이 메서드는 actor 식별자가 상태 자체에 포함되지 않는 한
     * actor 비의존 시그니처를 우선한다.
     *
     * <p>거부 사유 코드는 필수·메모는 선택이다(Track 80 D-169). 사유·유형 부적합(ALREADY_SHIPPED는 CANCEL 전용)·메모 길이는
     * {@link Claim#reject}가 검증한다(IllegalArgumentException → 400).
     *
     * @throws ClaimNotFoundException     클레임이 없는 경우
     * @throws ClaimInvalidStateException REQUESTED가 아닌 경우(CLM-4)
     * @throws IllegalArgumentException   거부 사유 누락·유형 부적합·메모 500자 초과
     */
    public void reject(Long claimId, ClaimRejectReasonCode reasonCode, String memo, LocalDateTime processedAt) {
        Claim claim = findClaim(claimId);
        claim.reject(reasonCode, memo, processedAt);
        claimRepository.save(claim);
        eventPublisher.publishEvent(new ClaimRejected(
                claim.getId(), claim.getPublicId(), claim.getOrderItemId(),
                claim.getType(), claim.getStatus(), claim.getRejectReasonCode(), LocalDateTime.now()));
    }

    /**
     * 구매자 반품 회수 송장 등록(Track 81-A D-170·R4). 본인이 요청한 RETURN·APPROVED·회수 확인 전 클레임에만 허용한다.
     * 소유 위반·미존재는 404(정보 노출 회피·Q8), 유형·상태 위반은 422. Delivery 생성·SHIPPING·이벤트는 {@link DeliveryService#registerReturnShipment}.
     *
     * @throws ClaimNotFoundException     클레임이 없거나 요청자가 아닌 경우
     * @throws ClaimInvalidStateException type != RETURN·APPROVED 아님·이미 회수 확인됨·회수 송장 중복(422)
     */
    public Delivery registerReturnShipmentByBuyer(String claimPublicId, Long buyerId, DeliveryCarrier carrier, String trackingNo) {
        Claim claim = claimRepository.findByPublicId(claimPublicId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: " + claimPublicId));
        Long claimId = claim.getId();
        if (!buyerId.equals(claim.getRequestedBy())) {
            throw new ClaimNotFoundException("클레임을 찾을 수 없습니다: " + claimPublicId);
        }
        if (!claim.getType().isPickupBased()) {
            throw new ClaimInvalidStateException("회수 송장은 RETURN·EXCHANGE 클레임에만 등록할 수 있습니다: type=" + claim.getType());
        }
        if (claim.getStatus() != ClaimStatus.APPROVED) {
            throw new ClaimInvalidStateException("승인된 반품·교환만 회수 송장을 등록할 수 있습니다: " + claim.getStatus());
        }
        if (claim.getPickedUpAt() != null) {
            throw new ClaimInvalidStateException("이미 회수 확인된 반품입니다: claimId=" + claimId);
        }
        return deliveryService.registerReturnShipment(claim, carrier, trackingNo);
    }

    /**
     * Seller 액터의 Claim 승인 진입점(Track 10·D-92 Q3-sub a‴).
     *
     * <p>처리 순서: 조회 → 권한 검증 → 도메인 전이. 권한 위반은 404({@link ClaimNotFoundException})로 응답하여
     * cross-tenant 정보 노출을 회피한다. 권한 검증 후 {@link #approve} primitive에 위임한다(클래스 단위 단일 트랜잭션).
     *
     * @throws ClaimNotFoundException     클레임이 없거나 요청 Seller 소유 품목이 아닌 경우
     * @throws ClaimInvalidStateException 상태가 REQUESTED가 아닌 경우(CLM-4)
     */
    public void approveBySeller(Long claimId, Long sellerId, LocalDateTime processedAt, Long refundAmount) {
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: claimId=" + claimId));
        authorizeSellerAccess(claim, sellerId);
        approve(claimId, processedAt, refundAmount);
    }

    /**
     * Seller 액터의 Claim 거부 진입점(Track 10·D-92 Q3-sub a‴).
     *
     * <p>처리 순서: 조회 → 권한 검증 → 도메인 전이. 권한 위반은 404({@link ClaimNotFoundException})로 응답하여
     * cross-tenant 정보 노출을 회피한다. 권한 검증 후 {@link #reject} primitive에 위임한다(클래스 단위 단일 트랜잭션).
     *
     * @throws ClaimNotFoundException     클레임이 없거나 요청 Seller 소유 품목이 아닌 경우
     * @throws ClaimInvalidStateException 상태가 REQUESTED가 아닌 경우(CLM-4)
     */
    public void rejectBySeller(Long claimId, Long sellerId, ClaimRejectReasonCode reasonCode, String memo,
            LocalDateTime processedAt) {
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: claimId=" + claimId));
        authorizeSellerAccess(claim, sellerId);
        reject(claimId, reasonCode, memo, processedAt);
    }

    /**
     * Admin 액터의 Claim 승인 진입점(Track 10-B·D-93 Q3·Q5·전체 접근).
     *
     * <p>Admin은 전체 Claim 접근 권한을 가지므로 권한 검증 단락이 부재한다(D-93 Q3·stub 단계 한정). Seller wrapper의
     * {@link #authorizeSellerAccess}에 대응하는 cross-tenant 검증이 없으며 Claim 미존재만 404다. {@link #approve}
     * primitive에 위임한다(클래스 단위 단일 트랜잭션).
     *
     * <p>D-92 횡단 원칙 재사용 1회차(D-93): 액터별 권한 차이는 wrapper 진입점에서 캡슐화하고 primitive는 actor
     * 비의존 시그니처를 유지한다.
     *
     * @throws ClaimNotFoundException     클레임이 없는 경우
     * @throws ClaimInvalidStateException 상태가 REQUESTED가 아닌 경우(CLM-4)
     */
    public void approveByAdmin(Long claimId, LocalDateTime processedAt, Long refundAmount) {
        approve(claimId, processedAt, refundAmount);
    }

    /**
     * Admin 액터의 Claim 거부 진입점(Track 10-B·D-93 Q3·Q5·전체 접근).
     *
     * <p>Admin은 전체 Claim 접근 권한을 가지므로 권한 검증 단락이 부재한다(D-93 Q3·stub 단계 한정). Claim 미존재만
     * 404다. {@link #reject} primitive에 위임한다(클래스 단위 단일 트랜잭션).
     *
     * <p>D-92 횡단 원칙 재사용 1회차(D-93): 액터별 권한 차이는 wrapper 진입점에서 캡슐화하고 primitive는 actor
     * 비의존 시그니처를 유지한다.
     *
     * @throws ClaimNotFoundException     클레임이 없는 경우
     * @throws ClaimInvalidStateException 상태가 REQUESTED가 아닌 경우(CLM-4)
     */
    public void rejectByAdmin(Long claimId, ClaimRejectReasonCode reasonCode, String memo, LocalDateTime processedAt) {
        reject(claimId, reasonCode, memo, processedAt);
    }

    /**
     * 본인 클레임 단건을 조회한다. 소유권은 requested_by로 판정하며, 미존재·타인 클레임 모두 404다(정보 노출 회피·Q8).
     *
     * @throws ClaimNotFoundException 클레임이 없거나 소유자가 다른 경우
     */
    @Transactional(readOnly = true)
    public ClaimResponse getClaim(String claimPublicId, Long buyerId) {
        Claim claim = claimRepository.findByPublicId(claimPublicId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: " + claimPublicId));
        if (!buyerId.equals(claim.getRequestedBy())) {
            throw new ClaimNotFoundException("클레임을 찾을 수 없습니다: " + claimPublicId);
        }
        String orderItemPublicId = orderItemRepository.findById(claim.getOrderItemId())
                .map(OrderItem::getPublicId)
                .orElseThrow(() -> new IllegalStateException(
                        "클레임의 주문 품목을 찾을 수 없습니다: orderItemId=" + claim.getOrderItemId()));
        RefundStatus refundStatus = latestRefundStatusByClaimId(List.of(claim.getId())).get(claim.getId());
        // Track 81-A·FE-29: 클레임 연결 Delivery 1쿼리(id 내림차순) → 회수(RETURN)·검수 불합격 재발송(OUTBOUND) 방향별 최신 1건
        Delivery returnDelivery = null;
        Delivery reshipment = null;
        if (claim.getType().isPickupBased()) {
            for (Delivery delivery : deliveryRepository.findByClaimIdInOrderByIdDesc(List.of(claim.getId()))) {
                if (delivery.getDirection() == DeliveryDirection.RETURN && returnDelivery == null) {
                    returnDelivery = delivery;
                } else if (delivery.getDirection() == DeliveryDirection.OUTBOUND && reshipment == null) {
                    reshipment = delivery;
                }
            }
        }
        // Track 83 D-177: 교환/원 옵션 라벨(EXCHANGE만·배치 1회). 원 옵션은 승인 스냅샷 우선, 없으면(승인 전) 현재 품목 variant로 재조립.
        String exchangeOptionLabel = null;
        String originalOptionLabel = null;
        if (claim.getType() == ClaimType.EXCHANGE) {
            Long originalVariantId = claim.getOriginalVariantId() != null ? claim.getOriginalVariantId()
                    : orderItemRepository.findById(claim.getOrderItemId()).map(OrderItem::getVariantId).orElse(null);
            Set<Long> variantIds = new java.util.LinkedHashSet<>();
            variantIds.add(claim.getExchangeVariantId());
            if (originalVariantId != null) {
                variantIds.add(originalVariantId);
            }
            Map<Long, String> labels = claimExchangeService.optionLabelsByVariantId(variantIds);
            exchangeOptionLabel = labels.get(claim.getExchangeVariantId());
            originalOptionLabel = claim.getOriginalOptionLabel() != null ? claim.getOriginalOptionLabel()
                    : (originalVariantId == null ? null : labels.get(originalVariantId));
        }
        // Track 81-B: 첨부 URL 1쿼리(순서 보존·없으면 빈 목록)
        return ClaimResponse.from(claim, orderItemPublicId, refundStatus, returnDelivery,
                claimAttachmentService.urlsOf(claim.getId()), reshipment, exchangeOptionLabel, originalOptionLabel);
    }

    /** 본인 클레임 목록(requested_by 기준·D-54 페이징). size는 1~100 클램프. 환불 상태는 페이지 단위 배치 1쿼리(Track 80). */
    @Transactional(readOnly = true)
    public PagedResponse<ClaimSummaryResponse> listClaims(Long buyerId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size));
        Page<Claim> claimPage = claimRepository.findAllByRequestedBy(buyerId, pageable);
        Map<Long, RefundStatus> refundStatusByClaimId = latestRefundStatusByClaimId(
                claimPage.getContent().stream().map(Claim::getId).toList());
        Page<ClaimSummaryResponse> claims = claimPage
                .map(claim -> ClaimSummaryResponse.from(claim, refundStatusByClaimId.get(claim.getId())));
        return PagedResponse.from(claims);
    }

    /** 클레임별 최신 환불 상태(id 내림차순 조회·first-wins). 환불 미생성 클레임은 키가 없다. 빈 입력은 0쿼리. */
    private Map<Long, RefundStatus> latestRefundStatusByClaimId(List<Long> claimIds) {
        if (claimIds.isEmpty()) {
            return Map.of();
        }
        return refundRepository.findByClaimIdInOrderByIdDesc(claimIds).stream()
                .collect(Collectors.toMap(Refund::getClaimId, Refund::getStatus, (latest, older) -> latest));
    }

    /**
     * 클레임을 종결한다(APPROVED → COMPLETED·CLM-4). 이미 COMPLETED면 멱등 no-op이다(CLM-1).
     *
     * <p>호출 맥락은 {@code ClaimRefundCompletedHandler}(Claim.type=CANCEL·AFTER_COMMIT)다. 처리 시각은 시스템 시각으로 채운다.
     * save 직후 {@link ClaimCompleted}를 발행한다(D-29 save→publish·D-90 Q4). 소비 핸들러 {@code ClaimCompletedHandler}가
     * OrderItem을 CANCEL_REQUESTED → CANCELLED로 종결한다(멱등 no-op 시 미발행).
     *
     * @throws ClaimNotFoundException     클레임이 없는 경우
     * @throws ClaimInvalidStateException APPROVED·COMPLETED가 아닌 상태에서 호출된 경우(CLM-1·CLM-4)
     */
    public void markCompleted(Long claimId) {
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: claimId=" + claimId));
        if (claim.getStatus() == ClaimStatus.COMPLETED) {
            log.info("[Claim] markCompleted 멱등 NO-OP(이미 COMPLETED): claimId={}", claimId);
            return;
        }
        claim.markCompleted(LocalDateTime.now());
        claimRepository.save(claim);
        eventPublisher.publishEvent(new ClaimCompleted(
                claim.getId(), claim.getPublicId(), claim.getOrderItemId(),
                claim.getType(), claim.getStatus(), LocalDateTime.now()));
    }

    /**
     * 클레임 수거 확인 도메인 전이 primitive(D-98 Q1·actor·type 비의존). save 직후 {@link ClaimPickedUp}(E11)을 발행한다(D-29).
     *
     * <p>멱등 no-op: 이미 picked_up_at != null이면 변경 없이 log.info 후 return({@link #markCompleted} 멱등 가드 패턴 1:1).
     * 합법 상태 전이(status == APPROVED 가드)는 {@link Claim#confirmPickup}이 수행한다.
     *
     * <p>외부 HTTP 진입점 직접 호출 금지. 외부 액터(Seller/Admin) 호출은 wrapper(confirmPickupBySeller 등) 경유 의무.
     *
     * @throws ClaimNotFoundException     클레임이 없는 경우
     * @throws ClaimInvalidStateException APPROVED가 아닌 경우(CLM-4)
     */
    public void confirmPickup(Long claimId, LocalDateTime pickedUpAt) {
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: claimId=" + claimId));
        if (claim.getPickedUpAt() != null) {
            log.info("[Claim] confirmPickup 멱등 NO-OP(이미 picked_up_at 설정됨): claimId={}", claimId);
            return;
        }
        claim.confirmPickup(pickedUpAt);
        if (claim.getType().isPickupBased()) {
            // Track 81-A D-170·D-177: 반품·교환 회수 확인은 구매자 회수 송장(RETURN Delivery)이 선행돼야 하며 그 Delivery를 DELIVERED로 마감한다(부재 422).
            deliveryService.completeReturnShipment(claimId);
        }
        claimRepository.save(claim);
        eventPublisher.publishEvent(new ClaimPickedUp(
                claim.getId(), claim.getPublicId(), claim.getOrderItemId(),
                claim.getType(), pickedUpAt, LocalDateTime.now()));
    }

    /**
     * Seller 액터의 Claim 수거 확인 진입점(D-98 Q9·D-92 횡단 원칙 재사용 2회차).
     *
     * <p>처리 순서: 조회 → 권한 검증 → 도메인 전이. 권한 위반은 404({@link ClaimNotFoundException})로 응답하여
     * cross-tenant 정보 노출을 회피한다. 권한 검증 후 {@link #confirmPickup} primitive에 위임한다.
     *
     * @throws ClaimNotFoundException     클레임이 없거나 요청 Seller 소유 품목이 아닌 경우
     * @throws ClaimInvalidStateException APPROVED가 아닌 경우(CLM-4)
     */
    public void confirmPickupBySeller(Long claimId, Long sellerId, LocalDateTime pickedUpAt) {
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: claimId=" + claimId));
        authorizeSellerAccess(claim, sellerId);
        confirmPickup(claimId, pickedUpAt);
    }

    /**
     * Admin 액터의 Claim 수거 확인 진입점(D-98 Q9·D-92 횡단 원칙 재사용 2회차).
     *
     * <p>Admin은 전체 Claim 접근 권한을 가지므로 권한 검증 단락이 부재한다(D-93 Q3). Claim 미존재만 404다.
     * {@link #confirmPickup} primitive에 위임한다.
     *
     * @throws ClaimNotFoundException     클레임이 없는 경우
     * @throws ClaimInvalidStateException APPROVED가 아닌 경우(CLM-4)
     */
    public void confirmPickupByAdmin(Long claimId, LocalDateTime pickedUpAt) {
        confirmPickup(claimId, pickedUpAt);
    }

    /**
     * 반품 검수 primitive(Track 81-A D-170·R5·actor 비의존). 클레임 행을 {@code refresh(PESSIMISTIC_WRITE)}로 잠가 동시 검수를 직렬화한
     * 뒤(늦은 쪽은 "이미 검수됨" 422) PASS/FAIL을 적용한다.
     * <ul>
     *   <li>PASS: {@link Claim#passInspection}(restock 필수) → {@link ClaimInspectionPassed} 발행 → 환불 개시(핸들러)</li>
     *   <li>FAIL: {@link Claim#failInspection}(거부 사유 필수·APPROVED → REJECTED 예외 전이) → 재발송 Delivery(OUTBOUND·택배사·송장 필수)
     *       → {@link ClaimRejected} 발행 → 품목 스냅샷 원복(DELIVERED)·거부 SMS(기존 경로)</li>
     * </ul>
     * 외부 HTTP 진입점 직접 호출 금지. 외부 액터 호출은 wrapper(inspectBySeller 등) 경유 의무.
     *
     * @throws ClaimNotFoundException     클레임이 없는 경우
     * @throws ClaimInvalidStateException type != RETURN·APPROVED 아님·미회수·이미 검수됨·재발송 중복(422)
     * @throws IllegalArgumentException   PASS인데 restock 누락 / FAIL인데 사유·택배사·송장 누락·사유가 INSPECTION_FAILED가 아님(400·D-172)
     */
    public void inspect(Long claimId, ClaimInspectionResult result, Boolean restock, ClaimRejectReasonCode rejectReasonCode,
            String memo, DeliveryCarrier reshipCarrier, String reshipTrackingNo, LocalDateTime inspectedAt) {
        if (result == null) {
            throw new IllegalArgumentException("inspect: 검수 결과는 필수입니다.");
        }
        Claim claim = findClaim(claimId);
        entityManager.refresh(claim, LockModeType.PESSIMISTIC_WRITE);
        if (result == ClaimInspectionResult.PASS) {
            if (restock == null) {
                throw new IllegalArgumentException("inspect: PASS는 재입고 여부(restock)가 필수입니다.");
            }
            claim.passInspection(restock, inspectedAt);
            claimRepository.save(claim);
            eventPublisher.publishEvent(new ClaimInspectionPassed(
                    claim.getId(), claim.getPublicId(), claim.getOrderItemId(), restock, LocalDateTime.now()));
            return;
        }
        if (reshipCarrier == null || reshipTrackingNo == null || reshipTrackingNo.isBlank()) {
            throw new IllegalArgumentException("inspect: FAIL은 재발송 택배사·송장번호가 필수입니다.");
        }
        claim.failInspection(rejectReasonCode, memo, inspectedAt);
        claimRepository.save(claim);
        deliveryService.registerReshipment(claim, reshipCarrier, reshipTrackingNo);
        // D-177 결정 8: 교환 검수 FAIL은 종결(REJECTED)이므로 승인 시 예약한 교환 옵션 재고를 되돌린다(Inventory 최후·멱등).
        claimExchangeService.releaseExchangeReservationIfAny(claim);
        eventPublisher.publishEvent(new ClaimRejected(
                claim.getId(), claim.getPublicId(), claim.getOrderItemId(),
                claim.getType(), claim.getStatus(), claim.getRejectReasonCode(), LocalDateTime.now()));
    }

    /**
     * Seller 액터의 반품 검수 진입점(Track 81-A). 조회 → 권한 검증(품목 소유·위반 404) → {@link #inspect} primitive.
     */
    public void inspectBySeller(Long claimId, Long sellerId, ClaimInspectionResult result, Boolean restock,
            ClaimRejectReasonCode rejectReasonCode, String memo, DeliveryCarrier reshipCarrier, String reshipTrackingNo,
            LocalDateTime inspectedAt) {
        Claim claim = findClaim(claimId);
        authorizeSellerAccess(claim, sellerId);
        inspect(claimId, result, restock, rejectReasonCode, memo, reshipCarrier, reshipTrackingNo, inspectedAt);
    }

    /** Admin 액터의 반품 검수 진입점(Track 81-A·전체 접근·미존재만 404). */
    public void inspectByAdmin(Long claimId, ClaimInspectionResult result, Boolean restock,
            ClaimRejectReasonCode rejectReasonCode, String memo, DeliveryCarrier reshipCarrier, String reshipTrackingNo,
            LocalDateTime inspectedAt) {
        inspect(claimId, result, restock, rejectReasonCode, memo, reshipCarrier, reshipTrackingNo, inspectedAt);
    }

    /**
     * Seller 액터의 EXCHANGE 출고 등록 진입점(D-99 Q9 γ·D-92 횡단 원칙 재사용 3회차).
     *
     * <p>처리 순서: 조회 → 권한 검증 → primitive 위임. {@link #authorizeSellerAccess} 1:1 재사용 후
     * {@link DeliveryService#registerExchangeShipment} primitive에 위임한다. 권한 위반은 404({@link ClaimNotFoundException})로
     * 응답하여 cross-tenant 정보 노출을 회피한다. 이중 호출 멱등 가드는 primitive 진입부 책임이다(D-99 Q11).
     *
     * @return 생성된 Delivery(SHIPPING·claim_id 연결 완료)
     * @throws ClaimNotFoundException     클레임이 없거나 요청 Seller 소유 품목이 아닌 경우
     * @throws ClaimInvalidStateException type != EXCHANGE·orderItemId 불일치·이중 호출(DeliveryService 위임)
     */
    public Delivery registerExchangeShipmentBySeller(
            Long claimId, Long sellerId, DeliveryCarrier carrier, String trackingNo) {
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: claimId=" + claimId));
        authorizeSellerAccess(claim, sellerId);
        return deliveryService.registerExchangeShipment(claimId, carrier, trackingNo);
    }

    /**
     * Seller 액터의 Claim 접근 권한을 검증한다(D-92 Q3 실패 우선순위 최선두: 권한 → 상태 → 전이).
     *
     * <p>{@link OrderItem#getSellerId()}와 요청 sellerId가 불일치하면 {@link ClaimNotFoundException}을 던진다
     * (cross-tenant 정보 노출 회피·404 매핑). Claim이 참조하는 OrderItem 부재는 데이터 무결성 위반이므로 500이다.
     *
     * @throws ClaimNotFoundException 요청 Seller 소유 품목이 아닌 경우(권한 위반 은닉)
     * @throws IllegalStateException  Claim이 참조하는 OrderItem이 부재한 경우(무결성 위반)
     */
    private void authorizeSellerAccess(Claim claim, Long sellerId) {
        OrderItem orderItem = orderItemRepository.findById(claim.getOrderItemId())
                .orElseThrow(() -> new IllegalStateException(
                        "OrderItem 무결성 위반: orderItemId=" + claim.getOrderItemId()));
        if (!orderItem.getSellerId().equals(sellerId)) {
            throw new ClaimNotFoundException("클레임을 찾을 수 없습니다: claimId=" + claim.getId());
        }
    }

    private Claim findClaim(Long claimId) {
        return claimRepository.findById(claimId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: claimId=" + claimId));
    }

    private int clampSize(int size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
