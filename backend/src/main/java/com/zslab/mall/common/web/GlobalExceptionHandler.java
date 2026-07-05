package com.zslab.mall.common.web;

import com.zslab.mall.auth.exception.AdminOperatorAlreadyExistsException;
import com.zslab.mall.auth.exception.AuthenticationFailedException;
import com.zslab.mall.auth.exception.SuperAdminRequiredException;
import com.zslab.mall.cart.exception.CartItemNotFoundException;
import com.zslab.mall.cart.exception.EmptyCartCheckoutException;
import com.zslab.mall.category.exception.CategoryDuplicateException;
import com.zslab.mall.category.exception.CategoryNotFoundException;
import com.zslab.mall.checkout.exception.CheckoutItemMismatchException;
import com.zslab.mall.checkout.exception.CheckoutItemNotFoundException;
import com.zslab.mall.checkout.exception.IdempotencyKeyInProgressException;
import com.zslab.mall.claim.exception.ClaimInvalidStateException;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.common.exception.UnauthenticatedException;
import com.zslab.mall.delivery.exception.DeliveryInvalidStateException;
import com.zslab.mall.delivery.exception.DeliveryNotFoundException;
import com.zslab.mall.inventory.exception.InventoryInvariantViolationException;
import com.zslab.mall.order.exception.OrderItemInvalidStateException;
import com.zslab.mall.order.exception.OrderNotFoundException;
import com.zslab.mall.order.exception.OrderNotPayableException;
import com.zslab.mall.payment.exception.InvalidCallbackException;
import com.zslab.mall.payment.exception.PaymentAlreadyCompletedException;
import com.zslab.mall.payment.exception.PaymentInProgressException;
import com.zslab.mall.payment.exception.PaymentNotFoundException;
import com.zslab.mall.product.exception.ProductNotFoundException;
import com.zslab.mall.product.exception.ProductVariantNotFoundException;
import com.zslab.mall.product.exception.ProductVariantOptionConflictException;
import com.zslab.mall.refund.exception.RefundInvariantViolationException;
import com.zslab.mall.refund.exception.RefundNotFoundException;
import com.zslab.mall.seller.exception.SellerUserAlreadyExistsException;
import com.zslab.mall.settlement.exception.SettlementAlreadyExistsException;
import com.zslab.mall.settlement.exception.SettlementInvalidStateException;
import com.zslab.mall.settlement.exception.SettlementNotFoundException;
import com.zslab.mall.settlement.exception.SettlementPeriodInvalidException;
import com.zslab.mall.user.exception.EmailAlreadyExistsException;
import com.zslab.mall.user.exception.UserNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 전역 예외 핸들러(§14·D-48). RFC 7807 {@link ProblemDetail} + 커스텀 {@code code}·{@code traceId} 속성으로 일원화한다.
 *
 * <p>HTTP 상태 매트릭스(§14·§17)에 1:1 매핑한다. 필터 계층 401/403은 {@link com.zslab.mall.common.security.SecurityErrorHandler}가
 * 처리하나, 서비스 계층 도메인 403({@link SuperAdminRequiredException}·Track 38)은 여기서 매핑한다(동일 code=FORBIDDEN).
 * 429/502/503(본 트랙 미구현)은 제외한다. traceId는 {@link TraceIdFilter}가 MDC에 넣은 값을 읽는다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String TYPE_BASE = "https://zslab-mall.duckdns.org/errors/";

    private static final String CODE_VALIDATION_FAILED = "VALIDATION_FAILED";
    private static final String CODE_MALFORMED_REQUEST = "MALFORMED_REQUEST";
    private static final String CODE_UNAUTHENTICATED = "UNAUTHENTICATED";
    private static final String CODE_AUTHENTICATION_FAILED = "AUTHENTICATION_FAILED";
    private static final String CODE_ORDER_NOT_FOUND = "ORDER_NOT_FOUND";
    private static final String CODE_PRODUCT_NOT_FOUND = "PRODUCT_NOT_FOUND";
    private static final String CODE_IDEMPOTENCY_KEY_IN_PROGRESS = "IDEMPOTENCY_KEY_IN_PROGRESS";
    private static final String CODE_PAYMENT_IN_PROGRESS = "PAYMENT_IN_PROGRESS";
    private static final String CODE_OPTIMISTIC_LOCK_FAILURE = "OPTIMISTIC_LOCK_FAILURE";
    private static final String CODE_PAYMENT_ALREADY_COMPLETED = "PAYMENT_ALREADY_COMPLETED";
    private static final String CODE_ORDER_NOT_PAYABLE = "ORDER_NOT_PAYABLE";
    private static final String CODE_CHECKOUT_ITEM_MISMATCH = "CHECKOUT_ITEM_MISMATCH";
    private static final String CODE_CART_CHECKOUT_EMPTY = "CART_CHECKOUT_EMPTY";
    private static final String CODE_INVALID_CALLBACK = "INVALID_CALLBACK";
    private static final String CODE_REFUND_NOT_FOUND = "REFUND_NOT_FOUND";
    private static final String CODE_REFUND_INVARIANT_VIOLATION = "REFUND_INVARIANT_VIOLATION";
    private static final String CODE_CLAIM_NOT_FOUND = "CLAIM_NOT_FOUND";
    private static final String CODE_DELIVERY_NOT_FOUND = "DELIVERY_NOT_FOUND";
    private static final String CODE_PRODUCT_VARIANT_NOT_FOUND = "PRODUCT_VARIANT_NOT_FOUND";
    private static final String CODE_CLAIM_STATE_INVALID = "CLAIM_STATE_INVALID";
    private static final String CODE_INVENTORY_INVARIANT_VIOLATION = "INVENTORY_INVARIANT_VIOLATION";
    private static final String CODE_DELIVERY_INVALID_STATE = "DELIVERY_INVALID_STATE";
    private static final String CODE_ORDER_ITEM_INVALID_STATE = "ORDER_ITEM_INVALID_STATE";
    private static final String CODE_PAYMENT_NOT_FOUND = "PAYMENT_NOT_FOUND";
    private static final String CODE_EMAIL_ALREADY_EXISTS = "EMAIL_ALREADY_EXISTS";
    private static final String CODE_USER_NOT_FOUND = "USER_NOT_FOUND";
    private static final String CODE_SELLER_USER_ALREADY_EXISTS = "SELLER_USER_ALREADY_EXISTS";
    private static final String CODE_ADMIN_OPERATOR_ALREADY_EXISTS = "ADMIN_OPERATOR_ALREADY_EXISTS";
    private static final String CODE_CATEGORY_NOT_FOUND = "CATEGORY_NOT_FOUND";
    private static final String CODE_CATEGORY_DUPLICATE = "CATEGORY_DUPLICATE";
    private static final String CODE_CART_ITEM_NOT_FOUND = "CART_ITEM_NOT_FOUND";
    private static final String CODE_PRODUCT_VARIANT_OPTION_CONFLICT = "PRODUCT_VARIANT_OPTION_CONFLICT";
    private static final String CODE_FORBIDDEN = "FORBIDDEN";
    private static final String CODE_SETTLEMENT_PERIOD_INVALID = "SETTLEMENT_PERIOD_INVALID";
    private static final String CODE_SETTLEMENT_ALREADY_EXISTS = "SETTLEMENT_ALREADY_EXISTS";
    private static final String CODE_SETTLEMENT_NOT_FOUND = "SETTLEMENT_NOT_FOUND";
    private static final String CODE_SETTLEMENT_INVALID_STATE = "SETTLEMENT_INVALID_STATE";
    private static final String CODE_INTERNAL_ERROR = "INTERNAL_ERROR";

    // ===== 400 =====
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return build(HttpStatus.BAD_REQUEST, CODE_VALIDATION_FAILED,
                detail.isBlank() ? "요청 검증에 실패했습니다." : detail, request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MalformedRequestException.class, IllegalArgumentException.class})
    public ResponseEntity<ProblemDetail> handleMalformed(Exception exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, CODE_MALFORMED_REQUEST, exception.getMessage(), request);
    }

    @ExceptionHandler(SettlementPeriodInvalidException.class)
    public ResponseEntity<ProblemDetail> handleSettlementPeriodInvalid(
            SettlementPeriodInvalidException exception, HttpServletRequest request) {
        // Track 48 P3: 정산 배치 year/month 범위 위반(월 1~12·연도 2000~2100 밖). 도메인 규칙 검증(Service)·400.
        return build(HttpStatus.BAD_REQUEST, CODE_SETTLEMENT_PERIOD_INVALID, exception.getMessage(), request);
    }

    // ===== 401 =====
    @ExceptionHandler(UnauthenticatedException.class)
    public ResponseEntity<ProblemDetail> handleUnauthenticated(
            UnauthenticatedException exception, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, CODE_UNAUTHENTICATED, exception.getMessage(), request);
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<ProblemDetail> handleAuthenticationFailed(
            AuthenticationFailedException exception, HttpServletRequest request) {
        // Track 33: 로그인 실패(미존재·비활성·비번·role 통합). 사유 무관 401·"Invalid email or password."(계정 열거 방지).
        return build(HttpStatus.UNAUTHORIZED, CODE_AUTHENTICATION_FAILED, exception.getMessage(), request);
    }

    // ===== 403 =====
    @ExceptionHandler(SuperAdminRequiredException.class)
    public ResponseEntity<ProblemDetail> handleSuperAdminRequired(
            SuperAdminRequiredException exception, HttpServletRequest request) {
        // Track 38: 운영 관리자 공급은 SUPER_ADMIN 전용. hasRole("ADMIN") 코어스 게이트 통과 후 서비스에서 세분 검증 실패(403).
        // 필터 계층 403(SecurityErrorHandler)과 달리 서비스 계층이 던지는 도메인 403이라 여기서 동일 code=FORBIDDEN으로 매핑한다.
        log.warn("[Auth] SUPER_ADMIN 인가 실패(403): {}", exception.getMessage());
        return build(HttpStatus.FORBIDDEN, CODE_FORBIDDEN, exception.getMessage(), request);
    }

    // ===== 404 =====
    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleOrderNotFound(
            OrderNotFoundException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, CODE_ORDER_NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(CheckoutItemNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleCheckoutItemNotFound(
            CheckoutItemNotFoundException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, CODE_PRODUCT_NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleProductNotFound(
            ProductNotFoundException exception, HttpServletRequest request) {
        // Track 44: 구매자 카탈로그 단건 미존재·비노출(status/판매자상태/삭제) 은닉(404). 존재 여부 노출 회피(§2).
        return build(HttpStatus.NOT_FOUND, CODE_PRODUCT_NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(RefundNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleRefundNotFound(
            RefundNotFoundException exception, HttpServletRequest request) {
        // Track 5 webhook: pg_refund_id 미매칭(404). 500 fallback으로 새는 라이브 트랩 차단.
        return build(HttpStatus.NOT_FOUND, CODE_REFUND_NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(ClaimNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleClaimNotFound(
            ClaimNotFoundException exception, HttpServletRequest request) {
        // Track 9 PR-B: 클레임 미존재·타인 소유(정보 노출 회피·Q8)·주문 품목 미매칭(404).
        return build(HttpStatus.NOT_FOUND, CODE_CLAIM_NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(DeliveryNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleDeliveryNotFound(
            DeliveryNotFoundException exception, HttpServletRequest request) {
        // Track 20 D-104 §5: Admin mark-delivered 시 deliveryPublicId 미존재(404). 500 fallback으로 새는 트랩 차단.
        return build(HttpStatus.NOT_FOUND, CODE_DELIVERY_NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(ProductVariantNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleProductVariantNotFound(
            ProductVariantNotFoundException exception, HttpServletRequest request) {
        // Track 21 D-105 §5: Admin 재고 조정 시 variantPublicId 미존재(404). 500 fallback으로 새는 트랩 차단.
        return build(HttpStatus.NOT_FOUND, CODE_PRODUCT_VARIANT_NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ProblemDetail> handlePaymentNotFound(
            PaymentNotFoundException exception, HttpServletRequest request) {
        // Track 28 D-113: Admin 결제 취소 시 paymentPublicId 미존재(404). 500 fallback으로 새는 트랩 차단.
        return build(HttpStatus.NOT_FOUND, CODE_PAYMENT_NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleUserNotFound(
            UserNotFoundException exception, HttpServletRequest request) {
        // Track 37: 판매자 provisioning owner userId 미존재(404). 500 fallback으로 새는 트랩 차단.
        return build(HttpStatus.NOT_FOUND, CODE_USER_NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(CategoryNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleCategoryNotFound(
            CategoryNotFoundException exception, HttpServletRequest request) {
        // Track 39: 상품 등록 시 categoryId 미존재(404). 500 fallback으로 새는 트랩 차단.
        return build(HttpStatus.NOT_FOUND, CODE_CATEGORY_NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(CartItemNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleCartItemNotFound(
            CartItemNotFoundException exception, HttpServletRequest request) {
        // Track 45: 장바구니 수량변경·selected 토글 시 대상 variant 미담김(404). 타 buyer 소유도 동일 404 은닉.
        return build(HttpStatus.NOT_FOUND, CODE_CART_ITEM_NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(SettlementNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleSettlementNotFound(
            SettlementNotFoundException exception, HttpServletRequest request) {
        // Track 49: 정산 전이(confirm·pay) 시 settlementId 미존재(404). 500 fallback으로 새는 트랩 차단.
        return build(HttpStatus.NOT_FOUND, CODE_SETTLEMENT_NOT_FOUND, exception.getMessage(), request);
    }

    // ===== 409 =====
    @ExceptionHandler(IdempotencyKeyInProgressException.class)
    public ResponseEntity<ProblemDetail> handleIdempotencyInProgress(
            IdempotencyKeyInProgressException exception, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, CODE_IDEMPOTENCY_KEY_IN_PROGRESS, exception.getMessage(), request);
    }

    @ExceptionHandler(PaymentInProgressException.class)
    public ResponseEntity<ProblemDetail> handlePaymentInProgress(
            PaymentInProgressException exception, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, CODE_PAYMENT_IN_PROGRESS, exception.getMessage(), request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(
            OptimisticLockingFailureException exception, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, CODE_OPTIMISTIC_LOCK_FAILURE, "동시 수정 충돌이 발생했습니다.", request);
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ProblemDetail> handleEmailAlreadyExists(
            EmailAlreadyExistsException exception, HttpServletRequest request) {
        // Track 34: Buyer 셀프가입 email 중복(409). existsByEmail 사전 검증 실패.
        return build(HttpStatus.CONFLICT, CODE_EMAIL_ALREADY_EXISTS, exception.getMessage(), request);
    }

    @ExceptionHandler(SellerUserAlreadyExistsException.class)
    public ResponseEntity<ProblemDetail> handleSellerUserAlreadyExists(
            SellerUserAlreadyExistsException exception, HttpServletRequest request) {
        // Track 37: 판매자 provisioning 중복 소속(409·V12 user_id UNIQUE). seller_user saveAndFlush 위반→seller INSERT 원자 롤백.
        return build(HttpStatus.CONFLICT, CODE_SELLER_USER_ALREADY_EXISTS, exception.getMessage(), request);
    }

    @ExceptionHandler(AdminOperatorAlreadyExistsException.class)
    public ResponseEntity<ProblemDetail> handleAdminOperatorAlreadyExists(
            AdminOperatorAlreadyExistsException exception, HttpServletRequest request) {
        // Track 38: 운영 관리자 중복 부여(409·uk_user_role(user_id, role_id) 위반). user_role saveAndFlush 위반→@Transactional 롤백.
        return build(HttpStatus.CONFLICT, CODE_ADMIN_OPERATOR_ALREADY_EXISTS, exception.getMessage(), request);
    }

    @ExceptionHandler(ProductVariantOptionConflictException.class)
    public ResponseEntity<ProblemDetail> handleProductVariantOptionConflict(
            ProductVariantOptionConflictException exception, HttpServletRequest request) {
        // Track 39: 상품 등록 시 동일 옵션 조합 변형 중복(409·uk_product_variant_options). DataIntegrityViolationException→409 변환.
        return build(HttpStatus.CONFLICT, CODE_PRODUCT_VARIANT_OPTION_CONFLICT, exception.getMessage(), request);
    }

    @ExceptionHandler(CategoryDuplicateException.class)
    public ResponseEntity<ProblemDetail> handleCategoryDuplicate(
            CategoryDuplicateException exception, HttpServletRequest request) {
        // Track 46: 카테고리 생성 시 형제 스코프 동일 display_name 중복(409·uk_category_dedup_key). saveAndFlush 위반→409 변환.
        return build(HttpStatus.CONFLICT, CODE_CATEGORY_DUPLICATE, exception.getMessage(), request);
    }

    @ExceptionHandler(SettlementAlreadyExistsException.class)
    public ResponseEntity<ProblemDetail> handleSettlementAlreadyExists(
            SettlementAlreadyExistsException exception, HttpServletRequest request) {
        // Track 48 P3: 동일 seller·기간 정산 중복(409·uk_settlement_seller_period). 동시 배치 실행 레이스 백스톱(선확인은 skip).
        log.warn("[Settlement] 정산 중복(409): {}", exception.getMessage());
        return build(HttpStatus.CONFLICT, CODE_SETTLEMENT_ALREADY_EXISTS, exception.getMessage(), request);
    }

    // ===== 422 =====
    @ExceptionHandler(PaymentAlreadyCompletedException.class)
    public ResponseEntity<ProblemDetail> handlePaymentAlreadyCompleted(
            PaymentAlreadyCompletedException exception, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, CODE_PAYMENT_ALREADY_COMPLETED, exception.getMessage(), request);
    }

    @ExceptionHandler(OrderNotPayableException.class)
    public ResponseEntity<ProblemDetail> handleOrderNotPayable(
            OrderNotPayableException exception, HttpServletRequest request) {
        // §6: detail에 차단 사유 코드(PRODUCT_NOT_ON_SALE·OUT_OF_STOCK) 명시
        return build(HttpStatus.UNPROCESSABLE_ENTITY, CODE_ORDER_NOT_PAYABLE, exception.getReason().name(), request);
    }

    @ExceptionHandler(CheckoutItemMismatchException.class)
    public ResponseEntity<ProblemDetail> handleCheckoutItemMismatch(
            CheckoutItemMismatchException exception, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, CODE_CHECKOUT_ITEM_MISMATCH, exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidCallbackException.class)
    public ResponseEntity<ProblemDetail> handleInvalidCallback(
            InvalidCallbackException exception, HttpServletRequest request) {
        log.warn("[PaymentWebhook] 콜백 거부(422): {}", exception.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, CODE_INVALID_CALLBACK, exception.getMessage(), request);
    }

    @ExceptionHandler(RefundInvariantViolationException.class)
    public ResponseEntity<ProblemDetail> handleRefundInvariantViolation(
            RefundInvariantViolationException exception, HttpServletRequest request) {
        // Track 5: PAY-1 과환불·RFN-1 위반(422). 도메인 불변조건 위반.
        log.warn("[RefundWebhook] 불변조건 위반(422): {}", exception.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, CODE_REFUND_INVARIANT_VIOLATION, exception.getMessage(), request);
    }

    @ExceptionHandler(ClaimInvalidStateException.class)
    public ResponseEntity<ProblemDetail> handleClaimInvalidState(
            ClaimInvalidStateException exception, HttpServletRequest request) {
        // Track 9 PR-B(D-89 Q3): 클레임 상태·정책 위반(CANCEL 한정·CLM-5·canTransitionTo). 500 fallback 차단·422 매핑(D-50).
        log.warn("[Claim] 상태 위반(422): {}", exception.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, CODE_CLAIM_STATE_INVALID, exception.getMessage(), request);
    }

    @ExceptionHandler(InventoryInvariantViolationException.class)
    public ResponseEntity<ProblemDetail> handleInventoryInvariantViolation(
            InventoryInvariantViolationException exception, HttpServletRequest request) {
        // Track 17 D-101 §2·§6: INV-1·INV-3·INV-4 위반(422). 도메인 불변조건 위반·ClaimInvalidStateException 선례 정합.
        log.warn("[Inventory] 불변조건 위반(422): {}", exception.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, CODE_INVENTORY_INVARIANT_VIOLATION, exception.getMessage(), request);
    }

    @ExceptionHandler(DeliveryInvalidStateException.class)
    public ResponseEntity<ProblemDetail> handleDeliveryInvalidState(
            DeliveryInvalidStateException exception, HttpServletRequest request) {
        // Track 23: 배송 개시 불가 상태(OrderItem 비-PAID 등). 500 fallback 차단·422 매핑(ClaimInvalidStateException 선례).
        log.warn("[Delivery] 배송 개시 상태 위반(422): {}", exception.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, CODE_DELIVERY_INVALID_STATE, exception.getMessage(), request);
    }

    @ExceptionHandler(OrderItemInvalidStateException.class)
    public ResponseEntity<ProblemDetail> handleOrderItemInvalidState(
            OrderItemInvalidStateException exception, HttpServletRequest request) {
        // Track 47: 구매확정 불가 상태(OrderItem 비-DELIVERED 등). 500 fallback 차단·422 매핑(DeliveryInvalidStateException 선례).
        log.warn("[Order] 구매확정 상태 위반(422): {}", exception.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, CODE_ORDER_ITEM_INVALID_STATE, exception.getMessage(), request);
    }

    @ExceptionHandler(SettlementInvalidStateException.class)
    public ResponseEntity<ProblemDetail> handleSettlementInvalidState(
            SettlementInvalidStateException exception, HttpServletRequest request) {
        // Track 49: 정산 전이 불가 상태(순방향 아님·PAID 불가역 등). 500 fallback 차단·422 매핑(OrderItemInvalidStateException 선례).
        log.warn("[Settlement] 정산 상태 위반(422): {}", exception.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, CODE_SETTLEMENT_INVALID_STATE, exception.getMessage(), request);
    }

    @ExceptionHandler(EmptyCartCheckoutException.class)
    public ResponseEntity<ProblemDetail> handleEmptyCartCheckout(
            EmptyCartCheckoutException exception, HttpServletRequest request) {
        // Track 41 β: 장바구니 결제 시 selected 품목 0개(빈 주문 선가드·ORD-1 도달 전 차단). well-formed 요청·업무 전제 실패(422·클라 교정).
        return build(HttpStatus.UNPROCESSABLE_ENTITY, CODE_CART_CHECKOUT_EMPTY, exception.getMessage(), request);
    }

    // ===== 500 (fallback) =====
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("[GlobalException] 미분류 서버 오류: {}", exception.toString(), exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, CODE_INTERNAL_ERROR, "서버 오류가 발생했습니다.", request);
    }

    private ResponseEntity<ProblemDetail> build(
            HttpStatus status, String code, String detail, HttpServletRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                status, detail != null ? detail : status.getReasonPhrase());
        problemDetail.setType(URI.create(TYPE_BASE + code.toLowerCase().replace('_', '-')));
        problemDetail.setTitle(toTitle(code));
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("code", code);
        problemDetail.setProperty("traceId", MDC.get(TraceIdFilter.TRACE_ID));
        return ResponseEntity.status(status).body(problemDetail);
    }

    /** SCREAMING_SNAKE_CASE code를 "Title Case" 제목으로 변환한다(예: ORDER_NOT_FOUND → "Order Not Found"). */
    private String toTitle(String code) {
        StringBuilder title = new StringBuilder();
        for (String part : code.toLowerCase().split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            title.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(' ');
        }
        return title.toString().trim();
    }
}
