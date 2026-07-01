package com.zslab.mall.common.web;

import com.zslab.mall.checkout.exception.CheckoutItemMismatchException;
import com.zslab.mall.checkout.exception.CheckoutItemNotFoundException;
import com.zslab.mall.checkout.exception.IdempotencyKeyInProgressException;
import com.zslab.mall.claim.exception.ClaimInvalidStateException;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.common.exception.UnauthenticatedException;
import com.zslab.mall.delivery.exception.DeliveryNotFoundException;
import com.zslab.mall.inventory.exception.InventoryInvariantViolationException;
import com.zslab.mall.order.exception.OrderNotFoundException;
import com.zslab.mall.order.exception.OrderNotPayableException;
import com.zslab.mall.payment.exception.InvalidCallbackException;
import com.zslab.mall.payment.exception.PaymentAlreadyCompletedException;
import com.zslab.mall.payment.exception.PaymentInProgressException;
import com.zslab.mall.refund.exception.RefundInvariantViolationException;
import com.zslab.mall.refund.exception.RefundNotFoundException;
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
 * <p>HTTP 상태 매트릭스(§14·§17)에 1:1 매핑한다. 403(Spring Security 후속 트랙)·429/502/503(본 트랙 미구현)은 제외한다.
 * traceId는 {@link TraceIdFilter}가 MDC에 넣은 값을 읽는다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String TYPE_BASE = "https://zslab-mall.duckdns.org/errors/";

    private static final String CODE_VALIDATION_FAILED = "VALIDATION_FAILED";
    private static final String CODE_MALFORMED_REQUEST = "MALFORMED_REQUEST";
    private static final String CODE_UNAUTHENTICATED = "UNAUTHENTICATED";
    private static final String CODE_ORDER_NOT_FOUND = "ORDER_NOT_FOUND";
    private static final String CODE_PRODUCT_NOT_FOUND = "PRODUCT_NOT_FOUND";
    private static final String CODE_IDEMPOTENCY_KEY_IN_PROGRESS = "IDEMPOTENCY_KEY_IN_PROGRESS";
    private static final String CODE_PAYMENT_IN_PROGRESS = "PAYMENT_IN_PROGRESS";
    private static final String CODE_OPTIMISTIC_LOCK_FAILURE = "OPTIMISTIC_LOCK_FAILURE";
    private static final String CODE_PAYMENT_ALREADY_COMPLETED = "PAYMENT_ALREADY_COMPLETED";
    private static final String CODE_ORDER_NOT_PAYABLE = "ORDER_NOT_PAYABLE";
    private static final String CODE_CHECKOUT_ITEM_MISMATCH = "CHECKOUT_ITEM_MISMATCH";
    private static final String CODE_INVALID_CALLBACK = "INVALID_CALLBACK";
    private static final String CODE_REFUND_NOT_FOUND = "REFUND_NOT_FOUND";
    private static final String CODE_REFUND_INVARIANT_VIOLATION = "REFUND_INVARIANT_VIOLATION";
    private static final String CODE_CLAIM_NOT_FOUND = "CLAIM_NOT_FOUND";
    private static final String CODE_DELIVERY_NOT_FOUND = "DELIVERY_NOT_FOUND";
    private static final String CODE_CLAIM_STATE_INVALID = "CLAIM_STATE_INVALID";
    private static final String CODE_INVENTORY_INVARIANT_VIOLATION = "INVENTORY_INVARIANT_VIOLATION";
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

    // ===== 401 =====
    @ExceptionHandler(UnauthenticatedException.class)
    public ResponseEntity<ProblemDetail> handleUnauthenticated(
            UnauthenticatedException exception, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, CODE_UNAUTHENTICATED, exception.getMessage(), request);
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
