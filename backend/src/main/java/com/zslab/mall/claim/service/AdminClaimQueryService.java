package com.zslab.mall.claim.service;

import com.zslab.mall.claim.controller.request.AdminClaimSort;
import com.zslab.mall.claim.controller.response.AdminClaimListResponse;
import com.zslab.mall.claim.controller.response.AdminClaimSummaryResponse;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.repository.AdminClaimSpecifications;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.repository.OrderItemOrderProjection;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.refund.entity.Refund;
import com.zslab.mall.refund.repository.RefundRepository;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.repository.UserRepository;
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
 * 배치 조회해 행을 조립한다(N+1 회피·쿼리 수 고정: count·page·품목·품목→주문 요약·구매자·환불·대기건수 = 7).
 */
@Service
@Transactional(readOnly = true)
public class AdminClaimQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_KEYWORD_LENGTH = 50;

    static final String ACTION_APPROVE = "APPROVE";
    static final String ACTION_REJECT = "REJECT";

    private final ClaimRepository claimRepository;
    private final OrderItemRepository orderItemRepository;
    private final UserRepository userRepository;
    private final RefundRepository refundRepository;

    public AdminClaimQueryService(ClaimRepository claimRepository, OrderItemRepository orderItemRepository,
            UserRepository userRepository, RefundRepository refundRepository) {
        this.claimRepository = claimRepository;
        this.orderItemRepository = orderItemRepository;
        this.userRepository = userRepository;
        this.refundRepository = refundRepository;
    }

    /**
     * 관리자 클레임 목록. keyword는 주문번호 정확일치·구매자 이름/이메일·상품명 부분일치. pendingCount는 유형 필터만 반영한다.
     *
     * @throws MalformedRequestException keyword가 trim 후 {@value #MAX_KEYWORD_LENGTH}자를 초과하거나 from &gt; to일 때(400)
     */
    public AdminClaimListResponse listClaims(ClaimType type, ClaimStatus status, String keyword,
            LocalDateTime from, LocalDateTime to, AdminClaimSort sort, int page, int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new MalformedRequestException("from은 to보다 늦을 수 없습니다.");
        }
        String trimmedKeyword = normalizeKeyword(keyword);
        Specification<Claim> specification = Specification
                .where(AdminClaimSpecifications.type(type))
                .and(AdminClaimSpecifications.status(status))
                .and(AdminClaimSpecifications.requestedBetween(from, to))
                .and(AdminClaimSpecifications.keyword(toLikePattern(trimmedKeyword), trimmedKeyword));
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size), toSort(sort));
        Page<Claim> claimPage = claimRepository.findAll(specification, pageable);

        Enrichment enrichment = enrich(claimPage.getContent());
        List<AdminClaimSummaryResponse> rows = claimPage.getContent().stream()
                .map(claim -> toSummary(claim, enrichment))
                .toList();
        Page<AdminClaimSummaryResponse> rowPage = new PageImpl<>(rows, pageable, claimPage.getTotalElements());

        long pendingCount = type == null
                ? claimRepository.countByStatus(ClaimStatus.REQUESTED)
                : claimRepository.countByTypeAndStatus(type, ClaimStatus.REQUESTED);
        return AdminClaimListResponse.from(PagedResponse.from(rowPage), pendingCount);
    }

    /** 페이지 내 클레임의 품목·주문 요약·구매자·최신 환불을 배치 조회한다(각 1쿼리·페이지가 비면 0쿼리). */
    private Enrichment enrich(List<Claim> claims) {
        if (claims.isEmpty()) {
            return new Enrichment(Map.of(), Map.of(), Map.of(), Map.of());
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
        Map<Long, Refund> latestRefundByClaimId = refundRepository.findByClaimIdInOrderByIdDesc(claimIds).stream()
                .collect(Collectors.toMap(Refund::getClaimId, Function.identity(), (latest, older) -> latest));
        return new Enrichment(itemById, orderByItemId, userById, latestRefundByClaimId);
    }

    private AdminClaimSummaryResponse toSummary(Claim claim, Enrichment enrichment) {
        OrderItem item = enrichment.itemById().get(claim.getOrderItemId());
        OrderItemOrderProjection order = enrichment.orderByItemId().get(claim.getOrderItemId());
        User buyer = order == null ? null : enrichment.userById().get(order.getBuyerId());
        Refund latestRefund = enrichment.latestRefundByClaimId().get(claim.getId());
        List<String> actions = claim.getStatus() == ClaimStatus.REQUESTED
                ? List.of(ACTION_APPROVE, ACTION_REJECT)
                : List.of();
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
                actions);
    }

    private record Enrichment(
            Map<Long, OrderItem> itemById,
            Map<Long, OrderItemOrderProjection> orderByItemId,
            Map<Long, User> userById,
            Map<Long, Refund> latestRefundByClaimId) {
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
