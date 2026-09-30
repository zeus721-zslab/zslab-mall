package com.zslab.mall.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.inquiry.enums.InquiryCategory;
import com.zslab.mall.inquiry.repository.InquiryRepository;
import com.zslab.mall.inquiry.service.AdminInquiryCommandService;
import com.zslab.mall.inquiry.service.InquiryService;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 문의 행 락 경합 통합 테스트(Track 106-4·실 MariaDB·ProductQuestionLockRaceIntegrationTest 패턴). 관리자 답변과 구매자 삭제·수정이 동시에
 * 들어오면 행 락으로 직렬화돼 최종 상태가 이긴 쪽과 일치해야 한다. R3은 순서를 고정한 결정적 경합이다 — 한쪽이 락을 쥔 채 기다리는 동안 다른 쪽이
 * 끝나지 않음(락 대기)을 Future 타임아웃으로 확인한다.
 */
class InquiryLockRaceIntegrationTest extends AbstractIntegrationTest {

    private static final long BAND_FROM = 10940L;
    private static final long BAND_TO = 10959L;
    private static final long BUYER = 10941L;
    private static final long ADMIN = 10942L;
    private static final long INQUIRY = 10943L;
    private static final long CHECKED_INQUIRY = 10944L;
    private static final long RACE_TIMEOUT_SECONDS = 20L;
    private static final String OK = "OK";
    private static final String ADMIN_ROLE = "ADMIN";
    private static final String ORIGINAL_CONTENT = "경합 대상 문의";
    /** 락 대기 판정 시간: 락이 없다면 답변은 이 안에 끝난다(단건 답변은 수십 ms). */
    private static final long LOCK_WAIT_PROBE_MILLIS = 500L;

    @Autowired
    private InquiryRepository inquiryRepository;
    @Autowired
    private InquiryService inquiryService;
    @Autowired
    private AdminInquiryCommandService adminInquiryCommandService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private InquiryFixture fixture;
    private String inquiryPid;

    @BeforeEach
    void setUp() {
        fixture = new InquiryFixture(jdbc, txManager);
        fixture.cleanup(BAND_FROM, BAND_TO);
        fixture.seedUser(BUYER, null);
        fixture.seedUser(ADMIN, null);
        inquiryPid = fixture.insertInquiry(INQUIRY, BUYER, null, "OTHER", ORIGINAL_CONTENT, null, null, false,
                LocalDateTime.now().minusHours(1));
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(BAND_FROM, BAND_TO);
    }

    @Test
    @DisplayName("R1 답변과 삭제 동시 → 정확히 한쪽만 성공 · 삭제 승리면 답변 없음·답변 404 / 답변 승리면 삭제 안 됨·삭제 422")
    void answerAndDeleteConcurrently_exactlyOneWins() throws Exception {
        String[] outcomes = race(
                () -> outcomeOf(() -> adminInquiryCommandService.answer(inquiryPid, "경합 답변", adminContext())),
                () -> outcomeOf(() -> inquiryService.delete(BUYER, inquiryPid)));

        String answerOutcome = outcomes[0];
        String deleteOutcome = outcomes[1];
        Map<String, Object> row = jdbc.queryForMap("SELECT answer_content, deleted_at FROM inquiry WHERE id = ?", INQUIRY);
        if (OK.equals(deleteOutcome)) {
            assertThat(answerOutcome).as("삭제가 먼저면 답변은 삭제된 문의를 찾지 못한다").isEqualTo("InquiryNotFoundException");
            assertThat(row.get("deleted_at")).isNotNull();
            assertThat(row.get("answer_content")).isNull();
        } else {
            assertThat(answerOutcome).as("삭제가 실패하면 답변이 이긴 것이다: delete=%s", deleteOutcome).isEqualTo(OK);
            assertThat(deleteOutcome).isEqualTo("InquiryInvalidStateException");
            assertThat(row.get("deleted_at")).isNull();
            assertThat(row.get("answer_content")).isEqualTo("경합 답변");
        }
    }

    @Test
    @DisplayName("R2 답변과 수정 동시 → 답변은 항상 성공 · 수정 승리면 수정 본문에 답변 / 답변 승리면 수정 422·원문 유지")
    void answerAndUpdateConcurrently_stateMatchesWinner() throws Exception {
        String[] outcomes = race(
                () -> outcomeOf(() -> adminInquiryCommandService.answer(inquiryPid, "경합 답변", adminContext())),
                () -> outcomeOf(() -> inquiryService.update(BUYER, inquiryPid, InquiryCategory.DELIVERY, "경합 중 수정한 문의")));

        String answerOutcome = outcomes[0];
        String updateOutcome = outcomes[1];
        Map<String, Object> row = jdbc.queryForMap("SELECT content, answer_content FROM inquiry WHERE id = ?", INQUIRY);
        assertThat(answerOutcome).isEqualTo(OK);
        assertThat(row.get("answer_content")).isEqualTo("경합 답변");
        if (OK.equals(updateOutcome)) {
            assertThat(row.get("content")).as("수정이 먼저 커밋되고 답변이 뒤따랐다").isEqualTo("경합 중 수정한 문의");
        } else {
            assertThat(updateOutcome).isEqualTo("InquiryInvalidStateException");
            assertThat(row.get("content")).as("답변이 먼저면 수정은 막힌다").isEqualTo(ORIGINAL_CONTENT);
        }
    }

