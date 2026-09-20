package com.zslab.mall.claim.service;

import com.zslab.mall.attachment.entity.Attachment;
import com.zslab.mall.attachment.repository.AttachmentCountProjection;
import com.zslab.mall.attachment.repository.AttachmentRepository;
import com.zslab.mall.claim.controller.response.SellerClaimDetailResponse;
import com.zslab.mall.claim.controller.response.SellerClaimSummaryResponse;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.claim.repository.AdminClaimSpecifications;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.claim.repository.SellerClaimSpecifications;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.repository.SellerOrderItemOrderProjection;
import com.zslab.mall.refund.entity.Refund;
import com.zslab.mall.refund.enums.RefundStatus;
import com.zslab.mall.refund.repository.RefundRepository;
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
 * 셀러 클레임 조회(Track 90-D-1·{@link AdminClaimQueryService} 복제·셀러 범위 한정·조회 전용). 관리자 서비스에 sellerId 인자를 넣어
 * 공용화하지 않고 셀러용을 따로 둔다(관리자 무수정 원칙). 유형·상태·기간 Specification은 관리자 것을 재사용하고
 * {@link SellerClaimSpecifications#ownedBySeller}(seller ownership predicate)를 목록·상세 모든 Specification에 반드시 AND로 결합한다(순서가
 * 아니라 AND 결합이 범위 보장이다). 정렬은 요청일 최신순 고정(sort 파라미터 없음·셀러 품목 목록 관례).
 * 쿼리 수 = count 1 + page 1 + 배치 4(품목·주문 축·최신 환불·첨부 개수) = 6·N+1 없음.
 */
