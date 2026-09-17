package com.zslab.mall.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.task.TaskSchedulingProperties;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 스케줄링 스레드 풀 설정 반영 검증(검수 5단계·D-175). application.yml {@code spring.task.scheduling}이 자동 구성
 * {@link ThreadPoolTaskScheduler} 빈에 실제 적용됐는지(기본 1스레드 직렬 실행 탈피)를 빈 값으로 단언한다.
 */
class SchedulingConfigIntegrationTest extends AbstractIntegrationTest {

    /** 등록된 @Scheduled 7건 이상으로 잡은 풀 크기(application.yml과 동일 값·불일치 시 설정 누락 신호). */
    private static final int EXPECTED_POOL_SIZE = 8;
    private static final String EXPECTED_THREAD_NAME_PREFIX = "zslab-sched-";

    @Autowired
    private TaskSchedulingProperties taskSchedulingProperties;
    @Autowired
    private ThreadPoolTaskScheduler taskScheduler;

    @Test
    @DisplayName("spring.task.scheduling pool.size=8·thread-name-prefix가 ThreadPoolTaskScheduler 빈에 반영된다")
    void schedulingPool_configuredFromYaml() {
        assertThat(taskSchedulingProperties.getPool().getSize()).isEqualTo(EXPECTED_POOL_SIZE);
        assertThat(taskSchedulingProperties.getThreadNamePrefix()).isEqualTo(EXPECTED_THREAD_NAME_PREFIX);
        assertThat(taskScheduler.getPoolSize()).isEqualTo(EXPECTED_POOL_SIZE);
        assertThat(taskScheduler.getThreadNamePrefix()).isEqualTo(EXPECTED_THREAD_NAME_PREFIX);
    }
}
