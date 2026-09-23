package com.zslab.mall.reconciliation.exception;

/** 불일치 미존재(404·Track 104-2 D-216). */
public class ReconciliationIssueNotFoundException extends RuntimeException {

    public ReconciliationIssueNotFoundException(String message) {
        super(message);
    }
}
