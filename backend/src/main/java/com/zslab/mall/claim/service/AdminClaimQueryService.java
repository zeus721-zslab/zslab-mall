package com.zslab.mall.claim.service;

import com.zslab.mall.attachment.repository.AttachmentCountProjection;
import com.zslab.mall.attachment.repository.AttachmentRepository;
import com.zslab.mall.claim.controller.request.AdminClaimActionFilter;
import com.zslab.mall.claim.controller.request.AdminClaimSort;
import com.zslab.mall.claim.controller.response.AdminClaimListResponse;
import com.zslab.mall.claim.controller.response.AdminClaimSummaryResponse;
import com.zslab.mall.claim.controller.response.ReturnShipmentResponse;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.repository.AdminClaimSpecifications;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.repository.OrderItemOrderProjection;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.refund.entity.Refund;
import com.zslab.mall.refund.enums.RefundStatus;
import com.zslab.mall.refund.repository.OrderItemRefundedProjection;
import com.zslab.mall.refund.repository.RefundRepository;
import com.zslab.mall.refund.repository.RefundedCondition;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.repository.UserRepository;
import com.zslab.mall.user.service.AdminMemberQueryService;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 클레임 목록 조회(Track 80 D-169·{@code AdminOrderQueryService} 패턴). Specification으로 페이지를 잡은 뒤 품목·주문·구매자·환불을
 * 배치 조회해 행을 조립한다(N+1 회피·쿼리 수 고정: count·page·품목·품목→주문 요약·구매자·환불·클레임 Delivery·첨부 개수·대기건수 = 9·
 * Track 81-A +1·Track 81-B +1 · Track 104-3a 품목 기환불액 +1).
 */
