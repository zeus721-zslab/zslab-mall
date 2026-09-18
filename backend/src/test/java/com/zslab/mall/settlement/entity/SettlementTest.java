package com.zslab.mall.settlement.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.settlement.enums.SettlementStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Settlement} 전이 mutator(markConfirmed·markPaid) 단위 검증(Track 49). 정상 전이·전이 위반 시
 * {@link IllegalStateException}·STL-5(PAID ⟺ paid_at≠null)를 확인한다. Settlement는 setter가 없어
 * {@link Settlement#create} 팩토리(초기 PENDING)로 생성한 뒤 전이한다. Track 85: 생성 시 계좌 NULL·지급예정일 필수·markPaid가
 * 지급 계좌를 스냅샷한다(STL-3).
 */
class SettlementTest {

    private static final LocalDateTime PERIOD_START = LocalDateTime.of(2026, 6, 1, 0, 0, 0);
    private static final LocalDateTime PERIOD_END = LocalDateTime.of(2026, 6, 30, 23, 59, 59);
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 7, 5, 10, 0, 0);
    private static final LocalDate SCHEDULED_PAY_DATE = LocalDate.of(2026, 7, 20);
    private static final Long BANK_ACCOUNT_ID = 77L;

    private Settlement pendingSettlement() {
        return Settlement.create(1L, PERIOD_START, PERIOD_END, 10_000L, 1_000L, 0L, SCHEDULED_PAY_DATE);
    }

    @Test
    @DisplayName("create: PENDING·net=gross−fee−refund·계좌 NULL·헤더 율 NULL·지급예정일 세팅 / 지급예정일 누락 → IllegalArgumentException")
    void create_initialState() {
        Settlement settlement = pendingSettlement();

        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(settlement.getNetAmount()).isEqualTo(9_000L);
        assertThat(settlement.getBankAccountId()).isNull();
        assertThat(settlement.getCommissionRate()).isNull();
        assertThat(settlement.getScheduledPayDate()).isEqualTo(SCHEDULED_PAY_DATE);
        assertThatThrownBy(() -> Settlement.create(1L, PERIOD_START, PERIOD_END, 10_000L, 1_000L, 0L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("markConfirmed: PENDING → CONFIRMED 전이·paid_at 미변경")
    void markConfirmed_fromPending() {
        Settlement settlement = pendingSettlement();

        settlement.markConfirmed();

        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.CONFIRMED);
        assertThat(settlement.getPaidAt()).isNull();  // STL-5: PAID 전이만 paid_at 세팅
    }

    @Test
    @DisplayName("markConfirmed: PENDING 아닌 상태 전이 시 IllegalStateException(CONFIRMED·PAID)")
    void markConfirmed_illegalFromNonPending() {
        Settlement confirmed = pendingSettlement();
        confirmed.markConfirmed();
        assertThatThrownBy(confirmed::markConfirmed).isInstanceOf(IllegalStateException.class);

        Settlement paid = pendingSettlement();
        paid.markConfirmed();
        paid.markPaid(PAID_AT, BANK_ACCOUNT_ID);
        assertThatThrownBy(paid::markConfirmed).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markPaid: CONFIRMED → PAID 전이·paid_at 세팅(STL-5)·지급 계좌 스냅샷(STL-3)")
    void markPaid_fromConfirmed() {
        Settlement settlement = pendingSettlement();
        settlement.markConfirmed();

        settlement.markPaid(PAID_AT, BANK_ACCOUNT_ID);

        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PAID);
        assertThat(settlement.getPaidAt()).isEqualTo(PAID_AT);
        assertThat(settlement.getBankAccountId()).isEqualTo(BANK_ACCOUNT_ID);
    }

    @Test
    @DisplayName("markPaid: PENDING에서 직접 지급 시 IllegalStateException(CONFIRMED 미경유)")
    void markPaid_illegalFromPending() {
        Settlement settlement = pendingSettlement();

        assertThatThrownBy(() -> settlement.markPaid(PAID_AT, BANK_ACCOUNT_ID)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markPaid: PAID 재지급 시 IllegalStateException(불가역)")
    void markPaid_illegalWhenAlreadyPaid() {
        Settlement settlement = pendingSettlement();
        settlement.markConfirmed();
        settlement.markPaid(PAID_AT, BANK_ACCOUNT_ID);

        assertThatThrownBy(() -> settlement.markPaid(PAID_AT, BANK_ACCOUNT_ID)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markPaid: paidAt null·bankAccountId null → IllegalArgumentException(STL-5·STL-3 위반 방지)")
    void markPaid_nullArguments() {
        Settlement settlement = pendingSettlement();
        settlement.markConfirmed();

        assertThatThrownBy(() -> settlement.markPaid(null, BANK_ACCOUNT_ID)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settlement.markPaid(PAID_AT, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.CONFIRMED);
    }
}
