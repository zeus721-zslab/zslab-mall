package com.zslab.mall.reconciliation.controller.response;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 관리자 불일치 1건(Track 104-2 D-216). 목록 화면과 주문 상세 불일치 섹션이 같은 형태를 쓴다.
 *
 * @param issueId        불일치 행 id(해결 요청 경로 변수 — 관리자 전용이라 public id를 두지 않았다)
 * @param orderId        주문 public id(매칭 주문 없는 PG 통지면 null)
 * @param detail         기록 시점 세부(reason·통지 원문 값·조회 시점 상태 — 키는 유형별로 다르다)
 * @param resolvedByName 해결한 운영자 이름(미해결·회원 행 없음·시스템 해소면 null)
 * @param resolvedBySystem 시스템 자동 해소 여부(해결됐는데 처리자 없음 — 매칭 없던 PG 통지가 재전송으로 매칭된 경우)
 */
public record AdminReconciliationIssueResponse(
        long issueId,
        String issueType,
        String status,
        String orderId,
        String orderNo,
        String pgTid,
        String pgRefundId,
        Map<String, Object> detail,
        LocalDateTime detectedAt,
        LocalDateTime resolvedAt,
        String resolvedByName,
        boolean resolvedBySystem,
        String resolutionMemo) {
}
