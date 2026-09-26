package com.zslab.mall.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.auth.exception.LastSuperAdminRevocationException;
import com.zslab.mall.auth.repository.RoleRepository;
import com.zslab.mall.auth.service.RoleRevocationService;
import com.zslab.mall.support.AbstractIntegrationTest;
import com.zslab.mall.user.service.UserService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 마지막 슈퍼 관리자 보호 동시성 테스트(D-230·실 MariaDB). 활성 슈퍼 관리자 2명이 동시에 빠지려 할 때 SUPER_ADMIN Role 행 잠금으로
 * 직렬화돼 1건만 성공하는지 검증한다. 첫 요청이 잠금을 잡은 직후(커밋 전) 멈추게 하고, 두 번째 요청이 잠금에서 대기하는지 확인한 뒤
 * 풀어 준다(ReconciliationResolveRaceIntegrationTest 패턴). 잠금을 잡지 않는 구현이면 첫 요청의 정지 신호가 오지 않아 실패한다.
 */
class LastSuperAdminRaceIntegrationTest extends AbstractIntegrationTest {

    private static final long SUPER_A = 9741L;
    private static final long SUPER_B = 9742L;
    private static final int RACE_TIMEOUT_SECONDS = 30;
    /** 뒤 요청이 이 시간 안에 끝나지 않으면(isDone false) 앞 트랜잭션의 잠금에 막힌 것으로 본다. */
    private static final int BLOCK_PROBE_MILLIS = 2_000;

    @MockitoSpyBean
    private RoleRepository roleRepository;
    @Autowired
    private UserService userService;
    @Autowired
    private RoleRevocationService roleRevocationService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private final AtomicBoolean holdFirstLock = new AtomicBoolean(false);
    private CountDownLatch firstHolding;
    private CountDownLatch releaseFirst;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        firstHolding = new CountDownLatch(1);
        releaseFirst = new CountDownLatch(1);
        Answer<?> delegateToRealRepository =
                Mockito.mockingDetails(roleRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object locked = delegateToRealRepository.answer(invocation);
            if (holdFirstLock.compareAndSet(true, false)) {
                firstHolding.countDown();
                awaitQuietly(releaseFirst);
            }
            return locked;
        }).when(roleRepository).findByCodeForUpdate(any());
        cleanup();
        seed();
    }

    @AfterEach
    void tearDown() {
        releaseFirst.countDown();
        cleanup();
    }

    @Test
    @DisplayName("활성 슈퍼 관리자 2명 동시 본인 탈퇴 → 뒤 요청은 잠금 대기 후 409 · 1건만 성공 · 활성 1명 유지")
    void concurrentSelfWithdraw_onlyOneSucceeds() throws Exception {
        List<Throwable> failures = race(() -> userService.withdraw(SUPER_A), () -> userService.withdraw(SUPER_B));

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).isInstanceOf(LastSuperAdminRevocationException.class);
        assertThat(activeSuperAdminCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("슈퍼 관리자 2명이 동시에 서로의 SUPER_ADMIN 회수 → 뒤 요청은 잠금 대기 후 409 · 1건만 성공 · 활성 1명 유지")
    void concurrentMutualRevoke_onlyOneSucceeds() throws Exception {
        List<Throwable> failures = race(
                () -> roleRevocationService.revoke(SUPER_A, publicId(SUPER_B), RoleCode.SUPER_ADMIN, "동시 회수",
                        AuditContext.of(SUPER_A, "ADMIN")),
                () -> roleRevocationService.revoke(SUPER_B, publicId(SUPER_A), RoleCode.SUPER_ADMIN, "동시 회수",
                        AuditContext.of(SUPER_B, "ADMIN")));

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).isInstanceOf(LastSuperAdminRevocationException.class);
        assertThat(activeSuperAdminCount()).isEqualTo(1);
    }

    /** 첫 작업을 잠금 직후 정지 → 두 번째 작업이 잠금에서 대기하는지 확인 → 해제. 실패한 작업의 원인 예외 목록을 돌려준다. */
    private List<Throwable> race(Runnable first, Runnable second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            holdFirstLock.set(true);
            Future<?> firstFuture = executor.submit(first);
            assertThat(firstHolding.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("첫 요청이 SUPER_ADMIN Role 행 잠금을 잡음").isTrue();

            Future<?> secondFuture = executor.submit(second);
            TimeUnit.MILLISECONDS.sleep(BLOCK_PROBE_MILLIS);
            boolean secondBlocked = !secondFuture.isDone();

            releaseFirst.countDown();
            List<Throwable> failures = new ArrayList<>();
            for (Future<?> future : List.of(firstFuture, secondFuture)) {
                try {
                    future.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (ExecutionException executionException) {
                    failures.add(executionException.getCause());
                }
            }
            assertThat(secondBlocked).as("뒤 요청이 앞 요청의 잠금에서 대기").isTrue();
            return failures;
        } finally {
            executor.shutdownNow();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String publicId(long userId) {
        return ("usr_D230RACE" + userId + "00000000000000000000000000").substring(0, 30);
    }

    // ---------- 시드·정리(모든 SQL은 ? 바인딩·SQL injection 없음) ----------

    private void seed() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                for (long id : List.of(SUPER_A, SUPER_B)) {
                    jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                            id, publicId(id));
                    jdbc.update("INSERT INTO user_role (user_id, role_id, created_at) SELECT ?, id, NOW(6) FROM role WHERE code = 'SUPER_ADMIN'",
                            id);
                }
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private int activeSuperAdminCount() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_role ur JOIN role r ON ur.role_id = r.id JOIN `user` u ON u.id = ur.user_id "
                        + "WHERE r.code = 'SUPER_ADMIN' AND u.withdrawn_at IS NULL AND u.deleted_at IS NULL",
                Integer.class);
        return count == null ? 0 : count;
    }

    private void cleanup() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                // 전역 부트스트랩 SUPER_ADMIN도 활성 인원에 들어가므로 제거한다(AdminUserRoleControllerIntegrationTest 정합).
                List<Long> superAdminUserIds = jdbc.queryForList(
                        "SELECT ur.user_id FROM user_role ur JOIN role r ON ur.role_id = r.id WHERE r.code = 'SUPER_ADMIN'",
                        Long.class);
                jdbc.update("DELETE FROM user_role WHERE role_id = (SELECT id FROM role WHERE code = 'SUPER_ADMIN')");
                for (Long id : superAdminUserIds) {
                    jdbc.update("DELETE FROM `user` WHERE id = ?", id);
                }
                for (long id : List.of(SUPER_A, SUPER_B)) {
                    jdbc.update("DELETE FROM audit_log WHERE target_type = 'USER' AND target_id = ?", id);
                    jdbc.update("DELETE FROM user_role WHERE user_id = ?", id);
                    jdbc.update("DELETE FROM `user` WHERE id = ?", id);
                }
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }
}
