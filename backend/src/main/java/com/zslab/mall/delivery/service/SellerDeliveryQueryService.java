package com.zslab.mall.delivery.service;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.delivery.controller.request.AdminDeliveryScope;
import com.zslab.mall.delivery.controller.request.AdminDeliverySort;
import com.zslab.mall.delivery.controller.response.SellerDeliverySummaryResponse;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.delivery.repository.AdminDeliverySpecifications;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.delivery.repository.SellerDeliverySpecifications;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.repository.OrderItemOrderProjection;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.repository.OrderShippingSnapshotProjection;
import com.zslab.mall.order.repository.OrderShippingSnapshotRepository;
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
 * 셀러 배송 조회(Track 90-B-1·{@link AdminDeliveryQueryService} 복제·셀러 범위 한정). 관리자 서비스에 sellerId 인자를 넣어 공용화하지 않고
 * 셀러용을 따로 둔다(관리자 무수정 원칙). 필터 Specification·scope·sort enum은 데이터 정의라 관리자 것을 그대로 쓰고,
 * {@link SellerDeliverySpecifications#ownedBySeller}로 범위만 제한한다. 쿼리 수는 관리자와 같다(count·page·품목·주문 요약·배송지·클레임 = 6).
 */
@Service
@Transactional(readOnly = true)
public class SellerDeliveryQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_KEYWORD_LENGTH = 50;

    private final DeliveryRepository deliveryRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderShippingSnapshotRepository orderShippingSnapshotRepository;
    private final ClaimRepository claimRepository;

    public SellerDeliveryQueryService(DeliveryRepository deliveryRepository, OrderItemRepository orderItemRepository,
            OrderShippingSnapshotRepository orderShippingSnapshotRepository, ClaimRepository claimRepository) {
        this.deliveryRepository = deliveryRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderShippingSnapshotRepository = orderShippingSnapshotRepository;
        this.claimRepository = claimRepository;
    }

    /**
     * 셀러 배송 목록. keyword는 송장번호 정확일치·주문번호 정확일치·수령인명 부분일치. 기간은 발송일(shipped_at) 기준.
     *
     * @throws MalformedRequestException keyword가 trim 후 {@value #MAX_KEYWORD_LENGTH}자를 초과하거나 from &gt; to일 때(400)
     */
    public PagedResponse<SellerDeliverySummaryResponse> listDeliveries(Long sellerId, AdminDeliveryScope scope,
            DeliveryStatus status, DeliveryCarrier carrier, String keyword, LocalDateTime from, LocalDateTime to,
            AdminDeliverySort sort, int page, int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new MalformedRequestException("from은 to보다 늦을 수 없습니다.");
        }
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size), toSort(sort));
        String trimmedKeyword = normalizeKeyword(keyword);
        Specification<Delivery> specification = Specification
                .where(SellerDeliverySpecifications.ownedBySeller(sellerId))
                .and(AdminDeliverySpecifications.scope(scope))
                .and(AdminDeliverySpecifications.status(status))
                .and(AdminDeliverySpecifications.carrier(carrier))
                .and(AdminDeliverySpecifications.shippedBetween(from, to))
                .and(AdminDeliverySpecifications.keyword(toLikePattern(trimmedKeyword), trimmedKeyword));
        Page<Delivery> deliveryPage = deliveryRepository.findAll(specification, pageable);
        Enrichment enrichment = enrich(deliveryPage.getContent());
        List<SellerDeliverySummaryResponse> rows = deliveryPage.getContent().stream()
                .map(delivery -> {
                    OrderItemOrderProjection order = enrichment.orderByItemId().get(delivery.getOrderItemId());
                    OrderShippingSnapshotProjection snapshot = order == null ? null
                            : enrichment.snapshotByOrderId().get(order.getOrderId());
                    return SellerDeliverySummaryResponse.from(delivery,
                            enrichment.itemById().get(delivery.getOrderItemId()), order,
                            snapshot == null ? null : snapshot.getRecipientName(),
                            delivery.getClaimId() == null ? null : enrichment.claimById().get(delivery.getClaimId()));
                })
                .toList();
        Page<SellerDeliverySummaryResponse> rowPage = new PageImpl<>(rows, pageable, deliveryPage.getTotalElements());
        return PagedResponse.from(rowPage);
    }

    /** 페이지 내 배송의 품목·주문 요약·배송지 스냅샷·클레임을 배치 조회한다(각 1쿼리·페이지가 비면 0쿼리). */
    private Enrichment enrich(List<Delivery> deliveries) {
        if (deliveries.isEmpty()) {
            return new Enrichment(Map.of(), Map.of(), Map.of(), Map.of());
        }
        Set<Long> itemIds = deliveries.stream().map(Delivery::getOrderItemId).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, OrderItem> itemById = orderItemRepository.findAllById(itemIds).stream()
                .collect(Collectors.toMap(OrderItem::getId, Function.identity()));
        Map<Long, OrderItemOrderProjection> orderByItemId = orderItemRepository.findOrderSummariesByIdIn(itemIds).stream()
                .collect(Collectors.toMap(OrderItemOrderProjection::getOrderItemId, Function.identity()));
        Set<Long> orderIds = orderByItemId.values().stream().map(OrderItemOrderProjection::getOrderId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, OrderShippingSnapshotProjection> snapshotByOrderId = orderIds.isEmpty() ? Map.of()
                : orderShippingSnapshotRepository.findProjectionsByOrderIdIn(orderIds).stream()
                        .collect(Collectors.toMap(OrderShippingSnapshotProjection::getOrderId, Function.identity()));
        Set<Long> claimIds = deliveries.stream().map(Delivery::getClaimId).filter(claimId -> claimId != null)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, Claim> claimById = claimIds.isEmpty() ? Map.of()
                : claimRepository.findAllById(claimIds).stream().collect(Collectors.toMap(Claim::getId, Function.identity()));
        return new Enrichment(itemById, orderByItemId, snapshotByOrderId, claimById);
    }

    private record Enrichment(
            Map<Long, OrderItem> itemById,
            Map<Long, OrderItemOrderProjection> orderByItemId,
            Map<Long, OrderShippingSnapshotProjection> snapshotByOrderId,
            Map<Long, Claim> claimById) {
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

    /** 발송일 기준 정렬(id 보조 키·관리자와 동일). shipped_at 인덱스 없음(D-184 §8 이월·셀러 범위 행 수는 더 작다). */
    private Sort toSort(AdminDeliverySort sort) {
        return switch (sort) {
            case OLDEST -> Sort.by(Sort.Order.asc("shippedAt"), Sort.Order.asc("id"));
            case LATEST -> Sort.by(Sort.Order.desc("shippedAt"), Sort.Order.desc("id"));
        };
    }

    private int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }
}
