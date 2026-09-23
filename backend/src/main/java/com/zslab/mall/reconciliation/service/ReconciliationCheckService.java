package com.zslab.mall.reconciliation.service;

import com.zslab.mall.order.service.OrderService;
import com.zslab.mall.reconciliation.repository.ReconciliationCandidate;
import com.zslab.mall.reconciliation.repository.ReconciliationCandidateRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 점검 스케줄러의 조회·건별 기록(Track 104-2 D-216·invariants P6). 업무 데이터(주문·결제·환불·클레임·배송)는 읽기만 하고 불일치 행만 쓴다.
 *
 * <p><b>건별 재확인</b>: 후보 목록은 락 없는 조회라 기록 시점엔 이미 풀렸을 수 있다. {@link #recordIfStillPresent}는 주문 쓰기 락(104-1 규약·
 * 첫 DB 접근)을 잡은 뒤 같은 조회를 대상 1건으로 다시 돌려 여전히 해당할 때만 기록한다.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationCheckService {

    /** 불일치 세부의 기록 주체(점검 스케줄러). */
    private static final String DETECTED_BY_SYSTEM = "SYSTEM";

    /** 대상 1건 재확인에는 커서를 쓰지 않는다(id 0보다 큰 전부 = 제한 없음). */
    private static final long NO_CURSOR = 0L;

    private final ReconciliationCandidateRepository candidateRepository;
    private final ReconciliationIssueRecorder reconciliationIssueRecorder;
    private final OrderService orderService;

    /** 패턴의 미기록 후보 중 대상 id가 {@code afterId}보다 큰 것을 id 순으로 최대 {@code limit}건 조회한다(커서 페이지). */
    @Transactional(readOnly = true)
    public List<ReconciliationCandidate> findCandidates(ReconciliationCheckPattern pattern, long afterId, int limit) {
        return query(pattern, null, afterId, limit);
    }

    /**
     * 후보 1건을 주문 락 아래에서 다시 판정해 여전히 해당하면 기록한다(각자 독립 트랜잭션).
     *
     * @return 새로 기록했으면 true, 그사이 풀렸거나 이미 기록된 대상이면 false
     */
    @Transactional
    public boolean recordIfStillPresent(ReconciliationCheckPattern pattern, ReconciliationCandidate candidate) {
        orderService.lockForWrite(candidate.getOrderId());
        List<ReconciliationCandidate> current = query(pattern, candidate.getTargetId(), NO_CURSOR, 1);
        if (current.isEmpty()) {
            return false;
        }
        ReconciliationCandidate fresh = current.get(0);
        return reconciliationIssueRecorder.record(pattern.issueType(), pattern.dedupePrefix() + fresh.getTargetId(),
                refsOf(fresh), detailOf(pattern, fresh));
    }

    private List<ReconciliationCandidate> query(ReconciliationCheckPattern pattern, Long targetId, long afterId, int limit) {
        String issueType = pattern.issueType().name();
        String prefix = pattern.dedupePrefix();
        return switch (pattern) {
            case PAYMENT_CANCELLED_WITHOUT_REFUND ->
                    candidateRepository.findCancelledPaymentsWithoutRefund(issueType, prefix, targetId, afterId, limit);
            case FULL_REFUND_PAYMENT_NOT_CANCELLED ->
                    candidateRepository.findFullyRefundedPaidPayments(issueType, prefix, targetId, afterId, limit);
            case FULL_REFUND_WITH_CONFIRMED_ITEM ->
                    candidateRepository.findFullyRefundedPaymentsWithConfirmedItem(issueType, prefix, targetId, afterId, limit);
            case CLAIM_COMPLETED_ITEM_NOT_TRANSITIONED, CLAIM_REJECTED_ITEM_NOT_RESTORED ->
                    candidateRepository.findClaimsWithStaleItem(issueType, prefix, pattern.claimStatus().name(),
                            pattern.staleItemStatusNames(), targetId, afterId, limit);
            case SHIPPED_ITEM_NOT_TRANSITIONED, DELIVERED_ITEM_NOT_TRANSITIONED ->
                    candidateRepository.findOutboundDeliveriesWithStaleItem(issueType, prefix, pattern.deliveryStatus().name(),
                            pattern.staleItemStatusNames(), targetId, afterId, limit);
        };
    }

    private ReconciliationIssueRefs refsOf(ReconciliationCandidate candidate) {
        return new ReconciliationIssueRefs(candidate.getOrderId(), candidate.getPaymentId(), null, candidate.getClaimId(),
                candidate.getDeliveryId(), candidate.getPgTid(), null);
    }

    /** 세부 — 기록 주체·패턴·조회 시점 상태(null 값은 담지 않는다). */
    private Map<String, Object> detailOf(ReconciliationCheckPattern pattern, ReconciliationCandidate candidate) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", pattern.name());
        detail.put("detectedBy", DETECTED_BY_SYSTEM);
        putIfPresent(detail, "paymentStatus", candidate.getPaymentStatus());
        putIfPresent(detail, "paymentAmount", candidate.getPaymentAmount());
        putIfPresent(detail, "refundedAmount", candidate.getRefundedAmount());
        putIfPresent(detail, "orderItemId", candidate.getOrderItemId());
        putIfPresent(detail, "itemStatus", candidate.getItemStatus());
        putIfPresent(detail, "claimType", candidate.getClaimType());
        putIfPresent(detail, "claimStatus", candidate.getClaimStatus());
        putIfPresent(detail, "deliveryStatus", candidate.getDeliveryStatus());
        return detail;
    }

    private static void putIfPresent(Map<String, Object> detail, String key, Object value) {
        if (value != null) {
            detail.put(key, value);
        }
    }
}
