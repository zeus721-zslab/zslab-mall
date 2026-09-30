package com.zslab.mall.productquestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.productquestion.repository.ProductQuestionRepository;
import com.zslab.mall.productquestion.service.ProductQuestionService;
import com.zslab.mall.productquestion.service.SellerProductQuestionService;
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
 * 질문 행 락 경합 통합 테스트(Track 106-2·D-239 규칙 9·실 MariaDB). 셀러 답변 등록과 질문자 삭제가 동시에 들어오면 행 락으로 직렬화돼 정확히
 * 한쪽만 성공하고, 최종 상태가 이긴 쪽과 일치해야 한다 — 삭제가 이기면 답변 없이 삭제(답변은 404), 답변이 이기면 답변만 남고 삭제는 422.
 * 경합 실행은 ReviewCreateIntegrationTest·ClaimLockRaceIntegrationTest의 래치 패턴을 따른다. R2는 순서를 고정한 결정적 경합이다 — 한쪽이 락을
 * 쥔 채 기다리는 동안 다른 쪽이 끝나지 않음(락 대기)을 Future 타임아웃으로 확인한다.
 */
class ProductQuestionLockRaceIntegrationTest extends AbstractIntegrationTest {

    private static final long BAND_FROM = 10880L;
    private static final long BAND_TO = 10899L;
    private static final long BUYER = 10881L;
    private static final long SELLER = 10882L;
    private static final long SELLER_USER = 10883L;
    private static final long PRODUCT = 10884L;
    private static final long QUESTION = 10885L;
    private static final long RACE_TIMEOUT_SECONDS = 20L;
    private static final String OK = "OK";
    /** 락 대기 판정 시간: 락이 없다면 답변은 이 안에 끝난다(단건 답변은 수십 ms). */
    private static final long LOCK_WAIT_PROBE_MILLIS = 500L;

    @Autowired
    private ProductQuestionRepository productQuestionRepository;
    @Autowired
    private ProductQuestionService productQuestionService;
    @Autowired
    private SellerProductQuestionService sellerProductQuestionService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private ProductQuestionFixture fixture;
    private String questionPid;

    @BeforeEach
    void setUp() {
        fixture = new ProductQuestionFixture(jdbc, txManager);
        fixture.cleanup(BAND_FROM, BAND_TO);
        fixture.seedUser(BUYER);
        fixture.seedCatalog(SELLER, "ACTIVE", PRODUCT, null);
        fixture.seedSellerUser(SELLER_USER, SELLER);
        questionPid = fixture.insertQuestion(QUESTION, PRODUCT, BUYER, "경합 대상 질문", "VISIBLE", null, null,
                LocalDateTime.now().minusHours(1));
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(BAND_FROM, BAND_TO);
    }

    @Test
    @DisplayName("R1 답변 등록과 질문 삭제 동시 → 정확히 한쪽만 성공 · 삭제 승리면 답변 없음·답변 404 / 답변 승리면 삭제 안 됨·삭제 422")
    void answerAndDeleteConcurrently_exactlyOneWins() throws Exception {
        String[] outcomes = race(
                () -> outcomeOf(() -> sellerProductQuestionService.answer(SELLER, SELLER_USER, questionPid, "경합 답변")),
                () -> outcomeOf(() -> productQuestionService.delete(BUYER, questionPid)));

        String answerOutcome = outcomes[0];
        String deleteOutcome = outcomes[1];
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT answer_content, answered_at, deleted_at FROM product_question WHERE id = ?", QUESTION);
        if (OK.equals(deleteOutcome)) {
            assertThat(answerOutcome).as("삭제가 먼저면 답변은 삭제된 질문을 찾지 못한다").isEqualTo("ProductQuestionNotFoundException");
            assertThat(row.get("deleted_at")).isNotNull();
            assertThat(row.get("answer_content")).isNull();
        } else {
            assertThat(answerOutcome).as("삭제가 실패하면 답변이 이긴 것이다: delete=%s", deleteOutcome).isEqualTo(OK);
            assertThat(deleteOutcome).isEqualTo("ProductQuestionInvalidStateException");
            assertThat(row.get("deleted_at")).isNull();
            assertThat(row.get("answer_content")).isEqualTo("경합 답변");
        }
    }

    @Test
    @DisplayName("R2 락 보유: A가 질문 행을 잠근 동안 B(셀러 답변)는 대기 → A가 같은 트랜잭션에서 삭제·커밋 → B는 404로 끝나고 답변 컬럼은 빈 채로 남는다")
    void answerWaitsForLockHolder_thenSeesDeletion() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        TransactionTemplate holderTx = new TransactionTemplate(txManager);
        try {
            Future<String> holder = pool.submit(() -> outcomeOf(() -> holderTx.executeWithoutResult(status -> {
                productQuestionRepository.findByPublicIdForUpdate(questionPid).orElseThrow();
                locked.countDown();
                awaitQuietly(releaseHolder);
                productQuestionService.delete(BUYER, questionPid);
            })));
            assertThat(locked.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("A가 행 락을 잡았다").isTrue();

            Future<String> answer = pool.submit(
                    () -> outcomeOf(() -> sellerProductQuestionService.answer(SELLER, SELLER_USER, questionPid, "락 대기 답변")));
            assertThatThrownBy(() -> answer.get(LOCK_WAIT_PROBE_MILLIS, TimeUnit.MILLISECONDS))
                    .as("A가 락을 쥐고 있는 동안 답변은 끝나지 않는다(락 대기)")
                    .isInstanceOf(TimeoutException.class);

            releaseHolder.countDown();
            assertThat(holder.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(OK);
            assertThat(answer.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo("ProductQuestionNotFoundException");
        } finally {
            releaseHolder.countDown();
            pool.shutdownNow();
        }
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT answer_content, answered_at, answered_by, deleted_at FROM product_question WHERE id = ?", QUESTION);
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(row.get("answer_content")).isNull();
        assertThat(row.get("answered_at")).isNull();
        assertThat(row.get("answered_by")).isNull();
    }

    // ---------- 경합 실행 ----------

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
