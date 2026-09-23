package com.zslab.mall.reconciliation.enums;

/**
 * 불일치 처리 상태(reconciliation_issue.status DDL ENUM 1:1·V35·Track 104-2 D-216). 해결은 OPEN → RESOLVED 한 번뿐이다.
 * FE 상수는 {@code frontend/layers/admin/app/lib/constants/reconciliation.ts}와 1:1이다.
 */
public enum ReconciliationIssueStatus {

    OPEN,
    RESOLVED
}
