package com.zslab.mall.order.service;

import com.zslab.mall.order.controller.response.OrderResponse;
import com.zslab.mall.order.controller.response.OrderSummaryResponse;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.exception.OrderNotFoundException;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductVariant;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.product.repository.ProductVariantRepository;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.repository.SellerRepository;
import java.util.List;
import java.util.Map;
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

    public BuyerOrderQueryService(
            OrderRepository orderRepository,
            ProductRepository productRepository,
            ProductVariantRepository productVariantRepository,
            SellerRepository sellerRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.productVariantRepository = productVariantRepository;
        this.sellerRepository = sellerRepository;
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
                order, productsByIdFor(items), variantsByIdFor(items), sellersByIdFor(items));
    }

    /** 본인 주문 목록(ordered_at DESC·D-42·D-54). 페이지는 정렬 미노출(서버 고정)·size는 1~100 클램프. */
    public PagedResponse<OrderSummaryResponse> listOrders(Long buyerId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size));
        Page<Order> orders = orderRepository.findByBuyerIdOrderByOrderedAtDesc(buyerId, pageable);

        List<Long> orderIds = orders.getContent().stream().map(Order::getId).toList();
        Map<Long, Order> ordersWithItems = orderIds.isEmpty()
                ? Map.of()
                : orderRepository.findByIdInWithItems(orderIds).stream()
                        .collect(Collectors.toMap(Order::getId, Function.identity()));
        List<OrderItem> allItems = ordersWithItems.values().stream()
                .flatMap(order -> order.getItems().stream()).toList();
        Map<Long, Product> productById = productsByIdFor(allItems);

        // 페이지 순서(ordered_at DESC) 유지하며 items 로딩본으로 요약 생성.
        List<OrderSummaryResponse> summaries = orders.getContent().stream()
                .map(order -> OrderSummaryResponse.from(ordersWithItems.getOrDefault(order.getId(), order), productById))
                .toList();
        Page<OrderSummaryResponse> summaryPage = new PageImpl<>(summaries, pageable, orders.getTotalElements());
        return PagedResponse.from(summaryPage);
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
