package com.zslab.mall.order.service;

import com.github.f4b6a3.ulid.UlidCreator;
import com.zslab.mall.common.observability.TracedEventPublisher;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 Application Service(QB-6). 트랜잭션 경계는 메서드 단위다(QB-1).
 *
 * <p>주문 생성·결제 완료 반영·상태 재계산을 담당한다. Order.status 파생은 {@link OrderStatusResolver}(Domain Service)에 위임한다.
 * Claim 이벤트 핸들러(PaymentRefundCompletedHandler·ClaimRefundCompletedHandler 등)는 Track 5에서 추가되었다.
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
    private final TracedEventPublisher eventPublisher;
    private final EntityManager entityManager;

    public OrderService(
            OrderRepository orderRepository,
            OrderStatusResolver orderStatusResolver,
            TracedEventPublisher eventPublisher,
            EntityManager entityManager) {
        this.orderRepository = orderRepository;
        this.orderStatusResolver = orderStatusResolver;
        this.eventPublisher = eventPublisher;
        this.entityManager = entityManager;
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
                    itemCommand.productName(),
                    itemCommand.quantity(),
                    itemCommand.unitPrice(),
                    itemCommand.totalPrice(),
                    itemCommand.commissionRate(),
                    itemCommand.optionLabel()));
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
     * <p><b>늦은 웹훅 차단 불변식(FE-12c)</b>: Order.status가 PENDING_PAYMENT일 때만 결제 완료를 승인한다. 미결제 종료
     * (PAYMENT_EXPIRED)된 주문에 결제 성공 콜백이 뒤늦게 도착하면 승인을 거부(예외)해 트랜잭션을 롤백한다 —
     * 이미 재고가 해제·종료된 주문이 PAID로 되살아나 재고가 음수화되는 상태를 원천 차단한다. Order.markPaid의 CANCELLED item
     * 불법 전이 예외에 앞서 명시적 status 가드로 의도를 드러낸다.
     *
     * @throws IllegalArgumentException 주문이 없는 경우
     * @throws IllegalStateException 주문이 PENDING_PAYMENT가 아니어서 결제 승인이 불가한 경우(늦은 웹훅·이미 종료)
     */
    public Order markPaid(Long orderId, LocalDateTime paidAt) {
        Order order = findOrder(orderId);
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException(
                    "이미 종료된 주문에는 결제 완료를 반영할 수 없습니다(늦은 웹훅 차단): orderId=" + orderId
                            + ", status=" + order.getStatus());
        }
        order.markPaid(paidAt);
        return order;
    }

    /**
     * 주문 쓰기 락을 잡는다(Track 104-1 D-215·invariants P5 주문 단위 직렬화). 주문에 속한 결제·환불·클레임·품목·배송·주문 상태를 바꾸는
     * 쓰기 트랜잭션은 모두 이 메서드로 주문 행({@code SELECT ... FOR UPDATE})을 가장 먼저 잠근다 — 경로마다 첫 락이 달라(결제·클레임·배송·
     * 품목) 생기던 교차 순서가 사라지고, 같은 주문의 쓰기가 한 줄로 선다. 기존 클레임·배송·품목·환불·결제 행 락은 이 뒤에 그대로 잡는다.
     *
     * <p><b>호출 규약</b>: 쓰기 트랜잭션의 첫 문장·엔티티 적재 전. 주문 id는 엔티티를 적재하지 않는 스칼라 조회({@code findOrderIdBy…})로
     * 구한다. 락 전에 주문 소유 엔티티를 1차 캐시에 올려 두면 락 뒤에도 그 옛 인스턴스를 돌려받는다(D-168 트랩). 락 이후 비잠금 읽기는
     * READ COMMITTED(application.yml)라 최신 커밋을 본다. 같은 트랜잭션에서 다시 불러도 이미 쥔 락이라 무해하다. 한 트랜잭션에서 여러
     * 주문을 잠그는 경로는 현재 없다 — 생기면 주문 id 오름차순으로 잠근다(교착 순환 방지).
     *
     * @return 잠근 주문. 주문이 없으면 빈 값이며 아무것도 잠그지 않는다(호출부의 기존 미존재 처리 404·skip에 맡긴다)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Order> lockForWrite(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId);
    }

    /**
     * OrderItem 상태 변경 후 Order.status를 재계산해 반영한다(ORD-2). Resolver 결과를 그대로 적용한다.
     *
     * <p><b>재계산 일원화(Track 104-1 D-215·invariants P4)</b>: 주문 쓰기 락({@link #lockForWrite})을 쥔 트랜잭션에서만 호출된다.
     * 품목은 락 뒤에 적재되므로(READ COMMITTED) 앞서 커밋된 형제 품목 변경을 본다 — 락 없이 재계산하면 동시 트랜잭션이 바꾼 형제 품목을
     * 못 보고 옛 상태로 주문 상태를 덮어쓴다. 락 보유는 관리 엔티티의 잠금 모드로 확인하고, 없으면 예외로 막는다. 같은 트랜잭션에서
     * 재계산이 주문 UPDATE를 한 번 flush하면 Hibernate가 잠금 모드를 WRITE로 바꾸고({@code AbstractEntityEntry.postUpdate}) JPA로는
     * {@code PESSIMISTIC_FORCE_INCREMENT}로 보이므로 이것도 쓰기 락 보유로 인정한다(락을 쥔 두 번째 재계산이 500으로 새지 않게).
     *
     * @throws IllegalArgumentException 주문이 없는 경우
     * @throws IllegalStateException    주문 쓰기 락 없이 호출된 경우(호출 경로 누락·코드 결함)
     */
    public Order recalculateStatus(Long orderId) {
        Order order = findOrder(orderId);
        LockModeType lockMode = entityManager.getLockMode(order);
        if (lockMode != LockModeType.PESSIMISTIC_WRITE && lockMode != LockModeType.PESSIMISTIC_FORCE_INCREMENT) {
            throw new IllegalStateException(
                    "주문 쓰기 락 없이 Order.status를 재계산할 수 없습니다(lockForWrite 선행·P5): orderId=" + orderId);
        }
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
