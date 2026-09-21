package com.zslab.mall.common.web;

import com.zslab.mall.auth.exception.AdminOperatorAlreadyExistsException;
import com.zslab.mall.auth.exception.AuthenticationFailedException;
import com.zslab.mall.auth.exception.LastSuperAdminRevocationException;
import com.zslab.mall.auth.exception.RoleAssignmentNotFoundException;
import com.zslab.mall.auth.exception.SelfRoleRevocationException;
import com.zslab.mall.auth.exception.SuperAdminRequiredException;
import com.zslab.mall.cart.exception.CartItemNotFoundException;
import com.zslab.mall.cart.exception.CartItemNotPurchasableException;
import com.zslab.mall.cart.exception.EmptyCartCheckoutException;
import com.zslab.mall.category.exception.CategoryDuplicateException;
import com.zslab.mall.category.exception.CategoryHasProductsException;
import com.zslab.mall.category.exception.CategoryNotFoundException;
import com.zslab.mall.checkout.exception.CheckoutItemMismatchException;
import com.zslab.mall.checkout.exception.CheckoutItemNotFoundException;
import com.zslab.mall.checkout.exception.IdempotencyKeyInProgressException;
import com.zslab.mall.claim.exception.ClaimInvalidStateException;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.file.exception.StoredFileNotFoundException;
import com.zslab.mall.common.exception.UnauthenticatedException;
import com.zslab.mall.delivery.exception.DeliveryInvalidStateException;
import com.zslab.mall.delivery.exception.DeliveryNotFoundException;
import com.zslab.mall.delivery.exception.DeliveryTrackingNoConflictException;
import com.zslab.mall.grade.exception.GradePolicyUnavailableException;
import com.zslab.mall.inventory.exception.InventoryInvariantViolationException;
import com.zslab.mall.order.exception.OrderItemInvalidStateException;
import com.zslab.mall.order.exception.OrderNotFoundException;
import com.zslab.mall.order.exception.OrderNotPayableException;
import com.zslab.mall.payment.exception.InvalidCallbackException;
import com.zslab.mall.payment.exception.OrderNotPendingPaymentException;
import com.zslab.mall.payment.exception.PaymentAlreadyCompletedException;
import com.zslab.mall.payment.exception.PaymentInProgressException;
import com.zslab.mall.payment.exception.PaymentInvalidStateException;
import com.zslab.mall.payment.exception.PaymentNotFoundException;
import com.zslab.mall.payment.exception.PaymentPgTidConflictException;
import com.zslab.mall.product.exception.ProductImageNotFoundException;
import com.zslab.mall.product.exception.ProductHasOrderHistoryException;
import com.zslab.mall.product.exception.ProductInvalidStateException;
import com.zslab.mall.product.exception.ProductNotFoundException;
import com.zslab.mall.product.exception.ProductVariantNotFoundException;
import com.zslab.mall.product.exception.ProductVariantOptionConflictException;
import com.zslab.mall.refund.exception.RefundInvariantViolationException;
import com.zslab.mall.refund.exception.RefundNotFoundException;
import com.zslab.mall.seller.exception.SellerActivityInProgressException;
import com.zslab.mall.seller.exception.SellerBusinessNoDuplicateException;
import com.zslab.mall.seller.exception.SellerBankAccountInvalidStateException;
import com.zslab.mall.seller.exception.SellerBankAccountNotFoundException;
import com.zslab.mall.seller.exception.SellerBankAccountReferencedException;
import com.zslab.mall.seller.exception.SellerInvalidStateException;
import com.zslab.mall.seller.exception.SellerSuspendedException;
import com.zslab.mall.seller.exception.SellerLastOwnerException;
import com.zslab.mall.seller.exception.SellerMemberInvalidStateException;
import com.zslab.mall.seller.exception.SellerMemberNotFoundException;
import com.zslab.mall.seller.exception.SellerNotFoundException;
import com.zslab.mall.seller.exception.SellerOwnerRequiredException;
import com.zslab.mall.seller.exception.SellerUserAlreadyExistsException;
import com.zslab.mall.settlement.exception.SettlementAlreadyExistsException;
import com.zslab.mall.settlement.exception.SettlementBankAccountMissingException;
import com.zslab.mall.settlement.exception.SettlementInvalidStateException;
import com.zslab.mall.settlement.exception.SettlementNegativeNetException;
import com.zslab.mall.settlement.exception.SettlementNotFoundException;
import com.zslab.mall.settlement.exception.SettlementPeriodInvalidException;
import com.zslab.mall.user.exception.AddressNotFoundException;
import com.zslab.mall.user.exception.EmailAlreadyExistsException;
import com.zslab.mall.user.exception.MemberActivityInProgressException;
import com.zslab.mall.user.exception.MemberAdminRoleAssignedException;
import com.zslab.mall.user.exception.MemberAlreadyWithdrawnException;
import com.zslab.mall.user.exception.MemberPhoneMissingException;
import com.zslab.mall.user.exception.TemporaryPasswordDeliveryFailedException;
import com.zslab.mall.user.exception.UserNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
    private static final String CODE_RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
    private static final String CODE_METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    private static final String CODE_UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";
    private static final String CODE_UNAUTHENTICATED = "UNAUTHENTICATED";
    private static final String CODE_AUTHENTICATION_FAILED = "AUTHENTICATION_FAILED";
    private static final String CODE_ORDER_NOT_FOUND = "ORDER_NOT_FOUND";
    private static final String CODE_PRODUCT_NOT_FOUND = "PRODUCT_NOT_FOUND";
    private static final String CODE_PRODUCT_IMAGE_NOT_FOUND = "PRODUCT_IMAGE_NOT_FOUND";
    private static final String CODE_IDEMPOTENCY_KEY_IN_PROGRESS = "IDEMPOTENCY_KEY_IN_PROGRESS";
    private static final String CODE_PAYMENT_IN_PROGRESS = "PAYMENT_IN_PROGRESS";
    private static final String CODE_OPTIMISTIC_LOCK_FAILURE = "OPTIMISTIC_LOCK_FAILURE";
    private static final String CODE_PAYMENT_ALREADY_COMPLETED = "PAYMENT_ALREADY_COMPLETED";
    private static final String CODE_ORDER_NOT_PAYABLE = "ORDER_NOT_PAYABLE";
    private static final String CODE_ORDER_NOT_PENDING_PAYMENT = "ORDER_NOT_PENDING_PAYMENT";
    private static final String CODE_CHECKOUT_ITEM_MISMATCH = "CHECKOUT_ITEM_MISMATCH";
    private static final String CODE_CART_CHECKOUT_EMPTY = "CART_CHECKOUT_EMPTY";
    private static final String CODE_INVALID_CALLBACK = "INVALID_CALLBACK";
    private static final String CODE_PAYMENT_PG_TID_CONFLICT = "PAYMENT_PG_TID_CONFLICT";
    private static final String CODE_REFUND_NOT_FOUND = "REFUND_NOT_FOUND";
    private static final String CODE_REFUND_INVARIANT_VIOLATION = "REFUND_INVARIANT_VIOLATION";
    private static final String CODE_CLAIM_NOT_FOUND = "CLAIM_NOT_FOUND";
    private static final String CODE_DELIVERY_NOT_FOUND = "DELIVERY_NOT_FOUND";
    private static final String CODE_PRODUCT_VARIANT_NOT_FOUND = "PRODUCT_VARIANT_NOT_FOUND";
    private static final String CODE_CLAIM_STATE_INVALID = "CLAIM_STATE_INVALID";
    private static final String CODE_INVENTORY_INVARIANT_VIOLATION = "INVENTORY_INVARIANT_VIOLATION";
    private static final String CODE_DELIVERY_INVALID_STATE = "DELIVERY_INVALID_STATE";
    private static final String CODE_DELIVERY_TRACKING_NO_CONFLICT = "DELIVERY_TRACKING_NO_CONFLICT";
    private static final String CODE_ORDER_ITEM_INVALID_STATE = "ORDER_ITEM_INVALID_STATE";
    private static final String CODE_PAYMENT_NOT_FOUND = "PAYMENT_NOT_FOUND";
    private static final String CODE_PAYMENT_INVALID_STATE = "PAYMENT_INVALID_STATE";
    private static final String CODE_EMAIL_ALREADY_EXISTS = "EMAIL_ALREADY_EXISTS";
    private static final String CODE_USER_NOT_FOUND = "USER_NOT_FOUND";
    private static final String CODE_ADDRESS_NOT_FOUND = "ADDRESS_NOT_FOUND";
    private static final String CODE_SELLER_USER_ALREADY_EXISTS = "SELLER_USER_ALREADY_EXISTS";
    private static final String CODE_ADMIN_OPERATOR_ALREADY_EXISTS = "ADMIN_OPERATOR_ALREADY_EXISTS";
    private static final String CODE_CATEGORY_NOT_FOUND = "CATEGORY_NOT_FOUND";
    private static final String CODE_CATEGORY_DUPLICATE = "CATEGORY_DUPLICATE";
    private static final String CODE_CATEGORY_HAS_PRODUCTS = "CATEGORY_HAS_PRODUCTS";
    private static final String CODE_CART_ITEM_NOT_FOUND = "CART_ITEM_NOT_FOUND";
    private static final String CODE_CART_ITEM_NOT_PURCHASABLE = "CART_ITEM_NOT_PURCHASABLE";
    private static final String CODE_PRODUCT_VARIANT_OPTION_CONFLICT = "PRODUCT_VARIANT_OPTION_CONFLICT";
    private static final String CODE_PRODUCT_INVALID_STATE = "PRODUCT_INVALID_STATE";
    private static final String CODE_PRODUCT_HAS_ORDER_HISTORY = "PRODUCT_HAS_ORDER_HISTORY";
    private static final String CODE_SELLER_NOT_FOUND = "SELLER_NOT_FOUND";
    private static final String CODE_SELLER_INVALID_STATE = "SELLER_INVALID_STATE";
    private static final String CODE_SELLER_ACTIVITY_IN_PROGRESS = "SELLER_ACTIVITY_IN_PROGRESS";
    private static final String CODE_SELLER_BUSINESS_NO_DUPLICATE = "SELLER_BUSINESS_NO_DUPLICATE";
    private static final String CODE_SELLER_BANK_ACCOUNT_NOT_FOUND = "SELLER_BANK_ACCOUNT_NOT_FOUND";
    private static final String CODE_SELLER_BANK_ACCOUNT_REFERENCED = "SELLER_BANK_ACCOUNT_REFERENCED";
    private static final String CODE_SELLER_BANK_ACCOUNT_INVALID_STATE = "SELLER_BANK_ACCOUNT_INVALID_STATE";
    private static final String CODE_SELLER_MEMBER_NOT_FOUND = "SELLER_MEMBER_NOT_FOUND";
    private static final String CODE_SELLER_LAST_OWNER = "SELLER_LAST_OWNER";
    private static final String CODE_SELLER_MEMBER_INVALID_STATE = "SELLER_MEMBER_INVALID_STATE";
    private static final String CODE_SELLER_SUSPENDED = "SELLER_SUSPENDED";
    private static final String CODE_SELLER_OWNER_REQUIRED = "SELLER_OWNER_REQUIRED";
    private static final String CODE_FILE_NOT_FOUND = "FILE_NOT_FOUND";
    private static final String CODE_PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";
    private static final String CODE_FORBIDDEN = "FORBIDDEN";
    private static final String CODE_SETTLEMENT_PERIOD_INVALID = "SETTLEMENT_PERIOD_INVALID";
    private static final String CODE_SETTLEMENT_ALREADY_EXISTS = "SETTLEMENT_ALREADY_EXISTS";
    private static final String CODE_SETTLEMENT_NOT_FOUND = "SETTLEMENT_NOT_FOUND";
    private static final String CODE_SETTLEMENT_INVALID_STATE = "SETTLEMENT_INVALID_STATE";
    private static final String CODE_SETTLEMENT_NET_NEGATIVE = "SETTLEMENT_NET_NEGATIVE";
    private static final String CODE_SETTLEMENT_BANK_ACCOUNT_MISSING = "SETTLEMENT_BANK_ACCOUNT_MISSING";
    private static final String CODE_GRADE_POLICY_UNAVAILABLE = "GRADE_POLICY_UNAVAILABLE";
    private static final String CODE_ROLE_ASSIGNMENT_NOT_FOUND = "ROLE_ASSIGNMENT_NOT_FOUND";
    private static final String CODE_LAST_SUPER_ADMIN = "LAST_SUPER_ADMIN";
    private static final String CODE_MEMBER_ACTIVITY_IN_PROGRESS = "MEMBER_ACTIVITY_IN_PROGRESS";
    private static final String CODE_MEMBER_ALREADY_WITHDRAWN = "MEMBER_ALREADY_WITHDRAWN";
    private static final String CODE_MEMBER_PHONE_MISSING = "MEMBER_PHONE_MISSING";
    private static final String CODE_MEMBER_ADMIN_ROLE_ASSIGNED = "MEMBER_ADMIN_ROLE_ASSIGNED";
    private static final String CODE_TEMPORARY_PASSWORD_DELIVERY_FAILED = "TEMPORARY_PASSWORD_DELIVERY_FAILED";
    private static final String CODE_INTERNAL_ERROR = "INTERNAL_ERROR";

    // ===== 400 =====
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.joining(", "));
        ResponseEntity<ProblemDetail> response = build(HttpStatus.BAD_REQUEST, CODE_VALIDATION_FAILED,
                detail.isBlank() ? "요청 검증에 실패했습니다." : detail, request);
        // Track 76: 필드 단위 오류 목록(fieldErrors)을 병기해 FE가 폼 필드별로 표시한다. detail 문자열은 기존 계약대로 유지한다.
        List<Map<String, String>> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> Map.of(
                        "field", fieldError.getField(),
                        "message", fieldError.getDefaultMessage() != null ? fieldError.getDefaultMessage() : ""))
                .toList();
        response.getBody().setProperty("fieldErrors", fieldErrors);
        return response;
    }

    // Track 85: 필수 쿼리 파라미터 누락(MissingServletRequestParameterException·예: 정산 목록 year/month)도 400 MALFORMED_REQUEST.
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MalformedRequestException.class, IllegalArgumentException.class, MissingServletRequestPartException.class,
            MissingServletRequestParameterException.class})
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

    @ExceptionHandler(SelfRoleRevocationException.class)
    public ResponseEntity<ProblemDetail> handleSelfRoleRevocation(
            SelfRoleRevocationException exception, HttpServletRequest request) {
        // Track 53: SUPER_ADMIN 자기 강등 차단(403). 권한은 충분하나 자기 대상 회수는 정책상 영구 금지(재시도 무의미)라
        // SuperAdminRequiredException 선례와 동일하게 서비스 계층 도메인 403(code=FORBIDDEN)으로 매핑한다.
        log.warn("[Auth] 자기 역할 회수 차단(403): {}", exception.getMessage());
        return build(HttpStatus.FORBIDDEN, CODE_FORBIDDEN, exception.getMessage(), request);
    }

    @ExceptionHandler(SellerSuspendedException.class)
    public ResponseEntity<ProblemDetail> handleSellerSuspended(
            SellerSuspendedException exception, HttpServletRequest request) {
        // Track 90-A: 정지(SUSPENDED) 셀러의 쓰기 요청. SUSPENDED는 유효한 세션 상태이며 해당 행위만 금지되므로, 인증 실패(401)가
        // 아니라 인가 거부(403)로 응답한다. 전용 코드로 FORBIDDEN 범용 코드와 구분해 FE가 정지 안내를 분기할 수 있게 한다.
        log.warn("[Seller] 정지 셀러 쓰기 차단(403): {}", exception.getMessage());
        return build(HttpStatus.FORBIDDEN, CODE_SELLER_SUSPENDED, exception.getMessage(), request);
    }

    @ExceptionHandler(SellerOwnerRequiredException.class)
    public ResponseEntity<ProblemDetail> handleSellerOwnerRequired(
            SellerOwnerRequiredException exception, HttpServletRequest request) {
        // Track 90-D-3: SELLER_OWNER 한정 쓰기를 MANAGER·STAFF가 호출. 세션은 유효하고 역할상 해당 행위만 금지라 SELLER_SUSPENDED와
        // 같은 403이며, 전용 코드로 FE가 "대표만 가능" 안내를 분기한다.
        log.warn("[Seller] OWNER 한정 쓰기 차단(403): {}", exception.getMessage());
        return build(HttpStatus.FORBIDDEN, CODE_SELLER_OWNER_REQUIRED, exception.getMessage(), request);
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

    @ExceptionHandler(StoredFileNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleStoredFileNotFound(
            StoredFileNotFoundException exception, HttpServletRequest request) {
        // Track 77: 업로드 파일 서빙 미존재·루트 밖 경로(traversal) 은닉(404).
        return build(HttpStatus.NOT_FOUND, CODE_FILE_NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(SellerNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleSellerNotFound(
            SellerNotFoundException exception, HttpServletRequest request) {
        // Track 76: 관리자 상품 등록·목록 필터의 sellerPublicId 미존재(404).
        return build(HttpStatus.NOT_FOUND, CODE_SELLER_NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(ProductImageNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleProductImageNotFound(
            ProductImageNotFoundException exception, HttpServletRequest request) {
        // Track 59 BL-6: 셀러 이미지 대표지정·삭제 시 대상 미존재(404). 타 판매자 소유도 동일 404 은닉(2-hop·AddressNotFound 선례).
        return build(HttpStatus.NOT_FOUND, CODE_PRODUCT_IMAGE_NOT_FOUND, exception.getMessage(), request);
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

    @ExceptionHandler(AddressNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleAddressNotFound(
            AddressNotFoundException exception, HttpServletRequest request) {
        // Track 58 BL-4: 배송지 수정·삭제·기본설정 시 대상 미존재(404). 타 user 소유도 동일 404 은닉(CartItem 선례).
        return build(HttpStatus.NOT_FOUND, CODE_ADDRESS_NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(SettlementNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleSettlementNotFound(
            SettlementNotFoundException exception, HttpServletRequest request) {
        // Track 49: 정산 전이(confirm·pay) 시 settlementId 미존재(404). 500 fallback으로 새는 트랩 차단.
        return build(HttpStatus.NOT_FOUND, CODE_SETTLEMENT_NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(RoleAssignmentNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleRoleAssignmentNotFound(
            RoleAssignmentNotFoundException exception, HttpServletRequest request) {
        // Track 53: 권한 회수 delete 0 row(대상 역할 미보유·User 미존재·경합 선삭제 통합 은닉·404). 존재 여부 비노출.
        return build(HttpStatus.NOT_FOUND, CODE_ROLE_ASSIGNMENT_NOT_FOUND, exception.getMessage(), request);
    }

    // ===== 413 =====
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleMaxUploadSize(
            MaxUploadSizeExceededException exception, HttpServletRequest request) {
        // Track 77: multipart 파일당/요청당 한도 초과(spring.servlet.multipart). 앱 단 파일별 검증 이전에 컨테이너가 거부한다.
        return build(HttpStatus.PAYLOAD_TOO_LARGE, CODE_PAYLOAD_TOO_LARGE,
                "업로드 용량 한도를 초과했습니다(파일당 10MB·요청당 20장).", request);
    }

    // ===== 409 =====
    @ExceptionHandler(ProductHasOrderHistoryException.class)
    public ResponseEntity<ProblemDetail> handleProductHasOrderHistory(
            ProductHasOrderHistoryException exception, HttpServletRequest request) {
        // Track 76: 주문 이력 있는 상품 삭제 차단(409). detail에 판매중지 대안을 안내한다.
        return build(HttpStatus.CONFLICT, CODE_PRODUCT_HAS_ORDER_HISTORY, exception.getMessage(), request);
    }

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

    @ExceptionHandler(LastSuperAdminRevocationException.class)
    public ResponseEntity<ProblemDetail> handleLastSuperAdminRevocation(
            LastSuperAdminRevocationException exception, HttpServletRequest request) {
        // Track 53: 마지막 SUPER_ADMIN 회수 차단(409). SUPER_ADMIN 0명 시 시스템 락아웃 방지·SUPER_ADMIN 집합 상태와 충돌.
        log.warn("[Auth] 마지막 SUPER_ADMIN 회수 차단(409): {}", exception.getMessage());
        return build(HttpStatus.CONFLICT, CODE_LAST_SUPER_ADMIN, exception.getMessage(), request);
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

    @ExceptionHandler(CategoryHasProductsException.class)
    public ResponseEntity<ProblemDetail> handleCategoryHasProducts(
            CategoryHasProductsException exception, HttpServletRequest request) {
        // Track 89-C: 활성 상품이 연결된 카테고리 soft-delete 차단(409). FK RESTRICT는 하드 삭제만 막으므로 서비스 가드 결과를 변환한다.
        log.warn("[Category] 상품 연결 카테고리 삭제 차단(409): {}", exception.getMessage());
        return build(HttpStatus.CONFLICT, CODE_CATEGORY_HAS_PRODUCTS, exception.getMessage(), request);
    }

    @ExceptionHandler(DeliveryTrackingNoConflictException.class)
    public ResponseEntity<ProblemDetail> handleDeliveryTrackingNoConflict(
            DeliveryTrackingNoConflictException exception, HttpServletRequest request) {
        // Track 89-B: 송장 정정 시 타 배송 행과 송장번호 중복(409·uk_delivery_tracking_no). 서비스 사전 검사로 UK 위반 500 차단.
        log.warn("[Delivery] 송장번호 중복(409): {}", exception.getMessage());
        return build(HttpStatus.CONFLICT, CODE_DELIVERY_TRACKING_NO_CONFLICT, exception.getMessage(), request);
    }

    @ExceptionHandler(SettlementAlreadyExistsException.class)
    public ResponseEntity<ProblemDetail> handleSettlementAlreadyExists(
            SettlementAlreadyExistsException exception, HttpServletRequest request) {
        // Track 48 P3: 동일 seller·기간 정산 중복(409·uk_settlement_seller_period). 동시 배치 실행 레이스 백스톱(선확인은 skip).
        log.warn("[Settlement] 정산 중복(409): {}", exception.getMessage());
        return build(HttpStatus.CONFLICT, CODE_SETTLEMENT_ALREADY_EXISTS, exception.getMessage(), request);
    }

    @ExceptionHandler(SellerActivityInProgressException.class)
    public ResponseEntity<ProblemDetail> handleSellerActivityInProgress(
            SellerActivityInProgressException exception, HttpServletRequest request) {
        // Track 89-D: 미지급 정산·진행 중 품목·활성 클레임 보유 판매자 종료 차단(409). 어느 가드에 몇 건인지 blocks[{code,count}]로 병기한다.
        log.warn("[Seller] 종료 차단(409): {}", exception.getMessage());
        ResponseEntity<ProblemDetail> response =
                build(HttpStatus.CONFLICT, CODE_SELLER_ACTIVITY_IN_PROGRESS, exception.getMessage(), request);
        List<Map<String, Object>> blocks = exception.getBlocks().stream()
                .map(block -> Map.<String, Object>of("code", block.code().name(), "count", block.count()))
                .toList();
        response.getBody().setProperty("blocks", blocks);
        return response;
    }

    @ExceptionHandler(SellerBusinessNoDuplicateException.class)
    public ResponseEntity<ProblemDetail> handleSellerBusinessNoDuplicate(
            SellerBusinessNoDuplicateException exception, HttpServletRequest request) {
        // Track 89-D: 입점·정보 수정 시 사업자번호 중복(409·uk_seller_business_no·SLR-1). 선검사 + flush 위반 변환.
        log.warn("[Seller] 사업자번호 중복(409): {}", exception.getMessage());
        return build(HttpStatus.CONFLICT, CODE_SELLER_BUSINESS_NO_DUPLICATE, exception.getMessage(), request);
    }

    @ExceptionHandler(MemberActivityInProgressException.class)
    public ResponseEntity<ProblemDetail> handleMemberActivityInProgress(
            MemberActivityInProgressException exception, HttpServletRequest request) {
        // Track 84: 진행 중 주문·활성 클레임 보유 회원 탈퇴 차단(409·셀프·관리자 공통 MemberActivityChecker).
        log.warn("[Member] 탈퇴 차단(409): {}", exception.getMessage());
        return build(HttpStatus.CONFLICT, CODE_MEMBER_ACTIVITY_IN_PROGRESS, exception.getMessage(), request);
    }

    @ExceptionHandler(MemberAlreadyWithdrawnException.class)
    public ResponseEntity<ProblemDetail> handleMemberAlreadyWithdrawn(
            MemberAlreadyWithdrawnException exception, HttpServletRequest request) {
        // Track 84: 탈퇴 회원 수정·재탈퇴·임시 비밀번호 발급 차단(409·withdrawn_at 상태 충돌).
        log.warn("[Member] 탈퇴 회원 처리 차단(409): {}", exception.getMessage());
        return build(HttpStatus.CONFLICT, CODE_MEMBER_ALREADY_WITHDRAWN, exception.getMessage(), request);
    }

    // ===== 422 =====
    @ExceptionHandler(MemberPhoneMissingException.class)
    public ResponseEntity<ProblemDetail> handleMemberPhoneMissing(
            MemberPhoneMissingException exception, HttpServletRequest request) {
        // Track 84: 연락처 없는 회원 임시 비밀번호 발급 불가(422·SMS 수신처 부재).
        log.warn("[Member] 임시 비밀번호 발급 불가·연락처 없음(422): {}", exception.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, CODE_MEMBER_PHONE_MISSING, exception.getMessage(), request);
    }

    @ExceptionHandler(MemberAdminRoleAssignedException.class)
    public ResponseEntity<ProblemDetail> handleMemberAdminRoleAssigned(
            MemberAdminRoleAssignedException exception, HttpServletRequest request) {
        // D-204: 관리자 역할 보유 회원 임시 비밀번호 발급 차단(422·관리자 영역 변경 강제 부재·권한 해제 후 재발급).
        log.warn("[Member] 임시 비밀번호 발급 불가·관리자 역할 보유(422): {}", exception.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, CODE_MEMBER_ADMIN_ROLE_ASSIGNED, exception.getMessage(), request);
    }

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

    @ExceptionHandler(OrderNotPendingPaymentException.class)
    public ResponseEntity<ProblemDetail> handleOrderNotPendingPayment(
            OrderNotPendingPaymentException exception, HttpServletRequest request) {
        // FE-12c-2: 미결제 종료(PAYMENT_EXPIRED)·완료 등 비-PENDING_PAYMENT 주문의 결제 시작 차단(422).
        // 상태 위반이므로 PaymentAlreadyCompletedException 선례와 동일 계열(422)로 매핑한다(400 형식오류와 구분).
        log.warn("[Payment] 결제 시작 불가 주문 상태(422): {}", exception.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, CODE_ORDER_NOT_PENDING_PAYMENT, exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidCallbackException.class)
    public ResponseEntity<ProblemDetail> handleInvalidCallback(
            InvalidCallbackException exception, HttpServletRequest request) {
        log.warn("[PaymentWebhook] 콜백 거부(422): {}", exception.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, CODE_INVALID_CALLBACK, exception.getMessage(), request);
    }

    @ExceptionHandler(PaymentPgTidConflictException.class)
    public ResponseEntity<ProblemDetail> handlePaymentPgTidConflict(
            PaymentPgTidConflictException exception, HttpServletRequest request) {
        // Track 93 D-198: 콜백 pgTid가 다른 결제 행과 충돌(409·uk_payment_provider_pg_tid). 500 fallback으로 새던 트랩 차단.
        log.warn("[PaymentWebhook] pgTid 충돌(409): {}", exception.getMessage());
        return build(HttpStatus.CONFLICT, CODE_PAYMENT_PG_TID_CONFLICT, exception.getMessage(), request);
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

    @ExceptionHandler(PaymentInvalidStateException.class)
    public ResponseEntity<ProblemDetail> handlePaymentInvalidState(
            PaymentInvalidStateException exception, HttpServletRequest request) {
        // D-172 보충: 환불 완료 콜백 동기 체인의 결제 취소 전이 불가(비PAID 등). 500 fallback 차단·422 매핑(DeliveryInvalidStateException 선례).
        log.warn("[Payment] 결제 상태 전이 위반(422): {}", exception.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, CODE_PAYMENT_INVALID_STATE, exception.getMessage(), request);
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

    @ExceptionHandler(SettlementNegativeNetException.class)
    public ResponseEntity<ProblemDetail> handleSettlementNegativeNet(
            SettlementNegativeNetException exception, HttpServletRequest request) {
        // Track 85: net 음수 정산 지급 차단(차감 이월 정책 미도입·422). 정상처리(CONFIRMED)는 허용·지급만 차단.
        log.warn("[Settlement] 음수 정산 지급 차단(422): {}", exception.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, CODE_SETTLEMENT_NET_NEGATIVE, exception.getMessage(), request);
    }

    @ExceptionHandler(SettlementBankAccountMissingException.class)
    public ResponseEntity<ProblemDetail> handleSettlementBankAccountMissing(
            SettlementBankAccountMissingException exception, HttpServletRequest request) {
        // Track 85: 지급 시점 주 정산계좌 부재(422). 계좌는 생성 조건이 아니라 지급 조건.
        log.warn("[Settlement] 주 정산계좌 부재 지급 차단(422): {}", exception.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, CODE_SETTLEMENT_BANK_ACCOUNT_MISSING, exception.getMessage(), request);
    }

    @ExceptionHandler(SellerInvalidStateException.class)
    public ResponseEntity<ProblemDetail> handleSellerInvalidState(
            SellerInvalidStateException exception, HttpServletRequest request) {
        // Track 89-D: 판매자 상태 위반(불법 전이·같은 상태 재요청·비-ACTIVE 판매자 상품 등록) 422(ProductInvalidStateException 선례).
        log.warn("[Seller] 판매자 상태 위반(422): {}", exception.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, CODE_SELLER_INVALID_STATE, exception.getMessage(), request);
    }

    @ExceptionHandler(SellerBankAccountNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleSellerBankAccountNotFound(
            SellerBankAccountNotFoundException exception, HttpServletRequest request) {
        // Track 89-F: 정산계좌 미존재·타 셀러 소속(존재 은닉) 404.
        log.warn("[SellerBankAccount] 계좌 미존재(404): {}", exception.getMessage());
        return build(HttpStatus.NOT_FOUND, CODE_SELLER_BANK_ACCOUNT_NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(SellerBankAccountReferencedException.class)
    public ResponseEntity<ProblemDetail> handleSellerBankAccountReferenced(
            SellerBankAccountReferencedException exception, HttpServletRequest request) {
        // Track 89-F: 정산 지급 스냅샷이 참조하는 계좌 행 수정 차단(409·이력 변조 방지·D-188).
        log.warn("[SellerBankAccount] 정산 참조 행 수정 차단(409): {}", exception.getMessage());
        return build(HttpStatus.CONFLICT, CODE_SELLER_BANK_ACCOUNT_REFERENCED, exception.getMessage(), request);
    }

    @ExceptionHandler(SellerBankAccountInvalidStateException.class)
    public ResponseEntity<ProblemDetail> handleSellerBankAccountInvalidState(
            SellerBankAccountInvalidStateException exception, HttpServletRequest request) {
        // Track 89-F: 이미 주 계좌인 행의 전환 재요청(422·같은 상태 재요청 관습).
        log.warn("[SellerBankAccount] 계좌 상태 위반(422): {}", exception.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, CODE_SELLER_BANK_ACCOUNT_INVALID_STATE, exception.getMessage(), request);
    }

    @ExceptionHandler(SellerMemberNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleSellerMemberNotFound(
            SellerMemberNotFoundException exception, HttpServletRequest request) {
        // Track 89-G: 셀러 구성원 아님(타 셀러 소속 은닉) 404.
        log.warn("[SellerMember] 구성원 미존재(404): {}", exception.getMessage());
        return build(HttpStatus.NOT_FOUND, CODE_SELLER_MEMBER_NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(SellerLastOwnerException.class)
    public ResponseEntity<ProblemDetail> handleSellerLastOwner(
            SellerLastOwnerException exception, HttpServletRequest request) {
        // Track 89-G: 마지막 활성 SELLER_OWNER 제거·강등 차단(409·LAST_SUPER_ADMIN 동형).
        log.warn("[SellerMember] 마지막 OWNER 차단(409): {}", exception.getMessage());
        return build(HttpStatus.CONFLICT, CODE_SELLER_LAST_OWNER, exception.getMessage(), request);
    }

    @ExceptionHandler(SellerMemberInvalidStateException.class)
    public ResponseEntity<ProblemDetail> handleSellerMemberInvalidState(
            SellerMemberInvalidStateException exception, HttpServletRequest request) {
        // Track 89-G: 같은 역할 재요청(422·같은 상태 재요청 관습).
        log.warn("[SellerMember] 구성원 상태 위반(422): {}", exception.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, CODE_SELLER_MEMBER_INVALID_STATE, exception.getMessage(), request);
    }

    @ExceptionHandler(ProductInvalidStateException.class)
    public ResponseEntity<ProblemDetail> handleProductInvalidState(
            ProductInvalidStateException exception, HttpServletRequest request) {
        // Track 50: 상품 승인·거부 전이 불가 상태(PENDING 아님). 500 fallback 차단·422 매핑(SettlementInvalidStateException 선례).
        log.warn("[Product] 상품 상태 위반(422): {}", exception.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, CODE_PRODUCT_INVALID_STATE, exception.getMessage(), request);
    }

    @ExceptionHandler(EmptyCartCheckoutException.class)
    public ResponseEntity<ProblemDetail> handleEmptyCartCheckout(
            EmptyCartCheckoutException exception, HttpServletRequest request) {
        // Track 41 β: 장바구니 결제 시 selected 품목 0개(빈 주문 선가드·ORD-1 도달 전 차단). well-formed 요청·업무 전제 실패(422·클라 교정).
        return build(HttpStatus.UNPROCESSABLE_ENTITY, CODE_CART_CHECKOUT_EMPTY, exception.getMessage(), request);
    }

    @ExceptionHandler(CartItemNotPurchasableException.class)
    public ResponseEntity<ProblemDetail> handleCartItemNotPurchasable(
            CartItemNotPurchasableException exception, HttpServletRequest request) {
        // Track 71: 담기 대상이 판매중지·품절 등 구매 불가(422·클라 교정 가능[다른 옵션 선택]).
        log.warn("[Cart] 구매 불가 담기 거부(422): {}", exception.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, CODE_CART_ITEM_NOT_PURCHASABLE, exception.getMessage(), request);
    }

    // ===== 500 (도메인) =====
    @ExceptionHandler(GradePolicyUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleGradePolicyUnavailable(
            GradePolicyUnavailableException exception, HttpServletRequest request) {
        // Track 51: AUTO 등급 산정 중 활성 정책 구간 미매칭(시드 [0,MAX] 커버 전제·비활성/손상 = 서버 설정 오류·500).
        // 무분류 fallback으로 새지 않도록 전용 code로 매핑·운영 진단성 확보(NotFound 404 관례와 구분).
        log.error("[Grade] 활성 등급 정책 미매칭(500): {}", exception.getMessage());
        return build(HttpStatus.INTERNAL_SERVER_ERROR, CODE_GRADE_POLICY_UNAVAILABLE, exception.getMessage(), request);
    }

    // ===== 502 =====
    @ExceptionHandler(TemporaryPasswordDeliveryFailedException.class)
    public ResponseEntity<ProblemDetail> handleTemporaryPasswordDeliveryFailed(
            TemporaryPasswordDeliveryFailedException exception, HttpServletRequest request) {
        // Track 84: 임시 비밀번호 SMS 발송 실패(502·외부 채널 오류). 발급 트랜잭션은 롤백돼 기존 비밀번호가 유지된다.
        log.warn("[Member] 임시 비밀번호 SMS 발송 실패·롤백(502): {}", exception.getMessage());
        return build(HttpStatus.BAD_GATEWAY, CODE_TEMPORARY_PASSWORD_DELIVERY_FAILED, exception.getMessage(), request);
    }

    // ===== Spring MVC 표준 예외(Track 95 D-201·LT-27) =====
    // 매핑 부재·미지원 메서드·미지원 Content-Type은 Exception catch-all로 500 INTERNAL_ERROR + ERROR 스택이 되던 트랩. 공개(permitAll) 경로에서는
    // 무인증으로 재현된다. detail은 요청 경로·내부 메시지를 노출하지 않는 고정 문구, 로그는 4xx 관례(warn·스택 없음).
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResourceFound(
            NoResourceFoundException exception, HttpServletRequest request) {
        log.warn("[Web] 매핑 부재(404): {} {}", request.getMethod(), request.getRequestURI());
        return build(HttpStatus.NOT_FOUND, CODE_RESOURCE_NOT_FOUND, "요청한 리소스를 찾을 수 없습니다.", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
        log.warn("[Web] 미지원 메서드(405): {} {}", request.getMethod(), request.getRequestURI());
        return build(HttpStatus.METHOD_NOT_ALLOWED, CODE_METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다.", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
        log.warn("[Web] 미지원 Content-Type(415): {} {} contentType={}", request.getMethod(), request.getRequestURI(),
                exception.getContentType());
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, CODE_UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type입니다.", request);
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
