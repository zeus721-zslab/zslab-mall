package com.zslab.mall.claim.exception;

/**
 * 클레임이 요구되는 상태가 아니어서 처리를 진행할 수 없을 때 발생한다(CLM-3).
 *
 * <p>대표 케이스: {@code RefundService.initiate}가 Claim.status=APPROVED를 요구하나 그렇지 않은 경우(미승인 환불 차단·CLM-3).
 */
public class ClaimInvalidStateException extends RuntimeException {

    public ClaimInvalidStateException(String message) {
        super(message);
    }
}
