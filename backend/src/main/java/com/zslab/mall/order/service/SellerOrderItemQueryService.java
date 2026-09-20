package com.zslab.mall.order.service;

import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.order.controller.response.SellerOrderItemDeliveryResponse;
import com.zslab.mall.order.controller.response.SellerOrderItemDetailResponse;
import com.zslab.mall.order.controller.response.SellerOrderItemSummaryResponse;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.enums.OrderStatus;
import com.zslab.mall.order.exception.OrderNotFoundException;
import com.zslab.mall.order.repository.OrderItemOrderProjection;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.repository.OrderShippingSnapshotProjection;
import com.zslab.mall.order.repository.OrderShippingSnapshotRepository;
import com.zslab.mall.order.repository.SellerOrderItemOrderProjection;
import com.zslab.mall.order.repository.SellerOrderItemSpecifications;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 셀러 품목 조회(Track 90-B-1·read-only). 셀러의 "주문" 단위는 자기 품목 행이다 — 출고(prepare-shipment)·배송·정산·D-187 종료 가드가
 * 전부 품목 축이고 한 주문에 여러 셀러 품목이 섞이므로(로컬 실측 13%) 주문 단위로 잡으면 타 셀러 품목·주문 총액이 함께 실린다.
 * 관리자 {@code AdminOrderQueryService}(Order 루트·주문 전체 기준 조립)는 재사용하지 않고, Specification 페이지 → 주문 축 projection·
 * 원 발송 배송·배송지 스냅샷 배치 enrich로 행을 조립한다(count 1 + page 1 + 배치 4 = 6·N+1 없음).
 *
 * <p>미결제 주문(PENDING_PAYMENT·PAYMENT_EXPIRED)의 품목은 목록·상세 모두 제외한다 — 셀러에게 아직 처리 대상이 아니고, 만료 주문의
 * 품목은 ORDERED로 영구 잔류해 품목 상태만으로는 걸러지지 않는다. 타 셀러 품목·미존재는 모두 404(존재 은닉·셀러 쓰기 API 관례).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SellerOrderItemQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_KEYWORD_LENGTH = 50;
    /** 셀러 화면에서 제외하는 미결제 주문 상태(summarizeSalesBySellerId의 제외 집합과 같은 기준). */
    static final Set<OrderStatus> UNPAID_ORDER_STATUSES = Set.of(OrderStatus.PENDING_PAYMENT, OrderStatus.PAYMENT_EXPIRED);

    private final OrderItemRepository orderItemRepository;
    private final DeliveryRepository deliveryRepository;
    private final OrderShippingSnapshotRepository orderShippingSnapshotRepository;

    /**
     * 셀러 품목 목록. keyword는 상품명 부분일치·주문번호 정확일치. 기간은 결제일(order.paid_at) 기준. 정렬은 결제일 최신순 고정.
     *
     * @throws MalformedRequestException keyword가 trim 후 {@value #MAX_KEYWORD_LENGTH}자를 초과하거나 from &gt; to일 때(400)
     */
    public PagedResponse<SellerOrderItemSummaryResponse> listItems(Long sellerId, OrderItemStatus status, LocalDateTime from,
            LocalDateTime to, String keyword, int page, int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new MalformedRequestException("from은 to보다 늦을 수 없습니다.");
        }
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size),
                Sort.by(Sort.Order.desc("order.paidAt"), Sort.Order.desc("id")));
        String trimmedKeyword = normalizeKeyword(keyword);
        Specification<OrderItem> specification = Specification
                .where(SellerOrderItemSpecifications.sellerId(sellerId))
                .and(SellerOrderItemSpecifications.orderStatusNotIn(UNPAID_ORDER_STATUSES))
                .and(SellerOrderItemSpecifications.itemStatus(status))
                .and(SellerOrderItemSpecifications.paidBetween(from, to))
                .and(SellerOrderItemSpecifications.keyword(toLikePattern(trimmedKeyword), trimmedKeyword));
        Page<OrderItem> itemPage = orderItemRepository.findAll(specification, pageable);
        Enrichment enrichment = enrich(itemPage.getContent());
        List<SellerOrderItemSummaryResponse> rows = itemPage.getContent().stream()
                .map(item -> SellerOrderItemSummaryResponse.of(item, enrichment.orderByItemId().get(item.getId()),
                        enrichment.recipientName(item.getId()),
                        SellerOrderItemDeliveryResponse.from(enrichment.latestDeliveryByItemId().get(item.getId()))))
                .toList();
        return PagedResponse.from(new PageImpl<>(rows, pageable, itemPage.getTotalElements()));
    }

    /**
     * 셀러 품목 상세(배송지 스냅샷 전체 포함).
     *
     * @throws OrderNotFoundException 미존재·타 셀러 품목·미결제 주문 품목(404·존재 은닉)
     */
    public SellerOrderItemDetailResponse getItem(Long sellerId, String orderItemPublicId) {
        OrderItem item = orderItemRepository.findOne(Specification
                        .where(SellerOrderItemSpecifications.sellerId(sellerId))
                        .and(SellerOrderItemSpecifications.orderStatusNotIn(UNPAID_ORDER_STATUSES))
                        .and(SellerOrderItemSpecifications.publicId(orderItemPublicId)))
                .orElseThrow(() -> new OrderNotFoundException("주문 품목을 찾을 수 없습니다: publicId=" + orderItemPublicId));
        Enrichment enrichment = enrich(List.of(item));
        return SellerOrderItemDetailResponse.of(item, enrichment.orderByItemId().get(item.getId()),
                SellerOrderItemDeliveryResponse.from(enrichment.latestDeliveryByItemId().get(item.getId())),
                enrichment.snapshotByOrderId().get(enrichment.orderIdByItemId().get(item.getId())));
    }

    /** 페이지 내 품목의 주문 축 값·주문 id·원 발송 최신 배송·배송지 스냅샷을 배치 조회한다(각 1쿼리·페이지가 비면 0쿼리). */
    private Enrichment enrich(List<OrderItem> items) {
        if (items.isEmpty()) {
            return new Enrichment(Map.of(), Map.of(), Map.of(), Map.of());
        }
        List<Long> itemIds = items.stream().map(OrderItem::getId).toList();
        Map<Long, SellerOrderItemOrderProjection> orderByItemId = orderItemRepository.findSellerOrderSummariesByIdIn(itemIds)
                .stream().collect(Collectors.toMap(SellerOrderItemOrderProjection::getOrderItemId, Function.identity()));
        // OrderItem.order는 getter를 노출하지 않으므로(Aggregate 단방향) 배송지 스냅샷 키(order id)는 기존 projection으로 얻는다.
        Map<Long, Long> orderIdByItemId = orderItemRepository.findOrderSummariesByIdIn(itemIds).stream()
                .collect(Collectors.toMap(OrderItemOrderProjection::getOrderItemId, OrderItemOrderProjection::getOrderId));
        Set<Long> orderIds = Set.copyOf(orderIdByItemId.values());
        Map<Long, OrderShippingSnapshotProjection> snapshotByOrderId = orderIds.isEmpty() ? Map.of()
                : orderShippingSnapshotRepository.findProjectionsByOrderIdIn(orderIds).stream()
                        .collect(Collectors.toMap(OrderShippingSnapshotProjection::getOrderId, Function.identity()));
        // 원 발송(OUTBOUND) 최신 1건만(id DESC 정렬 → 첫 등장 유지). 반품 회수(RETURN)는 배송 목록이 담당한다.
        Map<Long, Delivery> latestDeliveryByItemId = deliveryRepository
                .findByOrderItemIdInAndDirectionOrderByIdDesc(itemIds, DeliveryDirection.OUTBOUND).stream()
                .collect(Collectors.toMap(Delivery::getOrderItemId, Function.identity(), (latest, older) -> latest));
        return new Enrichment(orderByItemId, orderIdByItemId, snapshotByOrderId, latestDeliveryByItemId);
    }

    private record Enrichment(
            Map<Long, SellerOrderItemOrderProjection> orderByItemId,
            Map<Long, Long> orderIdByItemId,
            Map<Long, OrderShippingSnapshotProjection> snapshotByOrderId,
            Map<Long, Delivery> latestDeliveryByItemId) {

        String recipientName(Long itemId) {
            OrderShippingSnapshotProjection snapshot = snapshotByOrderId.get(orderIdByItemId.get(itemId));
            return snapshot == null ? null : snapshot.getRecipientName();
        }
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
