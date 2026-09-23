package com.zslab.mall.reconciliation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.reconciliation.entity.ReconciliationIssue;
import com.zslab.mall.reconciliation.enums.ReconciliationIssueStatus;
import com.zslab.mall.reconciliation.enums.ReconciliationIssueType;
import com.zslab.mall.reconciliation.repository.ReconciliationIssueRepository;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 불일치 기록 단일 진입점(Track 104-2 D-216·invariants P1·P6). PG 통지 충돌·환불 완료 시점 검사·점검 스케줄러가 모두 이 메서드로 기록한다.
 *
 * <p><b>트랜잭션</b>: 호출자 트랜잭션에 참여한다(MANDATORY). PG 통지 충돌은 예외 없이 정상 종료하므로 기록이 통지 처리와 함께 커밋되고,
 * 주문 id가 있는 경로는 104-1 규약대로 주문 쓰기 락을 쥔 뒤에 부른다.
 *
 * <p><b>멱등</b>: (유형, 중복 키)가 이미 있으면 기존 행을 그대로 둔다({@code insertIfAbsent}). PG 재전송·점검 재실행이 행을 늘리지 않는다.
 * 해결된 행도 키를 유지하므로 같은 불일치는 다시 열리지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationIssueRecorder {

    /** 재전송 매칭 자동 해소 메모(결정 1 지정 문구). */
    static final String RESOLVED_BY_RETRY_MEMO = "재전송 매칭으로 자동 해소";
    private static final String AUDIT_FIELD_STATUS = "status";
    private static final String AUDIT_FIELD_MEMO = "memo";

    private final ReconciliationIssueRepository repository;
    private final ObjectMapper objectMapper;
    private final AuditRecorder auditRecorder;

    /**
     * 불일치 1건을 기록한다.
     *
     * @param type      불일치 유형
     * @param dedupeKey 유형 내 중복 방지 키(대상 식별·예: {@code payment:12})
     * @param refs      대상 식별 정보(없는 값은 null)
     * @param detail    세부(사유·통지 원문 값·조회 시점 상태)
     * @return 새로 기록했으면 true, 이미 있던 불일치면 false
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean record(ReconciliationIssueType type, String dedupeKey, ReconciliationIssueRefs refs, Map<String, Object> detail) {
        int inserted = repository.insertIfAbsent(type.name(), dedupeKey, refs.orderId(), refs.paymentId(), refs.refundId(),
                refs.claimId(), refs.deliveryId(), refs.pgTid(), refs.pgRefundId(), toJson(detail), LocalDateTime.now());
        log.warn("[Reconciliation] 불일치 {}: type={} key={} orderId={} detail={}",
                inserted == 1 ? "기록" : "이미 기록됨(멱등)", type, dedupeKey, refs.orderId(), detail);
        return inserted == 1;
    }

    /**
     * 매칭 결제 없는 PG 결제 통지의 중복 키(기록·재전송 매칭 해소가 같은 값을 쓴다). 통지 종류(콜백 유형)를 포함한다 — 같은 시도에 SUCCESS가
     * 매칭 없이 기록된 뒤 CANCEL이 매칭돼 처리돼도 SUCCESS 사실은 반영된 것이 아니라 해소하면 안 된다(외부 검토 지적 2).
     */
    public static String unmatchedPaymentKey(String paymentAttemptKey, String callbackKind) {
        return "payment-attempt:" + paymentAttemptKey + ":" + callbackKind;
    }

    /** 매칭 환불 없는 PG 환불 통지의 중복 키. 통지 종류(콜백 상태 SUCCESS·FAIL)를 포함한다(결제와 같은 이유). */
    public static String unmatchedRefundKey(String pgRefundId, String callbackKind) {
        return "pg-refund:" + pgRefundId + ":" + callbackKind;
    }

    /**
     * 매칭 없음으로 기록됐던 PG 통지가 재전송으로 매칭돼 <b>같은 종류의 정상 처리가 끝난 직후</b> 그 열린 행을 시스템이 해결 처리한다(Track 104-2
     * D-216 결정 1·외부 검토 지적 2). 매칭 없는 통지는 4xx로 PG 재전송을 부르므로(환불 개시·결제 시작 커밋 전 도착 경합) 재전송이 상태 전이까지
     * 반영되면 불일치가 아니게 된다. 충돌로 기록만 됐거나 멱등 NO-OP면 부르지 않는다. 감사는 SYSTEM 행위자로 남긴다. 행이 없거나 이미 해결됐으면
     * 아무것도 하지 않는다. 호출 시점은 주문 쓰기 락 아래다.
     *
     * <p><b>경합</b>: 조건부 UPDATE(status = OPEN)로 해결한다 — 관리자 해결과 겹치면 뒤에 오는 쪽이 0행이 되어 먼저 커밋된 해결(처리자·메모)을
     * 덮어쓰지 않는다. 감사는 실제로 바꾼 1행일 때만 남긴다(외부 검토 지적 3).
     *
     * @return 이번에 해결했으면 true
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean resolveUnmatchedOnMatch(String dedupeKey) {
        int resolved = repository.resolveBySystemIfOpen(ReconciliationIssueType.PG_UNMATCHED_CALLBACK, dedupeKey, RESOLVED_BY_RETRY_MEMO,
                LocalDateTime.now(), ReconciliationIssueStatus.RESOLVED, ReconciliationIssueStatus.OPEN);
        if (resolved != 1) {
            return false;
        }
        Long issueId = repository.findByIssueTypeAndDedupeKey(ReconciliationIssueType.PG_UNMATCHED_CALLBACK, dedupeKey)
                .map(ReconciliationIssue::getId)
                .orElseThrow(() -> new IllegalStateException("방금 해결한 불일치 행을 찾을 수 없습니다: key=" + dedupeKey));
        auditRecorder.record(AuditContext.system(), AuditLogAction.UPDATE, PolymorphicTargetType.RECONCILIATION_ISSUE, issueId,
                Map.of(AUDIT_FIELD_STATUS, ReconciliationIssueStatus.OPEN.name()),
                Map.of(AUDIT_FIELD_STATUS, ReconciliationIssueStatus.RESOLVED.name(), AUDIT_FIELD_MEMO, RESOLVED_BY_RETRY_MEMO));
        log.info("[Reconciliation] 매칭 없던 PG 통지가 같은 종류로 재처리 → 자동 해소: id={} key={}", issueId, dedupeKey);
        return true;
    }

    private String toJson(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException exception) {
            // 세부 직렬화 실패는 기록 자체를 막으면 안 되는 오류가 아니라 코드 결함(직렬화 불가 값 전달)이라 그대로 알린다.
            throw new IllegalStateException("불일치 세부 직렬화 실패: " + detail, exception);
        }
    }
}
