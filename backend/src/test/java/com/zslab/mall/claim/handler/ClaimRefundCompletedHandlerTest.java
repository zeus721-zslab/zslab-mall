package com.zslab.mall.claim.handler;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.claim.service.ClaimService;
import com.zslab.mall.refund.event.RefundCompleted;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ClaimRefundCompletedHandler} 단위 검증(D-98 Q4·Q2). CANCEL·RETURN은 markCompleted 위임·EXCHANGE/비APPROVED/
 * 미발견은 skip을 mock 경계에서 검증한다(CLAUDE.md 변경 영역 테스트 추가·기존 테스트 부재 도메인).
 */
@ExtendWith(MockitoExtension.class)
class ClaimRefundCompletedHandlerTest {

    private static final Long CLAIM_ID = 1L;
    private static final LocalDateTime REFUNDED_AT = LocalDateTime.of(2026, 6, 29, 11, 0);

    @Mock
    private ClaimRepository claimRepository;
    @Mock
    private ClaimService claimService;
    @InjectMocks
    private ClaimRefundCompletedHandler handler;

    private RefundCompleted event() {
        return new RefundCompleted(99L, CLAIM_ID, 7L, 12_000L, REFUNDED_AT);
    }

    private Claim claim(ClaimType type, ClaimStatus status) {
        Claim claim = mock(Claim.class);
        when(claim.getType()).thenReturn(type);
        when(claim.getStatus()).thenReturn(status);
        return claim;
    }

    @Test
    @DisplayName("onRefundCompleted: CANCEL·APPROVED → markCompleted 위임")
    void cancel_approved_marksCompleted() {
        Claim claim = claim(ClaimType.CANCEL, ClaimStatus.APPROVED);
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim));

        handler.onRefundCompleted(event());

        verify(claimService).markCompleted(CLAIM_ID);
    }

    @Test
    @DisplayName("onRefundCompleted: RETURN·APPROVED → markCompleted 위임(D-98 Q2 수거 후 환불 종결)")
    void return_approved_marksCompleted() {
        Claim claim = claim(ClaimType.RETURN, ClaimStatus.APPROVED);
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim));

        handler.onRefundCompleted(event());

        verify(claimService).markCompleted(CLAIM_ID);
    }

    @Test
    @DisplayName("onRefundCompleted: EXCHANGE → skip(Refund 미경유·markCompleted 없음)")
    void exchange_skips() {
        Claim claim = mock(Claim.class);
        when(claim.getType()).thenReturn(ClaimType.EXCHANGE);
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim));

        handler.onRefundCompleted(event());

        verify(claimService, never()).markCompleted(anyLong());
    }

    @Test
    @DisplayName("onRefundCompleted: 클레임 미발견 → skip")
    void claimNotFound_skips() {
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.empty());

        handler.onRefundCompleted(event());

        verify(claimService, never()).markCompleted(anyLong());
    }

    @Test
    @DisplayName("onRefundCompleted: 비APPROVED(COMPLETED) → 멱등 skip")
    void notApproved_skips() {
        Claim claim = claim(ClaimType.CANCEL, ClaimStatus.COMPLETED);
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim));

        handler.onRefundCompleted(event());

        verify(claimService, never()).markCompleted(anyLong());
    }
}
