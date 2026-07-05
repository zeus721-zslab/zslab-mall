package com.zslab.mall.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.settlement.entity.Settlement;
import com.zslab.mall.settlement.enums.SettlementStatus;
import com.zslab.mall.settlement.exception.SettlementInvalidStateException;
import com.zslab.mall.settlement.exception.SettlementNotFoundException;
import jakarta.persistence.Query;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@link SettlementTransitionService} @DataJpaTest(Track 49). 실 MariaDB에 대해 비관적 락 조회(findByIdForUpdate)와
 * 전이(confirm·pay)를 구동해 정상 전이·멱등 no-op·전이 위반(422)·404를 검증한다. 서비스는 {@link Import}로 올린다.
 *
 * <p>settlement는 FK 부담 회피를 위해 FOREIGN_KEY_CHECKS=0 하에 native 시드하고(commission_rate는 DDL DEFAULT 1000),
 * @AfterEach로 세션 변수를 복구한다(커넥션 풀 누수 방지·Track 48 P2 선례).
 */
@Import(SettlementTransitionService.class)
class SettlementTransitionServiceTest extends Batch1DataJpaTestBase {

    private static final LocalDateTime PERIOD_START = LocalDateTime.of(2026, 6, 1, 0, 0, 0);
    private static final LocalDateTime PERIOD_END = LocalDateTime.of(2026, 6, 30, 23, 59, 59);
    private static final AuditContext AUDIT_CONTEXT = AuditContext.of(1L, "ADMIN");

    @Autowired
    private SettlementTransitionService settlementTransitionService;

    // 감사 적재는 본 슬라이스 검증 대상이 아니므로 mock으로 대체(전이·멱등·422·404 로직에 영향 없음)
    @MockitoBean
    private AuditRecorder auditRecorder;

    private long seq = 0;

    @AfterEach
    void restoreForeignKeyChecks() {
        entityManager.getEntityManager().createNativeQuery("SET FOREIGN_KEY_CHECKS = 1").executeUpdate();
    }

    /** 지정 상태의 settlement 1건을 시드하고 id를 반환한다. seller_id는 호출마다 유일(uk_settlement_seller_period 회피). */
    private long insertSettlement(SettlementStatus status) {
        long sellerId = 9500L + (++seq);
        entityManager.getEntityManager().createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate();
        Query query = entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO settlement "
            + "(seller_id, bank_account_id, period_start, period_end, gross_amount, fee_amount, refund_amount, "
            + "net_amount, status, created_at, updated_at) "
            + "VALUES (:seller, 1, :periodStart, :periodEnd, 10000, 1000, 0, 9000, :status, NOW(6), NOW(6))");
        query.setParameter("seller", sellerId);
        query.setParameter("periodStart", PERIOD_START);
        query.setParameter("periodEnd", PERIOD_END);
        query.setParameter("status", status.name());
        query.executeUpdate();
        long id = ((Number) entityManager.getEntityManager()
            .createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
        entityManager.flush();
        entityManager.clear();
        return id;
    }

    @Test
    @DisplayName("confirm: PENDING → CONFIRMED 정상 전이")
    void confirm_fromPending() {
        long id = insertSettlement(SettlementStatus.PENDING);

        Settlement result = settlementTransitionService.confirm(id, AUDIT_CONTEXT);

        assertThat(result.getStatus()).isEqualTo(SettlementStatus.CONFIRMED);
        assertThat(result.getPaidAt()).isNull();
    }

    @Test
    @DisplayName("pay: CONFIRMED → PAID 정상 전이·paid_at 세팅(STL-5)")
    void pay_fromConfirmed() {
        long id = insertSettlement(SettlementStatus.CONFIRMED);

        Settlement result = settlementTransitionService.pay(id, AUDIT_CONTEXT);

        assertThat(result.getStatus()).isEqualTo(SettlementStatus.PAID);
        assertThat(result.getPaidAt()).isNotNull();
    }

    @Test
    @DisplayName("confirm: 이미 CONFIRMED → 멱등 no-op(재요청 안전)")
    void confirm_idempotentWhenConfirmed() {
        long id = insertSettlement(SettlementStatus.CONFIRMED);

        Settlement result = settlementTransitionService.confirm(id, AUDIT_CONTEXT);

        assertThat(result.getStatus()).isEqualTo(SettlementStatus.CONFIRMED);
    }

    @Test
    @DisplayName("pay: 이미 PAID → 멱등 no-op(불가역·재요청 안전)")
    void pay_idempotentWhenPaid() {
        long id = insertSettlement(SettlementStatus.PAID);

        Settlement result = settlementTransitionService.pay(id, AUDIT_CONTEXT);

        assertThat(result.getStatus()).isEqualTo(SettlementStatus.PAID);
    }

    @Test
    @DisplayName("pay: PENDING에서 지급 시도 → SettlementInvalidStateException(CONFIRMED 미경유)")
    void pay_invalidFromPending() {
        long id = insertSettlement(SettlementStatus.PENDING);

        assertThatThrownBy(() -> settlementTransitionService.pay(id, AUDIT_CONTEXT))
            .isInstanceOf(SettlementInvalidStateException.class);
    }

    @Test
    @DisplayName("confirm: PAID에서 확정 시도 → SettlementInvalidStateException(불가역)")
    void confirm_invalidFromPaid() {
        long id = insertSettlement(SettlementStatus.PAID);

        assertThatThrownBy(() -> settlementTransitionService.confirm(id, AUDIT_CONTEXT))
            .isInstanceOf(SettlementInvalidStateException.class);
    }

    @Test
    @DisplayName("confirm·pay: 미존재 settlementId → SettlementNotFoundException")
    void transition_notFound() {
        assertThatThrownBy(() -> settlementTransitionService.confirm(999_999L, AUDIT_CONTEXT))
            .isInstanceOf(SettlementNotFoundException.class);
        assertThatThrownBy(() -> settlementTransitionService.pay(999_999L, AUDIT_CONTEXT))
            .isInstanceOf(SettlementNotFoundException.class);
    }
}
