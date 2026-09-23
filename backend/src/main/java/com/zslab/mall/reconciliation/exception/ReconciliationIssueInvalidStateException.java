package com.zslab.mall.reconciliation.exception;

/** 불일치 상태상 허용되지 않는 조작(이미 해결된 불일치의 재해결 등·422·Track 104-2 D-216). */
public class ReconciliationIssueInvalidStateException extends RuntimeException {

    public ReconciliationIssueInvalidStateException(String message) {
        super(message);
    }
}
