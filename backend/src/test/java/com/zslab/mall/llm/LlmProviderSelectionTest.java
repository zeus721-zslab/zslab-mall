package com.zslab.mall.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.llm.adapter.LlmPort;
import com.zslab.mall.llm.adapter.MockLlmAdapter;
import com.zslab.mall.llm.config.LlmProviderGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * LLM 제공자 선택(Track 106-1·D-209 패턴 + 허용 값 검증기). 미설정·mock은 Mock 어댑터로 기동하고, 허용 목록 밖 값은 조용히 기동하지 않고
 * 기동 자체가 실패해야 한다(비동기 소비자라 주입 실패로는 드러나지 않는 경우를 막는다). DB 없이 두 빈만 올린다.
 */
class LlmProviderSelectionTest {

    private static final String PROVIDER_KEY = "zslab.llm.provider";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LlmProviderGuard.class, MockLlmAdapter.class);

    @Test
    @DisplayName("미설정 → 기동 성공·LlmPort = MockLlmAdapter")
    void unset_startsWithMock() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(LlmPort.class);
            assertThat(context.getBean(LlmPort.class)).isInstanceOf(MockLlmAdapter.class);
        });
    }

    @Test
    @DisplayName("mock → 기동 성공·LlmPort = MockLlmAdapter")
    void mock_startsWithMock() {
        runner.withPropertyValues(PROVIDER_KEY + "=mock").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LlmPort.class)).isInstanceOf(MockLlmAdapter.class);
        });
    }

    @Test
    @DisplayName("허용 밖 값(unknown) → 기동 실패·원인에 LLM_PROVIDER와 입력값")
    void unknownProvider_failsStartup() {
        runner.withPropertyValues(PROVIDER_KEY + "=unknown").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("LLM_PROVIDER").hasStackTraceContaining("unknown");
        });
    }
}
