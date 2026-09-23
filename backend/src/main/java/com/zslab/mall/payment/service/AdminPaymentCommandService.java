package com.zslab.mall.payment.service;

import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.order.service.OrderService;
import com.zslab.mall.payment.entity.Payment;
import com.zslab.mall.payment.enums.PaymentStatus;
import com.zslab.mall.payment.exception.PaymentNotFoundException;
import com.zslab.mall.payment.repository.PaymentRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 결제 명령(Track 89-A). D-113 수동 결제 취소(fallback)에 사유·감사 기록을 붙인다 — 주문 상세 화면이 유일한 진입점이 되면서
 * "왜 수동 보정했는가"를 남길 곳이 필요해졌다({@code AdminOrderCancelService}의 ORDER 감사 선례·D-139).
 *
 * <p>전이 규칙(전액 환불 가드·CANCELLED 멱등 NO-OP)은 {@link PaymentService#markCancelledByAdmin}이 그대로 담당하고, 본 서비스는
 * publicId 해소·감사 적재만 한다. NO-OP(상태 불변)에는 감사 행을 남기지 않는다(변경 없음·AuditRecorder도 diff 없으면 skip).
 */
@Service
@Transactional
@RequiredArgsConstructor
public class AdminPaymentCommandService {

    private final PaymentService paymentService;
    private final PaymentRepository paymentRepository;
    private final AuditRecorder auditRecorder;
    private final OrderService orderService;

    /**
     * 수동 결제 취소. 상태가 실제로 바뀐 경우에만 감사(UPDATE·PAYMENT·before status·after status+reason)를 남긴다.
     *
     * @throws PaymentNotFoundException paymentPublicId 미존재(404)
     */
    public Payment markCancelled(String paymentPublicId, String reason, AuditContext auditContext) {
        // Track 104-1 D-215(P5): 결제를 적재하기 전에 주문 쓰기 락을 먼저 잡는다.
        paymentRepository.findOrderIdByPublicId(paymentPublicId).ifPresent(orderService::lockForWrite);
        Payment payment = paymentRepository.findByPublicId(paymentPublicId)
                .orElseThrow(() -> new PaymentNotFoundException("결제를 찾을 수 없습니다: publicId=" + paymentPublicId));
        PaymentStatus before = payment.getStatus();
        Payment cancelled = paymentService.markCancelledByAdmin(payment.getId());
        if (cancelled.getStatus() != before) {
            auditRecorder.record(auditContext, AuditLogAction.UPDATE, PolymorphicTargetType.PAYMENT, payment.getId(),
                    Map.of("status", before.name()),
                    Map.of("status", cancelled.getStatus().name(), "reason", reason));
        }
        return cancelled;
    }
}