    @Test
    @DisplayName("R4 답변 수정과 구매자 확인 동시(답변·확인 완료 상태에서) → 둘 다 성공 · 확인 먼저면 확인 시각 null(미확인 복귀) / 수정 먼저면 "
            + "확인 시각이 새 답변 시각 이후 · 본문은 새 답변 · 감사 1건")
    void modifyAnswerAndCheckConcurrently_neverLosesUnread() throws Exception {
        LocalDateTime createdAt = LocalDateTime.now().minusHours(2);
        String checkedPid = fixture.insertInquiry(CHECKED_INQUIRY, BUYER, null, "OTHER", "확인까지 끝난 문의", "처음 답변", ADMIN, true,
                createdAt);

        String[] outcomes = race(
                () -> outcomeOf(() -> adminInquiryCommandService.answer(checkedPid, "수정한 답변", adminContext())),
                () -> outcomeOf(() -> inquiryService.checkAnswer(BUYER, checkedPid)));

        assertThat(outcomes).containsExactly(OK, OK);
        Map<String, Object> row = jdbc.queryForMap("SELECT answer_content FROM inquiry WHERE id = ?", CHECKED_INQUIRY);
        assertThat(row.get("answer_content")).isEqualTo("수정한 답변");
        LocalDateTime answeredAt = jdbc.queryForObject("SELECT answered_at FROM inquiry WHERE id = ?", LocalDateTime.class,
                CHECKED_INQUIRY);
        LocalDateTime checkedAt = jdbc.queryForObject("SELECT answer_checked_at FROM inquiry WHERE id = ?", LocalDateTime.class,
                CHECKED_INQUIRY);
        assertThat(answeredAt).as("수정이 반영된 새 답변 시각").isAfter(createdAt.plusMinutes(1));
        if (checkedAt != null) {
            assertThat(checkedAt).as("수정 먼저 → 확인: 확인 시각은 새 답변 시각 이후(이전 확인 시각이 남으면 미확인 유실)")
                    .isAfterOrEqualTo(answeredAt);
        }
        // checkedAt == null 은 확인 먼저(이미 확인이라 무변경) → 수정(확인 시각 초기화) 순서다.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target_type = 'INQUIRY' AND target_id = ?", Integer.class,
                CHECKED_INQUIRY)).isEqualTo(1);
    }

    @Test
    @DisplayName("R3 락 보유: A가 문의 행을 잠근 동안 B(관리자 답변)는 대기 → A가 같은 트랜잭션에서 삭제·커밋 → B는 404로 끝나고 답변 컬럼은 빈 채로 남는다")
    void answerWaitsForLockHolder_thenSeesDeletion() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        TransactionTemplate holderTx = new TransactionTemplate(txManager);
        try {
            Future<String> holder = pool.submit(() -> outcomeOf(() -> holderTx.executeWithoutResult(status -> {
                inquiryRepository.findByPublicIdForUpdate(inquiryPid).orElseThrow();
                locked.countDown();
                awaitQuietly(releaseHolder);
                inquiryService.delete(BUYER, inquiryPid);
            })));
            assertThat(locked.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("A가 행 락을 잡았다").isTrue();

            Future<String> answer = pool.submit(
                    () -> outcomeOf(() -> adminInquiryCommandService.answer(inquiryPid, "락 대기 답변", adminContext())));
            assertThatThrownBy(() -> answer.get(LOCK_WAIT_PROBE_MILLIS, TimeUnit.MILLISECONDS))
                    .as("A가 락을 쥐고 있는 동안 답변은 끝나지 않는다(락 대기)")
                    .isInstanceOf(TimeoutException.class);

            releaseHolder.countDown();
            assertThat(holder.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(OK);
            assertThat(answer.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo("InquiryNotFoundException");
        } finally {
            releaseHolder.countDown();
            pool.shutdownNow();
        }
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT answer_content, answered_at, answered_by, deleted_at FROM inquiry WHERE id = ?", INQUIRY);
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(row.get("answer_content")).isNull();
        assertThat(row.get("answered_at")).isNull();
        assertThat(row.get("answered_by")).isNull();
    }

    // ---------- 경합 실행 ----------

    private static AuditContext adminContext() {
        return AuditContext.of(ADMIN, ADMIN_ROLE);
    }

    private static String outcomeOf(Runnable command) {
        try {
            command.run();
            return OK;
        } catch (RuntimeException exception) {
            return exception.getClass().getSimpleName();
        }
    }

    /** 두 작업을 래치로 동시에 풀어 결과 문자열 2개를 돌려준다. */
    private String[] race(Callable<String> first, Callable<String> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> firstResult = pool.submit(gate(first, ready, start));
            Future<String> secondResult = pool.submit(gate(second, ready, start));
            awaitQuietly(ready);
            start.countDown();
            return new String[] {
                    firstResult.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    secondResult.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            };
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<String> gate(Callable<String> work, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            awaitQuietly(start);
            return work.call();
        };
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("경합 래치 대기 중 인터럽트", exception);
        }
    }
}
