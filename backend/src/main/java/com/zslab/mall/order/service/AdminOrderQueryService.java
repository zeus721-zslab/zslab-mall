package com.zslab.mall.order.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.audit.entity.AuditLog;
import com.zslab.mall.audit.repository.AuditLogRepository;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.order.controller.request.AdminOrderSort;
import com.zslab.mall.order.controller.response.AdminOrderDetailResponse;
import com.zslab.mall.order.controller.response.AdminOrderSummaryResponse;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.order.controller.response.ShippingAddressResponse;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.enums.OrderStatus;
import com.zslab.mall.order.exception.OrderNotFoundException;
import com.zslab.mall.order.repository.AdminOrderSpecifications;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.payment.entity.Payment;
import com.zslab.mall.payment.enums.PaymentStatus;
import com.zslab.mall.payment.repository.PaymentRepository;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.repository.SellerRepository;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 주문 조회(Track 79 D-168·{@code BuyerOrderQueryService}·{@code AdminProductQueryService} 패턴). 목록은 Specification 페이지
 * → 주문 id로 items fetch join → payment·delivery·claim·user·seller를 {@code …In} 배치 조회로 enrich해 페이지 크기와 무관하게
 * 쿼리 수를 고정한다(count 1 + page 1 + items 1 + 배치 5 = 8·N+1 없음). 상세는 단건 fetch join + 배치 4 + audit 1.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdminOrderQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_KEYWORD_LENGTH = 50;

    static final String ACTION_CANCEL = "CANCEL";
    static final String ACTION_PREPARE_SHIPMENT = "PREPARE_SHIPMENT";
    static final String ACTION_MARK_DELIVERED = "MARK_DELIVERED";

    private static final Set<OrderStatus> TERMINAL_STATUSES =
            Set.of(OrderStatus.CANCELLED, OrderStatus.PAYMENT_EXPIRED);

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final DeliveryRepository deliveryRepository;
    private final ClaimRepository claimRepository;
    private final UserRepository userRepository;
    private final SellerRepository sellerRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    /**
     * 관리자 주문 목록. keyword는 주문번호 정확일치·주문자 이름/이메일·상품명 부분일치.
     *
     * @throws MalformedRequestException keyword가 trim 후 {@value #MAX_KEYWORD_LENGTH}자를 초과하거나 from &gt; to일 때(400)
     */
    public PagedResponse<AdminOrderSummaryResponse> listOrders(
            String keyword, OrderStatus status, PaymentStatus paymentStatus, DeliveryStatus deliveryStatus,
            LocalDateTime from, LocalDateTime to, AdminOrderSort sort, int page, int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new MalformedRequestException("from은 to보다 늦을 수 없습니다.");
        }
        String trimmedKeyword = normalizeKeyword(keyword);
        Specification<Order> specification = Specification
                .where(AdminOrderSpecifications.fetchShippingSnapshot())
                .and(AdminOrderSpecifications.keyword(toLikePattern(trimmedKeyword), trimmedKeyword))
                .and(AdminOrderSpecifications.status(status))
                .and(AdminOrderSpecifications.paymentStatus(paymentStatus))
                .and(AdminOrderSpecifications.deliveryStatus(deliveryStatus))
                .and(AdminOrderSpecifications.orderedBetween(from, to));
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size), toSort(sort));
        Page<Order> orderPage = orderRepository.findAll(specification, pageable);

        List<Long> orderIds = orderPage.getContent().stream().map(Order::getId).toList();
        List<Order> orders = orderIds.isEmpty() ? List.of() : orderRepository.findByIdInWithItems(orderIds);
        Map<Long, Order> orderById = orders.stream().collect(Collectors.toMap(Order::getId, Function.identity()));
        Enrichment enrichment = enrich(orders);

        List<AdminOrderSummaryResponse> rows = orderIds.stream()
                .map(orderById::get)
                .map(order -> toSummary(order, enrichment))
                .toList();
        Page<AdminOrderSummaryResponse> rowPage = new PageImpl<>(rows, pageable, orderPage.getTotalElements());
        return PagedResponse.from(rowPage);
    }

    /**
     * 관리자 주문 상세.
     *
     * @throws OrderNotFoundException 주문 미존재(404)
     */
    public AdminOrderDetailResponse getOrder(String orderPublicId) {
        Order order = orderRepository.findByPublicIdWithItems(orderPublicId)
                .orElseThrow(() -> new OrderNotFoundException("주문을 찾을 수 없습니다: " + orderPublicId));
        Enrichment enrichment = enrich(List.of(order));
        User buyer = enrichment.userById.get(order.getBuyerId());

        List<AdminOrderDetailResponse.Item> items = order.getItems().stream()
                .map(item -> new AdminOrderDetailResponse.Item(
                        item.getPublicId(), item.getProductName(), item.getOptionLabel(), item.getQuantity(),
                        item.getUnitPrice(), item.getTotalPrice(), item.getItemStatus().name(),
                        sellerName(enrichment.sellerById.get(item.getSellerId())),
                        toDeliveryRow(enrichment.latestDeliveryByItemId.get(item.getId())),
                        enrichment.claimsByItemId.getOrDefault(item.getId(), List.of()).stream()
                                .map(this::toClaimRow).toList()))
                .toList();
        List<AdminOrderDetailResponse.PaymentRow> payments = enrichment.paymentsByOrderId
                .getOrDefault(order.getId(), List.of()).stream()
                .map(payment -> new AdminOrderDetailResponse.PaymentRow(
                        payment.getPublicId(), payment.getMethod().name(), payment.getStatus().name(),
                        payment.getAmount(), payment.getPgProvider(), payment.getPaidAt(), payment.getCreatedAt()))
                .toList();

        return new AdminOrderDetailResponse(
                order.getPublicId(), order.getOrderNo(), order.getOrderedAt(), order.getPaidAt(), order.getStatus().name(),
                buyer == null ? null : new AdminOrderDetailResponse.Buyer(buyer.getPublicId(), buyer.getName(), buyer.getEmail()),
                order.getShippingSnapshot() == null ? null : ShippingAddressResponse.from(order.getShippingSnapshot()),
                order.getTotalPrice(), order.getDiscountAmount(), order.getShippingFee(), paymentAmount(order),
                payments, items, cancelReasons(order.getId()), actions(order, enrichment));
    }

    // ---------- 배치 enrich ----------

    /** 주문 목록에 필요한 연관 데이터를 5회 배치 조회로 모은다(페이지 크기 무관). */
    private Enrichment enrich(List<Order> orders) {
        if (orders.isEmpty()) {
            return Enrichment.EMPTY;
        }
        List<Long> orderIds = orders.stream().map(Order::getId).toList();
        List<OrderItem> items = orders.stream().flatMap(order -> order.getItems().stream()).toList();
        List<Long> itemIds = items.stream().map(OrderItem::getId).toList();
        Set<Long> buyerIds = orders.stream().map(Order::getBuyerId).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> sellerIds = items.stream().map(OrderItem::getSellerId).collect(Collectors.toCollection(LinkedHashSet::new));

        Map<Long, List<Payment>> paymentsByOrderId = paymentRepository.findByOrderIdInOrderByIdDesc(orderIds).stream()
                .collect(Collectors.groupingBy(Payment::getOrderId));
        Map<Long, Delivery> latestDeliveryByItemId = deliveryRepository.findByOrderItemIdInOrderByIdDesc(itemIds).stream()
                .collect(Collectors.toMap(Delivery::getOrderItemId, Function.identity(), (latest, older) -> latest));
        Map<Long, List<Claim>> claimsByItemId = claimRepository.findByOrderItemIdInOrderByIdDesc(itemIds).stream()
                .collect(Collectors.groupingBy(Claim::getOrderItemId));
        Map<Long, User> userById = userRepository.findByIdIn(buyerIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<Long, Seller> sellerById = sellerRepository.findByIdIn(sellerIds).stream()
                .collect(Collectors.toMap(Seller::getId, Function.identity()));
        return new Enrichment(paymentsByOrderId, latestDeliveryByItemId, claimsByItemId, userById, sellerById);
    }

    private record Enrichment(
            Map<Long, List<Payment>> paymentsByOrderId,
            Map<Long, Delivery> latestDeliveryByItemId,
            Map<Long, List<Claim>> claimsByItemId,
            Map<Long, User> userById,
            Map<Long, Seller> sellerById) {
        static final Enrichment EMPTY = new Enrichment(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    // ---------- 조립 ----------

    private AdminOrderSummaryResponse toSummary(Order order, Enrichment enrichment) {
        List<OrderItem> items = order.getItems();
        User buyer = enrichment.userById.get(order.getBuyerId());
        List<String> sellerNames = items.stream()
                .map(OrderItem::getSellerId).distinct()
                .map(sellerId -> sellerName(enrichment.sellerById.get(sellerId)))
                .toList();
        Payment representative = representativePayment(enrichment.paymentsByOrderId.get(order.getId()));
        return new AdminOrderSummaryResponse(
                order.getPublicId(), order.getOrderNo(), order.getOrderedAt(),
                representative == null ? null : representative.getPaidAt(), order.getStatus().name(),
                buyer == null ? null : buyer.getName(), buyer == null ? null : buyer.getEmail(),
                sellerNames, productSummary(items), items.size(),
                paymentAmount(order), order.getShippingFee(),
                representative == null ? null : representative.getMethod().name(),
                representative == null ? null : representative.getStatus().name(),
                aggregateDeliveryStatus(items, enrichment.latestDeliveryByItemId),
                hasActiveClaim(items, enrichment.claimsByItemId),
                actions(order, enrichment));
    }

    /** PAID 행 우선, 없으면 최신 행(목록은 id 내림차순이라 첫 원소). */
    private Payment representativePayment(List<Payment> payments) {
        if (payments == null || payments.isEmpty()) {
            return null;
        }
        return payments.stream()
                .filter(payment -> payment.getStatus() == PaymentStatus.PAID)
                .findFirst()
                .orElse(payments.get(0));
    }

    private static long paymentAmount(Order order) {
        return order.getTotalPrice() - order.getDiscountAmount() + order.getShippingFee();
    }

    private static String productSummary(List<OrderItem> items) {
        if (items.isEmpty()) {
            return "";
        }
        String first = items.get(0).getProductName();
        return items.size() == 1 ? first : first + " 외 " + (items.size() - 1) + "건";
    }

    private static String sellerName(Seller seller) {
        return seller == null ? null : seller.getCompanyName();
    }

    /** 배송 없음 null / 하나라도 SHIPPING → SHIPPING / 전부 DELIVERED → DELIVERED / 그 외 READY. */
    private static String aggregateDeliveryStatus(List<OrderItem> items, Map<Long, Delivery> deliveryByItemId) {
        List<Delivery> deliveries = items.stream()
                .map(item -> deliveryByItemId.get(item.getId()))
                .filter(delivery -> delivery != null)
                .toList();
        if (deliveries.isEmpty()) {
            return null;
        }
        if (deliveries.stream().anyMatch(delivery -> delivery.getStatus() == DeliveryStatus.SHIPPING)) {
            return DeliveryStatus.SHIPPING.name();
        }
        if (deliveries.size() == items.size()
                && deliveries.stream().allMatch(delivery -> delivery.getStatus() == DeliveryStatus.DELIVERED)) {
            return DeliveryStatus.DELIVERED.name();
        }
        return DeliveryStatus.READY.name();
    }

    private static boolean hasActiveClaim(List<OrderItem> items, Map<Long, List<Claim>> claimsByItemId) {
        return items.stream()
                .flatMap(item -> claimsByItemId.getOrDefault(item.getId(), List.<Claim>of()).stream())
                .anyMatch(claim -> claim.getStatus() == ClaimStatus.REQUESTED || claim.getStatus() == ClaimStatus.APPROVED);
    }

    /** 관리자 가능 액션: CANCEL(미결제 또는 취소 가능 항목 존재) / PREPARE_SHIPMENT(PAID 항목 존재) / MARK_DELIVERED(SHIPPING 배송 존재). */
    private static List<String> actions(Order order, Enrichment enrichment) {
        List<String> actions = new ArrayList<>();
        if (TERMINAL_STATUSES.contains(order.getStatus())) {
            return actions;
        }
        List<OrderItem> items = order.getItems();
        boolean cancellable = order.getStatus() == OrderStatus.PENDING_PAYMENT
                || items.stream().anyMatch(item -> item.getItemStatus().canTransitionTo(OrderItemStatus.CANCEL_REQUESTED));
        if (cancellable) {
            actions.add(ACTION_CANCEL);
        }
        if (items.stream().anyMatch(item -> item.getItemStatus() == OrderItemStatus.PAID)) {
            actions.add(ACTION_PREPARE_SHIPMENT);
        }
        boolean shipping = items.stream()
                .map(item -> enrichment.latestDeliveryByItemId.get(item.getId()))
                .anyMatch(delivery -> delivery != null && delivery.getStatus() == DeliveryStatus.SHIPPING);
        if (shipping) {
            actions.add(ACTION_MARK_DELIVERED);
        }
        return actions;
    }

    private static AdminOrderDetailResponse.DeliveryRow toDeliveryRow(Delivery delivery) {
        if (delivery == null) {
            return null;
        }
        return new AdminOrderDetailResponse.DeliveryRow(delivery.getPublicId(), delivery.getCarrier().name(),
                delivery.getTrackingNo(), delivery.getStatus().name(), delivery.getShippedAt(), delivery.getDeliveredAt());
    }

    private AdminOrderDetailResponse.ClaimRow toClaimRow(Claim claim) {
        return new AdminOrderDetailResponse.ClaimRow(claim.getPublicId(), claim.getType().name(), claim.getStatus().name(),
                claim.getReasonCode(), claim.getReasonDetail(), claim.getRequestedBy(), claim.getRequestedAt(),
                claim.getProcessedAt(), claim.getStatus() == ClaimStatus.REQUESTED);
    }

    /** 미결제 관리자 취소 audit(ORDER·UPDATE·diff에 reasonCode 포함)만 취소 사유로 해석한다. 파싱 실패는 warn 후 제외. */
    private List<AdminOrderDetailResponse.CancelReason> cancelReasons(Long orderId) {
        List<AdminOrderDetailResponse.CancelReason> reasons = new ArrayList<>();
        for (AuditLog auditLog : auditLogRepository.findByTargetTypeAndTargetId(PolymorphicTargetType.ORDER, orderId)) {
            Map<String, Map<String, Object>> diff = parseDiff(auditLog);
            if (diff == null || !diff.containsKey("reasonCode")) {
                continue;
            }
            reasons.add(new AdminOrderDetailResponse.CancelReason(
                    afterValue(diff.get("reasonCode")), afterValue(diff.get("reasonDetail")),
                    auditLog.getActorUserId(), auditLog.getActorRole(), auditLog.getCreatedAt()));
        }
        return reasons;
    }

    private Map<String, Map<String, Object>> parseDiff(AuditLog auditLog) {
        try {
            return objectMapper.readValue(auditLog.getDiffJson(), new TypeReference<Map<String, Map<String, Object>>>() { });
        } catch (Exception exception) {
            // 감사 diff는 표시 보조 정보 — 파싱 실패가 상세 조회를 막지 않도록 제외하고 남긴다.
            log.warn("[AdminOrder] audit diff_json 파싱 실패 → 취소 사유 제외: auditLogId={}", auditLog.getId(), exception);
            return null;
        }
    }

    private static String afterValue(Map<String, Object> change) {
        if (change == null || change.get("after") == null) {
            return null;
        }
        return String.valueOf(change.get("after"));
    }

    // ---------- 입력 정규화 (AdminProductQueryService 정합) ----------

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

    private Sort toSort(AdminOrderSort sort) {
        return switch (sort) {
            case OLDEST -> Sort.by(Sort.Order.asc("orderedAt"), Sort.Order.asc("id"));
            case LATEST -> Sort.by(Sort.Order.desc("orderedAt"), Sort.Order.desc("id"));
        };
    }

    private int clampSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
