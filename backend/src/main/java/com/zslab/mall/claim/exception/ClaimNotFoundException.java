package com.zslab.mall.claim.exception;

/**
 * 클레임 행을 찾을 수 없을 때 발생한다. {@code RefundService.initiate}·{@code ClaimService}가 claimId 미매칭 시 던진다.
 */
public class ClaimNotFoundException extends RuntimeException {

    public ClaimNotFoundException(String message) {
        super(message);
    }
}
