package com.zslab.mall.claim.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.claim.enums.ClaimRejectReasonCode;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.exception.ClaimInvalidStateException;
import com.zslab.mall.order.enums.OrderItemStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link Claim#reject} 도메인 검증(Track 80 D-169·거부 사유 필수·ALREADY_SHIPPED CANCEL 전용·메모 길이). */
class ClaimRejectTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 16, 12, 0);

    private static Claim requested(ClaimType type, OrderItemStatus previous) {
        return Claim.create(1L, type, "BUYER_CHANGED_MIND", null, 10L, NOW, previous);
    }

    @Test
    @DisplayName("reject: 사유 코드 + 메모 → REJECTED·processedAt·rejectReasonCode·rejectMemo 저장")
    void reject_storesReasonAndMemo() {
        Claim claim = requested(ClaimType.CANCEL, OrderItemStatus.PAID);

        claim.reject(ClaimRejectReasonCode.OUT_OF_POLICY, "정책 위반", NOW);

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REJECTED);
        assertThat(claim.getProcessedAt()).isEqualTo(NOW);
        assertThat(claim.getRejectReasonCode()).isEqualTo(ClaimRejectReasonCode.OUT_OF_POLICY);
        assertThat(claim.getRejectMemo()).isEqualTo("정책 위반");
    }

    @Test
    @DisplayName("reject: 사유 코드 null → IllegalArgumentException·상태 불변")
    void reject_reasonRequired() {
        Claim claim = requested(ClaimType.CANCEL, OrderItemStatus.PAID);

        assertThatThrownBy(() -> claim.reject(null, null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REQUESTED);
    }

    @Test
    @DisplayName("reject: ALREADY_SHIPPED는 CANCEL 전용 → RETURN/EXCHANGE에 IllegalArgumentException, CANCEL은 허용")
    void reject_alreadyShipped_cancelOnly() {
        Claim returnClaim = requested(ClaimType.RETURN, OrderItemStatus.DELIVERED);
        Claim exchangeClaim = requested(ClaimType.EXCHANGE, OrderItemStatus.DELIVERED);
        Claim cancelClaim = requested(ClaimType.CANCEL, OrderItemStatus.PAID);

        assertThatThrownBy(() -> returnClaim.reject(ClaimRejectReasonCode.ALREADY_SHIPPED, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> exchangeClaim.reject(ClaimRejectReasonCode.ALREADY_SHIPPED, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(returnClaim.getStatus()).isEqualTo(ClaimStatus.REQUESTED);
        cancelClaim.reject(ClaimRejectReasonCode.ALREADY_SHIPPED, null, NOW);
        assertThat(cancelClaim.getRejectReasonCode()).isEqualTo(ClaimRejectReasonCode.ALREADY_SHIPPED);
    }

    @Test
    @DisplayName("reject: 메모 500자 초과 → IllegalArgumentException / 500자 정확히는 허용")
    void reject_memoLength() {
        Claim claim = requested(ClaimType.CANCEL, OrderItemStatus.PAID);
        String memo500 = "가".repeat(Claim.REJECT_MEMO_MAX_LENGTH);

        assertThatThrownBy(() -> claim.reject(ClaimRejectReasonCode.OTHER, memo500 + "나", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        claim.reject(ClaimRejectReasonCode.OTHER, memo500, NOW);
        assertThat(claim.getRejectMemo()).hasSize(Claim.REJECT_MEMO_MAX_LENGTH);
    }

    @Test
    @DisplayName("reject: REQUESTED가 아니면 ClaimInvalidStateException(CLM-4)·사유 미저장")
    void reject_illegalTransition() {
        Claim claim = requested(ClaimType.CANCEL, OrderItemStatus.PAID);
        claim.approve(NOW, null);

        assertThatThrownBy(() -> claim.reject(ClaimRejectReasonCode.OTHER, null, NOW))
                .isInstanceOf(ClaimInvalidStateException.class);
        assertThat(claim.getRejectReasonCode()).isNull();
    }
}