@Service
@Transactional(readOnly = true)
public class AdminClaimQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_KEYWORD_LENGTH = 50;

    static final String ACTION_APPROVE = "APPROVE";
    static final String ACTION_REJECT = "REJECT";
    static final String ACTION_CONFIRM_PICKUP = "CONFIRM_PICKUP";
    static final String ACTION_INSPECT = "INSPECT";
    /** 교환품 발송 등록(EXCHANGE·검수 PASS 후·OUTBOUND 미등록·Track 83 D-177). */
    static final String ACTION_REGISTER_EXCHANGE_SHIPMENT = "REGISTER_EXCHANGE_SHIPMENT";
    /** 교환품 배송완료 처리(EXCHANGE·OUTBOUND SHIPPING·reshipment.deliveryId로 mark-delivered·Track 83 D-177). */
    static final String ACTION_MARK_EXCHANGE_DELIVERED = "MARK_EXCHANGE_DELIVERED";
    /**
     * 수동 환불 개시(Track 89-A·D-106 fallback 진입점). 자동 환불(CANCEL 승인·RETURN 검수 PASS 핸들러)이 유실됐거나 FAILED로 끝난
     * APPROVED 클레임에만 노출한다 — 활성 환불(PENDING·COMPLETED·PG 성공이 기록된 FAILED)이 있으면 initiate가 멱등 no-op이라, 품목 잔여
     * 상한이 없으면 initiate가 422라 노출하지 않는다(Track 104-3a).
     * EXCHANGE 차액 환불은 트리거 재설계 이월(D-172 보충)이라 제외한다.
     */
    static final String ACTION_INITIATE_REFUND = "INITIATE_REFUND";

    private final ClaimRepository claimRepository;
    private final OrderItemRepository orderItemRepository;
    private final UserRepository userRepository;
    private final RefundRepository refundRepository;
    private final DeliveryRepository deliveryRepository;
    private final AttachmentRepository attachmentRepository;
    private final ClaimExchangeService claimExchangeService;
    private final AdminMemberQueryService adminMemberQueryService;

    public AdminClaimQueryService(ClaimRepository claimRepository, OrderItemRepository orderItemRepository,
            UserRepository userRepository, RefundRepository refundRepository, DeliveryRepository deliveryRepository,
            AttachmentRepository attachmentRepository,
            ClaimExchangeService claimExchangeService, AdminMemberQueryService adminMemberQueryService) {
        this.claimRepository = claimRepository;
        this.orderItemRepository = orderItemRepository;
        this.userRepository = userRepository;
        this.refundRepository = refundRepository;
        this.deliveryRepository = deliveryRepository;
        this.attachmentRepository = attachmentRepository;
        this.claimExchangeService = claimExchangeService;
        this.adminMemberQueryService = adminMemberQueryService;
    }

    /**
     * 관리자 클레임 목록. keyword는 주문번호 정확일치·구매자 이름/이메일·상품명 부분일치. refundStatus는 최신 환불 상태(Track 89-A).
     * action은 필요 액션(Track 96-4 D-205·{@link #availableActions}와 같은 조건·다른 필터와 AND). pendingCount는 유형 필터만 반영한다.
     *
     * @throws MalformedRequestException keyword가 trim 후 {@value #MAX_KEYWORD_LENGTH}자를 초과하거나 from &gt; to일 때(400)
     */
    public AdminClaimListResponse listClaims(ClaimType type, ClaimStatus status, RefundStatus refundStatus,
            AdminClaimActionFilter action, String keyword, LocalDateTime from, LocalDateTime to, String buyerPublicId,
            AdminClaimSort sort, int page, int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new MalformedRequestException("from은 to보다 늦을 수 없습니다.");
        }
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size), toSort(sort));
        long pendingCount = type == null
                ? claimRepository.countByStatus(ClaimStatus.REQUESTED)
                : claimRepository.countByTypeAndStatus(type, ClaimStatus.REQUESTED);
        // Track 84: buyerPublicId(usr_)는 BUYER 회원 id로 해소해 order.buyer_id 경로로 정확 필터한다(AdminMemberQueryService 공유). 미존재·비BUYER는 빈 페이지(404 아님).
        Long buyerId = null;
        if (buyerPublicId != null && !buyerPublicId.isBlank()) {
            buyerId = adminMemberQueryService.findBuyerId(buyerPublicId.trim()).orElse(null);
            if (buyerId == null) {
                return AdminClaimListResponse.from(PagedResponse.from(Page.empty(pageable)), pendingCount);
            }
        }
        String trimmedKeyword = normalizeKeyword(keyword);
        Specification<Claim> specification = Specification
                .where(AdminClaimSpecifications.type(type))
                .and(AdminClaimSpecifications.status(status))
                .and(AdminClaimSpecifications.refundStatus(refundStatus))
                .and(AdminClaimSpecifications.action(action))
                .and(AdminClaimSpecifications.requestedBetween(from, to))
                .and(AdminClaimSpecifications.keyword(toLikePattern(trimmedKeyword), trimmedKeyword))
                .and(AdminClaimSpecifications.buyerId(buyerId));
        Page<Claim> claimPage = claimRepository.findAll(specification, pageable);

        Enrichment enrichment = enrich(claimPage.getContent());
        List<AdminClaimSummaryResponse> rows = claimPage.getContent().stream()
                .map(claim -> toSummary(claim, enrichment))
                .toList();
        Page<AdminClaimSummaryResponse> rowPage = new PageImpl<>(rows, pageable, claimPage.getTotalElements());
        return AdminClaimListResponse.from(PagedResponse.from(rowPage), pendingCount);
    }

    /** 페이지 내 클레임의 품목·주문 요약·구매자·최신 환불을 배치 조회한다(각 1쿼리·페이지가 비면 0쿼리). */
    private Enrichment enrich(List<Claim> claims) {
        if (claims.isEmpty()) {
            return new Enrichment(Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), Set.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }
        Set<Long> itemIds = claims.stream().map(Claim::getOrderItemId).collect(Collectors.toCollection(LinkedHashSet::new));
        List<Long> claimIds = claims.stream().map(Claim::getId).toList();

        Map<Long, OrderItem> itemById = orderItemRepository.findAllById(itemIds).stream()
                .collect(Collectors.toMap(OrderItem::getId, Function.identity()));
        Map<Long, OrderItemOrderProjection> orderByItemId = orderItemRepository.findOrderSummariesByIdIn(itemIds).stream()
                .collect(Collectors.toMap(OrderItemOrderProjection::getOrderItemId, Function.identity()));
        Set<Long> buyerIds = orderByItemId.values().stream().map(OrderItemOrderProjection::getBuyerId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, User> userById = userRepository.findByIdIn(buyerIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        // id 내림차순 조회이므로 first-wins 병합이 클레임별 최신 환불이 된다
        List<Refund> refunds = refundRepository.findByClaimIdInOrderByIdDesc(claimIds);
        Map<Long, Refund> latestRefundByClaimId = refunds.stream()
                .collect(Collectors.toMap(Refund::getClaimId, Function.identity(), (latest, older) -> latest));
        // Track 104-3a: 재개시 판정은 initiate 멱등 게이트와 같이 클레임의 환불 행 전체에서 "환불된 금액" 행(RefundedCondition) 유무를 본다
        Set<Long> refundedClaimIds = refunds.stream().filter(RefundedCondition::matches).map(Refund::getClaimId)
                .collect(Collectors.toSet());
        Set<Long> pgRefundSucceededClaimIds = refunds.stream().filter(refund -> refund.getPgRefundSucceededAt() != null)
                .map(Refund::getClaimId).collect(Collectors.toSet());
        // Track 104-3a: 품목 기환불액 1쿼리 배치(품목 잔여 상한 판정·환불 행 없는 품목은 0)
        Map<Long, Long> refundedAmountByOrderItemId = refundRepository.sumRefundedByOrderItemIdIn(itemIds).stream()
                .collect(Collectors.toMap(OrderItemRefundedProjection::getOrderItemId, OrderItemRefundedProjection::getRefundedAmount));
        // Track 81-A: 클레임 연결 Delivery(회수 RETURN·재발송/교환 OUTBOUND) 1쿼리 배치 — 방향별 최신 1건
        Map<Long, List<Delivery>> deliveriesByClaimId = deliveryRepository.findByClaimIdInOrderByIdDesc(claimIds).stream()
                .collect(Collectors.groupingBy(Delivery::getClaimId));
        // Track 81-B: 반품 사진 첨부 개수 1쿼리 배치(GROUP BY·목록은 개수만)
        Map<Long, Long> attachmentCountByClaimId = attachmentRepository
                .countByTargetTypeAndTargetIdIn(PolymorphicTargetType.CLAIM, claimIds).stream()
                .collect(Collectors.toMap(AttachmentCountProjection::getTargetId, AttachmentCountProjection::getAttachmentCount));
        // Track 83 D-177: 교환 원/교환 옵션 라벨 1쿼리 배치(EXCHANGE 클레임만·원 옵션은 스냅샷 없으면 현재 품목 variant)
        Set<Long> variantIds = new LinkedHashSet<>();
        for (Claim claim : claims) {
            if (claim.getType() == ClaimType.EXCHANGE) {
                variantIds.add(claim.getExchangeVariantId());
                OrderItem item = itemById.get(claim.getOrderItemId());
                Long originalVariantId = originalVariantIdOf(claim, item);
                if (originalVariantId != null) {
                    variantIds.add(originalVariantId);
                }
            }
        }
        variantIds.remove(null);
        Map<Long, String> optionLabelByVariantId = claimExchangeService.optionLabelsByVariantId(variantIds);
        return new Enrichment(itemById, orderByItemId, userById, latestRefundByClaimId, refundedClaimIds, pgRefundSucceededClaimIds,
                refundedAmountByOrderItemId, deliveriesByClaimId, attachmentCountByClaimId, optionLabelByVariantId);
    }

    /** 교환 원 옵션 라벨: 승인 스냅샷(original_option_label) 우선, 없으면 original variant로 재조립한 라벨(D-177 결정 2 보충). */
    public static String originalOptionLabel(Claim claim, Long originalVariantId, Map<Long, String> optionLabelByVariantId) {
        if (claim.getOriginalOptionLabel() != null) {
            return claim.getOriginalOptionLabel();
        }
        return originalVariantId == null ? null : optionLabelByVariantId.get(originalVariantId);
    }

    /** 교환 원 옵션 variant: 승인 스냅샷(original_variant_id) 우선, 승인 전에는 현재 품목 variant. 비교환·품목 없음은 null. */
    private static Long originalVariantIdOf(Claim claim, OrderItem item) {
        if (claim.getType() != ClaimType.EXCHANGE) {
            return null;
        }
        if (claim.getOriginalVariantId() != null) {
            return claim.getOriginalVariantId();
        }
        return item == null ? null : item.getVariantId();
    }

    private AdminClaimSummaryResponse toSummary(Claim claim, Enrichment enrichment) {
        OrderItem item = enrichment.itemById().get(claim.getOrderItemId());
        OrderItemOrderProjection order = enrichment.orderByItemId().get(claim.getOrderItemId());
        User buyer = order == null ? null : enrichment.userById().get(order.getBuyerId());
        Refund latestRefund = enrichment.latestRefundByClaimId().get(claim.getId());
        Delivery returnDelivery = latestByDirection(enrichment.deliveriesByClaimId().get(claim.getId()), DeliveryDirection.RETURN);
        Delivery reshipment = latestByDirection(enrichment.deliveriesByClaimId().get(claim.getId()), DeliveryDirection.OUTBOUND);
        long itemRemainingRefundable = item == null
                ? 0L
                : item.getTotalPrice() - enrichment.refundedAmountByOrderItemId().getOrDefault(claim.getOrderItemId(), 0L);
        List<String> actions = availableActions(claim, returnDelivery, reshipment,
                enrichment.refundedClaimIds().contains(claim.getId()), itemRemainingRefundable);
        Long originalVariantId = originalVariantIdOf(claim, item);
        return new AdminClaimSummaryResponse(
                claim.getPublicId(),
                claim.getType(),
                claim.getStatus(),
                claim.getRequestedAt(),
                claim.getProcessedAt(),
                order == null ? null : order.getOrderPublicId(),
                item == null ? null : item.getPublicId(),
                order == null ? null : order.getOrderNo(),
                buyer == null ? null : buyer.getName(),
                buyer == null ? null : buyer.getEmail(),
                item == null ? null : item.getProductName(),
                item == null ? null : item.getOptionLabel(),
                item == null ? 0 : item.getQuantity(),
                item == null ? null : item.getTotalPrice(),
                claim.getReasonCode(),
                claim.getReasonDetail(),
                claim.getRejectReasonCode(),
                claim.getRejectMemo(),
                latestRefund == null ? null : latestRefund.getStatus(),
                enrichment.pgRefundSucceededClaimIds().contains(claim.getId()),
                actions,
                returnDelivery == null ? null : ReturnShipmentResponse.from(returnDelivery),
                reshipment == null ? null : ReturnShipmentResponse.from(reshipment),
                claim.getPickedUpAt(),
                claim.getInspectionResult(),
                claim.getRestock(),
                enrichment.attachmentCountByClaimId().getOrDefault(claim.getId(), 0L),
                originalOptionLabel(claim, originalVariantId, enrichment.optionLabelByVariantId()),
                claim.getExchangeVariantId() == null ? null : enrichment.optionLabelByVariantId().get(claim.getExchangeVariantId()));
    }

    /**
     * 단계별 처리 가능 액션(Track 81-A D-170 / Track 83 D-177). REQUESTED는 승인·거부, RETURN·EXCHANGE APPROVED는 회수 송장이 있고 미회수면
     * CONFIRM_PICKUP·회수 확인 후 미검수면 INSPECT. EXCHANGE는 검수 PASS 후 OUTBOUND 미등록이면 REGISTER_EXCHANGE_SHIPMENT·발송 중이면
     * MARK_EXCHANGE_DELIVERED. 그 외(완료·거부·회수 송장 대기)는 빈 목록. INITIATE_REFUND(Track 89-A)는 자동 환불이 붙었어야 할 시점
     * (CANCEL APPROVED / RETURN 검수 PASS) 이후 "환불된 금액" 환불 행이 없고 품목 잔여 상한이 남았을 때만 추가된다({@link #ACTION_INITIATE_REFUND}).
     */
    static List<String> availableActions(Claim claim, Delivery returnDelivery, Delivery outboundDelivery, boolean hasRefundedRefund,
            long itemRemainingRefundable) {
        if (claim.getStatus() == ClaimStatus.REQUESTED) {
            return List.of(ACTION_APPROVE, ACTION_REJECT);
        }
        if (claim.getType().isPickupBased() && claim.getStatus() == ClaimStatus.APPROVED) {
            if (claim.getPickedUpAt() == null) {
                return returnDelivery == null ? List.of() : List.of(ACTION_CONFIRM_PICKUP);
            }
            if (claim.getInspectionResult() == null) {
                return List.of(ACTION_INSPECT);
            }
            if (claim.getType() == ClaimType.EXCHANGE && claim.isInspectionPassed()) {
                if (outboundDelivery == null) {
                    return List.of(ACTION_REGISTER_EXCHANGE_SHIPMENT);
                }
                if (outboundDelivery.getStatus() == DeliveryStatus.SHIPPING) {
                    return List.of(ACTION_MARK_EXCHANGE_DELIVERED);
                }
            }
        }
        if (refundInitiatable(claim, hasRefundedRefund, itemRemainingRefundable)) {
            return List.of(ACTION_INITIATE_REFUND);
        }
        return List.of();
    }

    /**
     * CANCEL APPROVED 또는 RETURN APPROVED+검수 PASS이고, 클레임에 "환불된 금액" 환불 행({@link RefundedCondition} — PENDING·COMPLETED·
     * PG 성공이 기록된 FAILED)이 없고, 품목 잔여 상한(품목 금액 − 품목 기환불액)이 남았을 때(Track 104-3a·initiate 게이트·품목 상한과 같은 조건).
     */
    private static boolean refundInitiatable(Claim claim, boolean hasRefundedRefund, long itemRemainingRefundable) {
        if (claim.getStatus() != ClaimStatus.APPROVED) {
            return false;
        }
        boolean refundDue = claim.getType() == ClaimType.CANCEL
                || (claim.getType() == ClaimType.RETURN && claim.isInspectionPassed());
        return refundDue && !hasRefundedRefund && itemRemainingRefundable > 0;
    }

    private static Delivery latestByDirection(List<Delivery> deliveries, DeliveryDirection direction) {
        if (deliveries == null) {
            return null;
        }
        // id 내림차순 조회이므로 첫 일치가 최신
        return deliveries.stream().filter(delivery -> delivery.getDirection() == direction).findFirst().orElse(null);
    }

    private record Enrichment(
            Map<Long, OrderItem> itemById,
            Map<Long, OrderItemOrderProjection> orderByItemId,
            Map<Long, User> userById,
            Map<Long, Refund> latestRefundByClaimId,
            Set<Long> refundedClaimIds,
            Set<Long> pgRefundSucceededClaimIds,
            Map<Long, Long> refundedAmountByOrderItemId,
            Map<Long, List<Delivery>> deliveriesByClaimId,
            Map<Long, Long> attachmentCountByClaimId,
            Map<Long, String> optionLabelByVariantId) {
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_KEYWORD_LENGTH) {
            throw new MalformedRequestException("keyword는 최대 " + MAX_KEYWORD_LENGTH + "자입니다.");
        }
        return trimmed;
    }

    private String toLikePattern(String trimmedKeyword) {
        if (trimmedKeyword == null) {
            return null;
        }
        String escaped = trimmedKeyword
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private Sort toSort(AdminClaimSort sort) {
        return switch (sort) {
            case OLDEST -> Sort.by(Sort.Order.asc("requestedAt"), Sort.Order.asc("id"));
            case LATEST -> Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.desc("id"));
        };
    }

    private int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }
}
