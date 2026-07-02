package com.zslab.mall.payment.controller;

import com.zslab.mall.common.auth.AdminActorResolver;
import com.zslab.mall.payment.controller.response.AdminPaymentMarkCancelledResponse;
import com.zslab.mall.payment.entity.Payment;
import com.zslab.mall.payment.exception.PaymentNotFoundException;
import com.zslab.mall.payment.repository.PaymentRepository;
import com.zslab.mall.payment.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin 액터용 Payment REST 컨트롤러(Track 28 D-113). 운영자 수동 결제 취소 1 endpoint를 노출한다.
 *
 * <p>클래스 레벨 base path를 두지 않고 메서드 절대경로를 부여한다(D-105 §2 Q2 옵션 A·{@link com.zslab.mall.inventory.controller.AdminInventoryController}
 * ·{@link com.zslab.mall.delivery.controller.AdminDeliveryController}·{@link com.zslab.mall.refund.controller.AdminRefundController} 선례 정합).
 * Admin 식별은 {@code X-Admin-Id} 헤더 stub이다(D-93·{@link AdminActorResolver}).
 *
 * <p>HTTP 책임만 가진다(D-40 β′): 액터 해소·publicId→id 해소·Service 위임·응답 조립만 수행한다. Admin은 전체 접근이므로
 * 권한 검증 단락이 부재하며(D-93 Q3·Q5) paymentPublicId 미존재만 404({@link PaymentNotFoundException})다.
 *
 * <p><b>용도(D-113)</b>: {@code Refund.COMPLETED → Payment CANCELLED} 자동 전이({@code PaymentRefundCompletedHandler})가
 * 유실됐을 때 운영자가 전액 환불 완료 결제를 수동으로 CANCELLED 보정하는 fallback 경로다. 전액 환불 가드(D-71)·CANCELLED
 * 멱등 NO-OP는 {@link PaymentService#markCancelledByAdmin} → {@code markCancelled} 도메인 위임 책임이다(강제 취소 아님).
 */
@RestController
public class AdminPaymentController {

    private final PaymentService paymentService;
    private final PaymentRepository paymentRepository;
    private final AdminActorResolver adminActorResolver;

    public AdminPaymentController(
            PaymentService paymentService,
            PaymentRepository paymentRepository,
            AdminActorResolver adminActorResolver) {
        this.paymentService = paymentService;
        this.paymentRepository = paymentRepository;
        this.adminActorResolver = adminActorResolver;
    }

    /**
     * Admin 수동 결제 취소(D-113). 미존재 paymentPublicId → 404. 전액 환불 완료 시 PAID→CANCELLED, 부분·미환불은 상태 유지
     * NO-OP(D-71 가드 상속), 이미 CANCELLED면 멱등 NO-OP다. NO-OP도 예외 없이 200 + 현재 결제 상태를 반환한다.
     */
    @PostMapping("/api/v1/admin/payments/{paymentPublicId}/mark-cancelled")
    public AdminPaymentMarkCancelledResponse markCancelled(
            @PathVariable String paymentPublicId,
            HttpServletRequest httpRequest) {
        // X-Admin-Id 존재·형식 검증만 수행한다(전체 접근·식별자 미사용·D-93 Q3). 누락 401·형식 오류 400.
        adminActorResolver.resolve(httpRequest);
        Payment payment = paymentRepository.findByPublicId(paymentPublicId)
                .orElseThrow(() -> new PaymentNotFoundException("결제를 찾을 수 없습니다: publicId=" + paymentPublicId));
        // markCancelledByAdmin은 @Transactional 종료 후 취소 반영 Payment를 반환한다. 스칼라 status만 읽으므로 OSIV off에서도 재조회 불요.
        Payment cancelled = paymentService.markCancelledByAdmin(payment.getId());
        return AdminPaymentMarkCancelledResponse.from(paymentPublicId, cancelled);
    }
}
