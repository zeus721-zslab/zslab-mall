package com.zslab.mall.order.service;

import com.github.f4b6a3.ulid.UlidCreator;
import com.zslab.mall.order.command.CreateOrderCommand;
import com.zslab.mall.order.command.OrderItemCommand;
import com.zslab.mall.order.command.ShippingAddressCommand;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.entity.OrderShippingSnapshot;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.enums.OrderStatus;
import com.zslab.mall.order.event.OrderPlaced;
import com.zslab.mall.order.repository.OrderRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 Application Service(QB-6). 트랜잭션 경계는 메서드 단위다(QB-1).
 *
 * <p>주문 생성·결제 완료 반영·상태 재계산을 담당한다. Order.status 파생은 {@link OrderStatusResolver}(Domain Service)에 위임한다.
 * Claim 이벤트 핸들러(applyClaimRequested 등)는 Track 5 진입 시점에 추가한다(본 트랙 미작성).
 */
@Service
@Transactional
public class OrderService {

    /** order_no 날짜부 포맷(yyyyMMdd·QB-9). */
    private static final DateTimeFormatter ORDER_NO_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    /** ULID(26자) 후미 6자 시작 인덱스(QB-9 order_no 후미). */
    private static final int ULID_SUFFIX_START = 20;

    private final OrderRepository orderRepository;
    private final OrderStatusResolver orderStatusResolver;
    private final ApplicationEventPublisher eventPublisher;

    public OrderService(
            OrderRepository orderRepository,
            OrderStatusResolver orderStatusResolver,
            ApplicationEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.orderStatusResolver = orderStatusResolver;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 주문을 생성한다. OrderItem·OrderShippingSnapshot을 cascade PERSIST로 함께 영속하고 OrderPlaced를 발행한다.
     *
     * @throws IllegalArgumentException OrderItem이 0개(ORD-1)이거나 입력이 불완전한 경우
     * @throws IllegalStateException order_no 생성 충돌이 재시도 한도(1회)를 초과한 경우
     */
    public Order createOrder(CreateOrderCommand command) {
        if (command == null || command.items() == null || command.items().isEmpty()) {
            throw new IllegalArgumentException("주문에는 최소 1개의 OrderItem이 필요합니다(ORD-1).");
        }

        Order order = Order.create(
                command.buyerId(),
                generateUniqueOrderNo(),
                command.discountAmount(),
                command.shippingFee());

        for (OrderItemCommand itemCommand : command.items()) {
            order.addItem(OrderItem.create(
                    itemCommand.productId(),
                    itemCommand.variantId(),
                    itemCommand.sellerId(),
                    itemCommand.quantity(),
                    itemCommand.unitPrice(),
                    itemCommand.totalPrice()));
        }

        order.attachSnapshot(toSnapshot(command.shipping()));
        order.markOrdered(LocalDateTime.now());   // D-42 목록 정렬 기준·주문 확정 시각

        Order saved = orderRepository.save(order);

        // E1 OrderPlaced — payload는 식별자·시각 3필드 한정(QB-13). 소비 핸들러는 Track 7 이연.
        eventPublisher.publishEvent(new OrderPlaced(saved.getPublicId(), saved.getId(), LocalDateTime.now()));

        return saved;
    }

    /**
     * 결제 완료를 반영한다(동기화 규칙 [1]). 모든 OrderItem을 PAID로 전이하고 Order.status=PAID로 갱신한다.
     *
     * @throws IllegalArgumentException 주문이 없는 경우
     */
    public Order markPaid(Long orderId, LocalDateTime paidAt) {
        Order order = findOrder(orderId);
        order.markPaid(paidAt);
        return order;
    }

    /**
     * OrderItem 상태 변경 후 Order.status를 재계산해 반영한다(ORD-2). Resolver 결과를 그대로 적용한다.
     *
     * @throws IllegalArgumentException 주문이 없는 경우
     */
    public Order recalculateStatus(Long orderId) {
        Order order = findOrder(orderId);
        List<OrderItemStatus> itemStatuses = order.getItems().stream()
                .map(OrderItem::getItemStatus)
                .toList();
        OrderStatus resolved = orderStatusResolver.resolve(itemStatuses);
        order.applyResolvedStatus(resolved);
        return order;
    }

    private Order findOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: orderId=" + orderId));
    }

    private OrderShippingSnapshot toSnapshot(ShippingAddressCommand shipping) {
        if (shipping == null) {
            throw new IllegalArgumentException("배송지(shipping)는 null일 수 없습니다.");
        }
        return OrderShippingSnapshot.create(
                shipping.recipientName(),
                shipping.recipientPhone(),
                shipping.zonecode(),
                shipping.addressRoad(),
                shipping.addressJibun(),
                shipping.addressDetail(),
                shipping.deliveryMemo());
    }

    /**
     * order_no를 생성한다(QB-9 yyyyMMdd-XXXXXX·15자). UK 충돌 시 1회 재시도 후 한도 초과면 예외.
     * 최종 방어선은 DB UK(uk_order_order_no·ORD-4)다.
     */
    private String generateUniqueOrderNo() {
        String orderNo = generateOrderNo();
        if (orderRepository.existsByOrderNo(orderNo)) {
            orderNo = generateOrderNo();
            if (orderRepository.existsByOrderNo(orderNo)) {
                throw new IllegalStateException("order_no 생성 충돌이 재시도 한도(1회)를 초과했습니다: " + orderNo);
            }
        }
        return orderNo;
    }

    private String generateOrderNo() {
        String ulidSuffix = UlidCreator.getMonotonicUlid().toString()
                .substring(ULID_SUFFIX_START)
                .toUpperCase();
        return LocalDate.now().format(ORDER_NO_DATE_FORMAT) + "-" + ulidSuffix;
    }
}
