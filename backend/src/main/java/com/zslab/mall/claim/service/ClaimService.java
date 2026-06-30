package com.zslab.mall.claim.service;

import com.zslab.mall.claim.controller.request.ClaimRequestCommand;
import com.zslab.mall.claim.controller.response.ClaimResponse;
import com.zslab.mall.claim.controller.response.ClaimSummaryResponse;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.event.ClaimApproved;
import com.zslab.mall.claim.event.ClaimCompleted;
import com.zslab.mall.claim.event.ClaimPickedUp;
import com.zslab.mall.claim.event.ClaimRejected;
import com.zslab.mall.claim.event.ClaimRequested;
import com.zslab.mall.claim.exception.ClaimInvalidStateException;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.common.observability.TracedEventPublisher;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.service.DeliveryService;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.repository.OrderRepository;
import java.time.LocalDateTime;
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

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ClaimRepository claimRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderRepository orderRepository;
    private final TracedEventPublisher eventPublisher;
    private final DeliveryService deliveryService;

    public ClaimService(
            ClaimRepository claimRepository,
            OrderItemRepository orderItemRepository,
            OrderRepository orderRepository,
            TracedEventPublisher eventPublisher,
            DeliveryService deliveryService) {
        this.claimRepository = claimRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
        this.deliveryService = deliveryService;
    }

    /**
     * 클레임을 요청한다(REQUESTED 생성·D-89 Q1·Q6·Q8·CLM-5). save 직후 {@link ClaimRequested}를 발행한다(D-29 save→publish).
     *
     * @param command orderItemPublicId·claimType·reasonCode·buyerId·requestedAt
     * @return 생성된 Claim(public_id·id 부여 완료)
     * @throws ClaimNotFoundException     주문 품목이 없거나 소유자가 다른 경우(정보 노출 회피·T3)
     * @throws ClaimInvalidStateException 활성 클레임 중복(CLM-5)·OrderItem 상태가 해당 type 요청 전이 불가인 경우(422)
     */
    public Claim request(ClaimRequestCommand command) {
        // (a) OrderItem public_id → id 해소(D-64·D-65). 미존재 시 404.
        OrderItem orderItem = orderItemRepository.findByPublicId(command.orderItemPublicId())
                .orElseThrow(() -> new ClaimNotFoundException(
                        "주문 품목을 찾을 수 없습니다: " + command.orderItemPublicId()));

        // (b)(c) 소유권 검증: order_item → order → buyer_id 2단계 조회(Q8). 불일치 시 404(정보 누출 차단·T3).
        Long orderId = orderItemRepository.findOrderIdById(orderItem.getId())
                .orElseThrow(() -> new ClaimNotFoundException(
                        "주문 품목을 찾을 수 없습니다: " + command.orderItemPublicId()));
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ClaimNotFoundException(
                        "주문을 찾을 수 없습니다: orderItemPublicId=" + command.orderItemPublicId()));
        if (!order.getBuyerId().equals(command.buyerId())) {
            throw new ClaimNotFoundException("주문 품목을 찾을 수 없습니다: " + command.orderItemPublicId());
        }

        // (d) CLM-5: 동일 OrderItem 활성 클레임(REQUESTED·APPROVED) 중복 차단(422).
        if (claimRepository.existsActiveByOrderItemId(orderItem.getId())) {
            throw new ClaimInvalidStateException("이미 진행 중인 클레임이 있습니다(CLM-5): " + command.orderItemPublicId());
        }

        // (e) type별 진입 전이 대상 매핑 후 OrderItem 상태 전이 가능성 검증(D-98 Q4·읽기 검증만·실제 전이는 핸들러 소관).
        OrderItemStatus targetStatus = switch (command.claimType()) {
            case CANCEL -> OrderItemStatus.CANCEL_REQUESTED;
            case RETURN -> OrderItemStatus.RETURN_REQUESTED;
            case EXCHANGE -> OrderItemStatus.EXCHANGE_REQUESTED;
        };
        if (!orderItem.getItemStatus().canTransitionTo(targetStatus)) {
            throw new ClaimInvalidStateException(
                    "현재 주문 품목 상태에서 " + command.claimType() + " 요청이 불가합니다: " + orderItem.getItemStatus());
        }

        // (f) Claim 생성·저장 후 이벤트 발행(D-29 save→publish·no flush). public_id·id는 save 시 부여된다.
        //     요청 시점 OrderItem 상태를 스냅샷으로 저장(D-98 Q11·REJECTED 원복용).
        Claim claim = Claim.create(
                orderItem.getId(),
                command.claimType(),
                command.reasonCode().name(),
                command.reasonDetail(),
                command.buyerId(),
                command.requestedAt(),
                orderItem.getItemStatus());
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
     * Claim 승인 도메인 전이 primitive. save 직후 {@link ClaimApproved}를 발행한다(D-29).
     *
     * <p>외부 HTTP 진입점 직접 호출 금지. 외부 액터(Seller/Admin) 호출은 wrapper 메서드(approveBySeller 등) 경유 의무.
     * 본 primitive는 권한 검증 미수행·도메인 상태 전이 단독 책임이다. 직접 호출은 도메인 내부 호출 또는 통합 테스트
     * 순수 상태 전이 검증에 한정한다.
     *
     * <p>D-92 횡단 원칙 정합: 도메인 상태 전이 메서드는 actor 식별자가 상태 자체에 포함되지 않는 한
     * actor 비의존 시그니처를 우선한다.
     *
     * @throws ClaimNotFoundException     클레임이 없는 경우
     * @throws ClaimInvalidStateException REQUESTED가 아닌 경우(CLM-4)
     */
    public void approve(Long claimId, LocalDateTime processedAt) {
        Claim claim = findClaim(claimId);
        claim.approve(processedAt);
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
     * @throws ClaimNotFoundException     클레임이 없는 경우
     * @throws ClaimInvalidStateException REQUESTED가 아닌 경우(CLM-4)
     */
    public void reject(Long claimId, LocalDateTime processedAt) {
        Claim claim = findClaim(claimId);
        claim.reject(processedAt);
        claimRepository.save(claim);
        eventPublisher.publishEvent(new ClaimRejected(
                claim.getId(), claim.getPublicId(), claim.getOrderItemId(),
                claim.getType(), claim.getStatus(), LocalDateTime.now()));
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
    public void approveBySeller(Long claimId, Long sellerId, LocalDateTime processedAt) {
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: claimId=" + claimId));
        authorizeSellerAccess(claim, sellerId);
        approve(claimId, processedAt);
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
    public void rejectBySeller(Long claimId, Long sellerId, LocalDateTime processedAt) {
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: claimId=" + claimId));
        authorizeSellerAccess(claim, sellerId);
        reject(claimId, processedAt);
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
    public void approveByAdmin(Long claimId, LocalDateTime processedAt) {
        approve(claimId, processedAt);
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
    public void rejectByAdmin(Long claimId, LocalDateTime processedAt) {
        reject(claimId, processedAt);
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
        return ClaimResponse.from(claim, orderItemPublicId);
    }

    /** 본인 클레임 목록(requested_by 기준·D-54 페이징). size는 1~100 클램프. */
    @Transactional(readOnly = true)
    public PagedResponse<ClaimSummaryResponse> listClaims(Long buyerId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size));
        Page<ClaimSummaryResponse> claims = claimRepository.findAllByRequestedBy(buyerId, pageable)
                .map(ClaimSummaryResponse::from);
        return PagedResponse.from(claims);
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
