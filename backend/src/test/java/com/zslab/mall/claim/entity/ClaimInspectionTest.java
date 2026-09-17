package com.zslab.mall.claim.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.claim.enums.ClaimInspectionResult;
import com.zslab.mall.claim.enums.ClaimReasonCode;
import com.zslab.mall.claim.enums.ClaimRejectReasonCode;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.exception.ClaimInvalidStateException;
import com.zslab.mall.order.enums.OrderItemStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 반품 검수 도메인 검증(Track 81-A D-170·R5) + 사유 적합성(R2). */
class ClaimInspectionTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 17, 12, 0);

    private static Claim approvedReturn() {
        Claim claim = Claim.create(1L, ClaimType.RETURN, "PRODUCT_DEFECT", null, 10L, NOW, OrderItemStatus.DELIVERED);
        claim.approve(NOW, null);
        return claim;
    }

    @Test
    @DisplayName("passInspection: 회수 확인 후 → PASS·restock 저장·상태 APPROVED 유지 / 미회수·재검수·비RETURN 422")
    void passInspection_guards() {
        Claim claim = approvedReturn();
        assertThatThrownBy(() -> claim.passInspection(true, NOW)).isInstanceOf(ClaimInvalidStateException.class); // 미회수
        claim.confirmPickup(NOW);

        claim.passInspection(false, NOW.plusHours(1));

        assertThat(claim.getInspectionResult()).isEqualTo(ClaimInspectionResult.PASS);
        assertThat(claim.getRestock()).isFalse();
        assertThat(claim.isRestockRequested()).isFalse();
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThatThrownBy(() -> claim.passInspection(true, NOW)).isInstanceOf(ClaimInvalidStateException.class); // 재검수

        Claim cancel = Claim.create(1L, ClaimType.CANCEL, "BUYER_CHANGED_MIND", null, 10L, NOW, OrderItemStatus.PAID);
        cancel.approve(NOW, null);
        assertThatThrownBy(() -> cancel.passInspection(true, NOW)).isInstanceOf(ClaimInvalidStateException.class);
    }

    @Test
    @DisplayName("failInspection: APPROVED → REJECTED 예외 전이·사유/메모/FAIL 저장 / 사유 누락·부적합(ALREADY_SHIPPED) 400")
    void failInspection_rejectsWithReason() {
        Claim claim = approvedReturn();
        claim.confirmPickup(NOW);
        assertThatThrownBy(() -> claim.failInspection(null, null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> claim.failInspection(ClaimRejectReasonCode.ALREADY_SHIPPED, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);

        claim.failInspection(ClaimRejectReasonCode.INSPECTION_FAILED, "사용 흔적", NOW.plusHours(1));

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REJECTED);
        assertThat(claim.getInspectionResult()).isEqualTo(ClaimInspectionResult.FAIL);
        assertThat(claim.getRejectReasonCode()).isEqualTo(ClaimRejectReasonCode.INSPECTION_FAILED);
        assertThat(claim.getRejectMemo()).isEqualTo("사용 흔적");
        assertThat(claim.getProcessedAt()).isEqualTo(NOW.plusHours(1));
        assertThat(claim.getRestock()).isNull();
    }

    @Test
    @DisplayName("일반 reject는 여전히 APPROVED에서 불가(매트릭스 무변경) / INSPECTION_FAILED는 RETURN 전용")
    void matrixUnchanged_andReasonScope() {
        Claim claim = approvedReturn();
        assertThatThrownBy(() -> claim.reject(ClaimRejectReasonCode.OTHER, null, NOW)).isInstanceOf(ClaimInvalidStateException.class);
        assertThat(ClaimStatus.APPROVED.canTransitionTo(ClaimStatus.REJECTED)).isFalse();
        assertThat(ClaimRejectReasonCode.INSPECTION_FAILED.isApplicableTo(ClaimType.RETURN)).isTrue();
        assertThat(ClaimRejectReasonCode.INSPECTION_FAILED.isApplicableTo(ClaimType.CANCEL)).isFalse();
    }

    @Test
    @DisplayName("ClaimReasonCode.isApplicableTo: RETURN은 단순변심·상품불량·오배송 3값·CANCEL/EXCHANGE는 전부 허용")
    void requestReasonScope() {
        assertThat(ClaimReasonCode.BUYER_CHANGED_MIND.isApplicableTo(ClaimType.RETURN)).isTrue();
        assertThat(ClaimReasonCode.PRODUCT_DEFECT.isApplicableTo(ClaimType.RETURN)).isTrue();
        assertThat(ClaimReasonCode.WRONG_PRODUCT.isApplicableTo(ClaimType.RETURN)).isTrue();
        assertThat(ClaimReasonCode.STOCK_DELAY.isApplicableTo(ClaimType.RETURN)).isFalse();
        assertThat(ClaimReasonCode.OTHER.isApplicableTo(ClaimType.RETURN)).isFalse();
        assertThat(ClaimReasonCode.STOCK_DELAY.isApplicableTo(ClaimType.CANCEL)).isTrue();
        assertThat(ClaimReasonCode.OTHER.isApplicableTo(ClaimType.EXCHANGE)).isTrue();
    }
}