@Service
@Transactional(readOnly = true)
public class SellerClaimQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_KEYWORD_LENGTH = 50;

    private final ClaimRepository claimRepository;
    private final OrderItemRepository orderItemRepository;
    private final RefundRepository refundRepository;
    private final AttachmentRepository attachmentRepository;
    private final DeliveryRepository deliveryRepository;

    public SellerClaimQueryService(ClaimRepository claimRepository, OrderItemRepository orderItemRepository,
            RefundRepository refundRepository, AttachmentRepository attachmentRepository, DeliveryRepository deliveryRepository) {
        this.claimRepository = claimRepository;
        this.orderItemRepository = orderItemRepository;
        this.refundRepository = refundRepository;
        this.attachmentRepository = attachmentRepository;
        this.deliveryRepository = deliveryRepository;
    }

    /**
     * 셀러 클레임 목록. keyword는 주문번호 정확일치·상품명 부분일치. 기간은 요청일(requested_at) 기준.
     *
     * @throws MalformedRequestException keyword가 trim 후 {@value #MAX_KEYWORD_LENGTH}자를 초과하거나 from &gt; to일 때(400)
     */
    public PagedResponse<SellerClaimSummaryResponse> listClaims(Long sellerId, ClaimType type, ClaimStatus status, String keyword,
            LocalDateTime from, LocalDateTime to, int page, int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new MalformedRequestException("from은 to보다 늦을 수 없습니다.");
        }
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size),
                Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.desc("id")));
        String trimmedKeyword = normalizeKeyword(keyword);
        Specification<Claim> specification = Specification
                .where(SellerClaimSpecifications.ownedBySeller(sellerId))
                .and(AdminClaimSpecifications.type(type))
                .and(AdminClaimSpecifications.status(status))
                .and(AdminClaimSpecifications.requestedBetween(from, to))
                .and(SellerClaimSpecifications.keyword(toLikePattern(trimmedKeyword), trimmedKeyword));
        Page<Claim> claimPage = claimRepository.findAll(specification, pageable);
        Enrichment enrichment = enrich(claimPage.getContent());
        List<SellerClaimSummaryResponse> rows = claimPage.getContent().stream()
                .map(claim -> SellerClaimSummaryResponse.of(claim,
                        enrichment.itemById().get(claim.getOrderItemId()),
                        enrichment.orderByItemId().get(claim.getOrderItemId()),
                        enrichment.latestRefundStatusByClaimId().get(claim.getId()),
                        enrichment.attachmentCountByClaimId().getOrDefault(claim.getId(), 0L)))
                .toList();
        return PagedResponse.from(new PageImpl<>(rows, pageable, claimPage.getTotalElements()));
    }

    /**
     * 셀러 클레임 상세(첨부 목록·교환품 배송 상태 포함).
     *
     * @throws ClaimNotFoundException 미존재·타 셀러 품목의 클레임(404·존재 은닉)
     */
    public SellerClaimDetailResponse getClaim(Long sellerId, String claimPublicId) {
        Claim claim = claimRepository.findOne(Specification
                        .where(SellerClaimSpecifications.ownedBySeller(sellerId))
                        .and(SellerClaimSpecifications.publicId(claimPublicId)))
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: publicId=" + claimPublicId));
        Enrichment enrichment = enrich(List.of(claim));
        List<Attachment> attachments = attachmentRepository
                .findByTargetTypeAndTargetIdOrderByDisplayOrderAsc(PolymorphicTargetType.CLAIM, claim.getId());
        return SellerClaimDetailResponse.of(claim,
                enrichment.itemById().get(claim.getOrderItemId()),
                enrichment.orderByItemId().get(claim.getOrderItemId()),
                enrichment.latestRefundStatusByClaimId().get(claim.getId()),
                attachments,
                exchangeDeliveryStatus(claim));
    }

    /** 교환품 발송(OUTBOUND·claim_id) 최신 배송의 상태. EXCHANGE가 아니거나 미발송이면 null(조회 0회). */
    private DeliveryStatus exchangeDeliveryStatus(Claim claim) {
        if (claim.getType() != ClaimType.EXCHANGE) {
            return null;
        }
        // id 내림차순 조회이므로 첫 OUTBOUND가 최신
        return deliveryRepository.findByClaimIdInOrderByIdDesc(List.of(claim.getId())).stream()
                .filter(delivery -> delivery.getDirection() == DeliveryDirection.OUTBOUND)
                .findFirst()
                .map(Delivery::getStatus)
                .orElse(null);
    }

    /** 페이지 내 클레임의 품목·주문 축·최신 환불 상태·첨부 개수를 배치 조회한다(각 1쿼리·페이지가 비면 0쿼리). */
    private Enrichment enrich(List<Claim> claims) {
        if (claims.isEmpty()) {
            return new Enrichment(Map.of(), Map.of(), Map.of(), Map.of());
        }
        Set<Long> itemIds = claims.stream().map(Claim::getOrderItemId).collect(Collectors.toCollection(LinkedHashSet::new));
        List<Long> claimIds = claims.stream().map(Claim::getId).toList();
        Map<Long, OrderItem> itemById = orderItemRepository.findAllById(itemIds).stream()
                .collect(Collectors.toMap(OrderItem::getId, Function.identity()));
        // 주문 축은 번호·시각만 담는 셀러 projection(구매자 id·총액 없음)
        Map<Long, SellerOrderItemOrderProjection> orderByItemId = orderItemRepository.findSellerOrderSummariesByIdIn(itemIds)
                .stream().collect(Collectors.toMap(SellerOrderItemOrderProjection::getOrderItemId, Function.identity()));
        // id 내림차순 조회이므로 first-wins 병합이 클레임별 최신 환불이 된다(상태만·금액은 응답에 싣지 않는다)
        Map<Long, RefundStatus> latestRefundStatusByClaimId = refundRepository.findByClaimIdInOrderByIdDesc(claimIds).stream()
                .collect(Collectors.toMap(Refund::getClaimId, Refund::getStatus, (latest, older) -> latest));
        Map<Long, Long> attachmentCountByClaimId = attachmentRepository
                .countByTargetTypeAndTargetIdIn(PolymorphicTargetType.CLAIM, claimIds).stream()
                .collect(Collectors.toMap(AttachmentCountProjection::getTargetId, AttachmentCountProjection::getAttachmentCount));
        return new Enrichment(itemById, orderByItemId, latestRefundStatusByClaimId, attachmentCountByClaimId);
    }

    private record Enrichment(
            Map<Long, OrderItem> itemById,
            Map<Long, SellerOrderItemOrderProjection> orderByItemId,
            Map<Long, RefundStatus> latestRefundStatusByClaimId,
            Map<Long, Long> attachmentCountByClaimId) {
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

    private int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }
}
