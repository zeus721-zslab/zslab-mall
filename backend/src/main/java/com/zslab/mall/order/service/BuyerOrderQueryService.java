package com.zslab.mall.order.service;

import com.zslab.mall.order.controller.response.OrderResponse;
import com.zslab.mall.order.controller.response.OrderSummaryResponse;
import com.zslab.mall.order.controller.response.OrderSummaryResponse.ActiveClaimCount;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderStatus;
import com.zslab.mall.order.exception.OrderNotFoundException;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductVariant;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.product.repository.ProductVariantRepository;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.repository.SellerRepository;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Buyer 주문 조회 서비스(D-40.3·확인3). GET 단건·목록의 read + enrich(내부 BIGINT → public_id·companyName·productName)를 담당한다.
 * 컨트롤러의 Repository 직접 접근(D-43.11)을 피하는 읽기 전용 계층이며, 쓰기 오케스트레이션은 CheckoutService(D-58)가 담당한다.
 */
@Service
@Transactional(readOnly = true)
public class BuyerOrderQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final SellerRepository sellerRepository;
    private final ClaimRepository claimRepository;
    private final DeliveryRepository deliveryRepository;

    public BuyerOrderQueryService(
            OrderRepository orderRepository,
            ProductRepository productRepository,
            ProductVariantRepository productVariantRepository,
            SellerRepository sellerRepository,
            ClaimRepository claimRepository,
            DeliveryRepository deliveryRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.productVariantRepository = productVariantRepository;
        this.sellerRepository = sellerRepository;
        this.claimRepository = claimRepository;
        this.deliveryRepository = deliveryRepository;
    }

    /** 본인 주문 단건(§11 seller 그룹화 + #6 배송지). 미존재·타인 주문 모두 404(정보 노출 회피·§2). */
    public OrderResponse getOrder(String orderPublicId, Long buyerId) {
        Order order = orderRepository.findByPublicIdWithItems(orderPublicId)
                .orElseThrow(() -> new OrderNotFoundException("주문을 찾을 수 없습니다: " + orderPublicId));
        if (!order.getBuyerId().equals(buyerId)) {
            throw new OrderNotFoundException("주문을 찾을 수 없습니다: " + orderPublicId);
        }
        List<OrderItem> items = order.getItems();
        return OrderResponse.fromOrderWithItems(
                order, productsByIdFor(items), variantsByIdFor(items), sellersByIdFor(items), exchangeCompletedItemIdsFor(items),
                originalDeliveryByItemIdFor(items));
    }

    /**
     * 품목 id별 원 발송 Delivery(Track 96-2 D-203·C-05). 주문 단위 1회 배치 조회(셀러 품목 조회와 같은
     * {@code findByOrderItemIdInAndDirectionOrderByIdDesc} 재사용·품목별 개별 쿼리 없음). OUTBOUND 중 클레임 미연결(claim_id NULL)만
     * 원 발송으로 보고 id DESC 정렬의 첫 등장(최신)을 유지한다 — 교환품·재발송은 클레임 상세가 담당한다. 품목이 없으면 조회 없이 빈 맵.
     */
    private Map<Long, Delivery> originalDeliveryByItemIdFor(List<OrderItem> items) {
        if (items.isEmpty()) {
            return Map.of();
        }
        List<Long> itemIds = items.stream().map(OrderItem::getId).toList();
        return deliveryRepository.findByOrderItemIdInAndDirectionOrderByIdDesc(itemIds, DeliveryDirection.OUTBOUND).stream()
                .filter(delivery -> delivery.getClaimId() == null)
                .collect(Collectors.toMap(Delivery::getOrderItemId, Function.identity(), (latest, older) -> latest));
    }

    /**
     * 완료된 교환(EXCHANGE·COMPLETED)이 있는 품목 id 집합(Track 83 D-177 보충·FE-30-4). 주문 단위 1회 배치 조회(관리자 enrich와 같은
     * {@code findByOrderItemIdInOrderByIdDesc} 재사용·품목별 개별 쿼리 없음). 품목이 없으면 조회 없이 빈 집합.
     */
    private Set<Long> exchangeCompletedItemIdsFor(List<OrderItem> items) {
        if (items.isEmpty()) {
            return Set.of();
        }
        List<Long> itemIds = items.stream().map(OrderItem::getId).toList();
        return claimRepository.findByOrderItemIdInOrderByIdDesc(itemIds).stream()
                .filter(claim -> claim.getType() == ClaimType.EXCHANGE && claim.getStatus() == ClaimStatus.COMPLETED)
                .map(Claim::getOrderItemId)
                .collect(Collectors.toSet());
    }

    /**
     * 본인 주문 목록(ordered_at DESC·D-42·D-54). 페이지는 정렬 미노출(서버 고정)·size는 1~100 클램프.
     * 미결제 종료(PAYMENT_EXPIRED) 주문은 목록에서 제외한다(FE-12c·비노출·DB 레벨 제외로 페이지 정합 유지).
     */
    public PagedResponse<OrderSummaryResponse> listOrders(Long buyerId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size));
        Page<Order> orders = orderRepository.findByBuyerIdAndStatusNotOrderByOrderedAtDesc(
                buyerId, OrderStatus.PAYMENT_EXPIRED, pageable);

        List<Long> orderIds = orders.getContent().stream().map(Order::getId).toList();
        Map<Long, Order> ordersWithItems = orderIds.isEmpty()
                ? Map.of()
                : orderRepository.findByIdInWithItems(orderIds).stream()
                        .collect(Collectors.toMap(Order::getId, Function.identity()));

        Map<Long, List<ActiveClaimCount>> activeClaimsByOrderId = activeClaimCountsByOrderId(ordersWithItems.values());

        // 페이지 순서(ordered_at DESC) 유지하며 items 로딩본으로 요약 생성(상품명은 order_item 스냅샷·Track 76).
        List<OrderSummaryResponse> summaries = orders.getContent().stream()
                .map(order -> OrderSummaryResponse.from(
                        ordersWithItems.getOrDefault(order.getId(), order),
                        activeClaimsByOrderId.getOrDefault(order.getId(), List.of())))
                .toList();
        Page<OrderSummaryResponse> summaryPage = new PageImpl<>(summaries, pageable, orders.getTotalElements());
        return PagedResponse.from(summaryPage);
    }

    /**
     * 페이지 주문들의 진행 중 클레임 유형별 건수를 주문 id별로 접는다(Track 101-B 카드 배지). 품목 전체를 한 번에 조회하는
     * {@code findActiveByOrderItemIdIn} 1쿼리이며(품목별 개별 쿼리 없음), 품목이 없으면 조회 없이 빈 맵이다.
     * 활성 판정은 DB 한 곳에서만 한다 — 외부 검토 반영으로 상태 조건을 쿼리로 내려, 종결 클레임은 애초에 적재되지 않는다
     * (애플리케이션 쪽 재검사를 겹쳐 두면 거르는 지점이 둘이 된다).
     *
     * <p>클레임 행에는 order_id가 없고 {@code OrderItem}도 소속 Order getter를 노출하지 않으므로(Aggregate 단방향),
     * fetch join으로 이미 로딩된 items에서 품목 id → 주문 id 역인덱스를 만들어 되접는다. 유형 순서는 {@link ClaimType}
     * 선언 순서로 고정해 같은 주문이 매번 같은 배지 순서를 갖게 한다.
     */
    private Map<Long, List<ActiveClaimCount>> activeClaimCountsByOrderId(Collection<Order> ordersWithItems) {
        Map<Long, Long> orderIdByItemId = new HashMap<>();
        for (Order order : ordersWithItems) {
            for (OrderItem item : order.getItems()) {
                orderIdByItemId.put(item.getId(), order.getId());
            }
        }
        if (orderIdByItemId.isEmpty()) {
            return Map.of();
        }
        Map<Long, EnumMap<ClaimType, Integer>> countsByOrderId = new HashMap<>();
        for (Claim claim : claimRepository.findActiveByOrderItemIdIn(orderIdByItemId.keySet())) {
            Long orderId = orderIdByItemId.get(claim.getOrderItemId());
            countsByOrderId.computeIfAbsent(orderId, key -> new EnumMap<>(ClaimType.class))
                    .merge(claim.getType(), 1, Integer::sum);
        }
        Map<Long, List<ActiveClaimCount>> result = new HashMap<>();
        countsByOrderId.forEach((orderId, counts) -> result.put(orderId, counts.entrySet().stream()
                .map(entry -> new ActiveClaimCount(entry.getKey(), entry.getValue()))
                .toList()));
        return result;
    }

    private int clampSize(int size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private Map<Long, Product> productsByIdFor(List<OrderItem> items) {
        List<Long> ids = items.stream().map(OrderItem::getProductId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return productRepository.findByIdIn(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
    }

    private Map<Long, ProductVariant> variantsByIdFor(List<OrderItem> items) {
        List<Long> ids = items.stream().map(OrderItem::getVariantId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return productVariantRepository.findByIdIn(ids).stream()
                .collect(Collectors.toMap(ProductVariant::getId, Function.identity()));
    }

    private Map<Long, Seller> sellersByIdFor(List<OrderItem> items) {
        List<Long> ids = items.stream().map(OrderItem::getSellerId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return sellerRepository.findByIdIn(ids).stream()
                .collect(Collectors.toMap(Seller::getId, Function.identity()));
    }
}
