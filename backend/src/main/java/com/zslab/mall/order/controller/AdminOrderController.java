package com.zslab.mall.order.controller;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.common.auth.ActorRoleResolver;
import com.zslab.mall.common.auth.AdminActorResolver;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.order.controller.request.AdminOrderCancelRequest;
import com.zslab.mall.order.controller.request.AdminOrderSort;
import com.zslab.mall.order.controller.request.PrepareShipmentRequest;
import com.zslab.mall.order.controller.response.AdminOrderCancelResponse;
import com.zslab.mall.order.controller.response.AdminOrderDetailResponse;
import com.zslab.mall.order.controller.response.AdminOrderSummaryResponse;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.order.controller.response.PrepareShipmentResponse;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderStatus;
import com.zslab.mall.order.service.AdminOrderCancelService;
import com.zslab.mall.order.exception.OrderNotFoundException;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.service.AdminOrderQueryService;
import com.zslab.mall.order.service.OrderShippingService;
import com.zslab.mall.payment.enums.PaymentStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 주문 API(Track 79 D-168). 인가는 SecurityConfig의 {@code /api/v1/admin/**}→{@code hasRole("ADMIN")}가 강제한다
 * ({@code AdminProductController} 선례·메서드 @PreAuthorize 미사용). 감사 컨텍스트는 actorId·coarse role만 조립한다.
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
public class AdminOrderController {

    private final AdminOrderQueryService adminOrderQueryService;
    private final AdminOrderCancelService adminOrderCancelService;
    private final OrderShippingService orderShippingService;
    private final OrderItemRepository orderItemRepository;
    private final AdminActorResolver adminActorResolver;
    private final ActorRoleResolver actorRoleResolver;

    public AdminOrderController(
            AdminOrderQueryService adminOrderQueryService,
            AdminOrderCancelService adminOrderCancelService,
            OrderShippingService orderShippingService,
            OrderItemRepository orderItemRepository,
            AdminActorResolver adminActorResolver,
            ActorRoleResolver actorRoleResolver) {
        this.adminOrderQueryService = adminOrderQueryService;
        this.adminOrderCancelService = adminOrderCancelService;
        this.orderShippingService = orderShippingService;
        this.orderItemRepository = orderItemRepository;
        this.adminActorResolver = adminActorResolver;
        this.actorRoleResolver = actorRoleResolver;
    }

    private AuditContext auditContext(HttpServletRequest request) {
        return AuditContext.of(adminActorResolver.resolve(request), actorRoleResolver.requireCoarseRole());
    }

    /**
     * 관리자 주문 목록. 필터: status(주문)·paymentStatus·deliveryStatus·from/to(ordered_at·ISO-8601)·buyerPublicId(회원 정확·Track 84·미존재는 빈 페이지)·keyword(주문번호 정확·주문자
     * 이름/이메일·상품명 부분). 허용 외 enum·sort 값 400, keyword 50자 초과·from&gt;to 400(MALFORMED_REQUEST).
     */
    @GetMapping
    public ResponseEntity<PagedResponse<AdminOrderSummaryResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) PaymentStatus paymentStatus,
            @RequestParam(required = false) DeliveryStatus deliveryStatus,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String buyerPublicId,
            @RequestParam(defaultValue = "LATEST") AdminOrderSort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(adminOrderQueryService.listOrders(
                keyword, status, paymentStatus, deliveryStatus, from, to, buyerPublicId, sort, page, size));
    }

    /** 관리자 주문 상세. 미존재 404. */
    @GetMapping("/{orderPublicId}")
    public ResponseEntity<AdminOrderDetailResponse> get(@PathVariable String orderPublicId) {
        return ResponseEntity.ok(adminOrderQueryService.getOrder(orderPublicId));
    }

    /**
     * 관리자 주문 취소. 미결제 주문은 전체 종료(PAYMENT_EXPIRED·재고 해제·사유 audit), 결제 후 주문은 항목별 Claim(CANCEL) 생성 +
     * 승인(환불은 기존 경로). 200 + 생성된 Claim 목록(미결제는 빈 목록). 미존재 404·이미 종료 409·항목 상태 불가/CLM-5 422.
     */
    @PostMapping("/{orderPublicId}/cancel")
    public ResponseEntity<AdminOrderCancelResponse> cancel(
            @PathVariable String orderPublicId,
            @RequestBody @Valid AdminOrderCancelRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(adminOrderCancelService.cancel(orderPublicId, request.orderItemPublicIds(),
                request.reasonCode(), request.reasonDetail(), auditContext(httpRequest)));
    }

    /**
     * 관리자 송장 등록(Track 79 D-168·F). 셀러 {@code POST /api/v1/order-items/{id}/prepare-shipment}와 동일 body·응답이며
     * 소유 검증만 없다. 품목 미존재 404·PAID 아님 422(DELIVERY_INVALID_STATE).
     */
    @PostMapping("/items/{orderItemPublicId}/prepare-shipment")
    public ResponseEntity<PrepareShipmentResponse> prepareShipment(
            @PathVariable String orderItemPublicId,
            @Valid @RequestBody PrepareShipmentRequest request) {
        OrderItem orderItem = orderItemRepository.findByPublicId(orderItemPublicId)
                .orElseThrow(() -> new OrderNotFoundException("주문 품목을 찾을 수 없습니다: publicId=" + orderItemPublicId));
        return ResponseEntity.ok(PrepareShipmentResponse.from(
                orderShippingService.prepareShipmentByAdmin(orderItem.getId(), request.carrier(), request.trackingNo())));
    }
}
