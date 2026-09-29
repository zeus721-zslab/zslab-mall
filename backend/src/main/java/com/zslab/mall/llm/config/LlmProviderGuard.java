package com.zslab.mall.llm.config;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code zslab.llm.provider}(env LLM_PROVIDER) 허용 값 검증기(Track 106-1·결정 7 A). D-209의 다른 외부 연동은 알 수 없는 값이면 Mock 빈이 빠지고
 * 포트를 무조건 주입받는 소비자에서 기동이 실패하지만, 리뷰 요약은 커밋 후 비동기로만 호출되므로 그 실패가 드러나지 않을 수 있다(DELIVERY_TRACKER
 * 선례). 그래서 허용 목록을 기동 시점에 직접 검증해 오타·미구현 값이면 기동을 멈춘다(DemoAccountGuard 선례 — 생성자 검증).
 *
 * <p>실제 모델 어댑터를 추가할 때 이 허용 목록에 값을 더한다.
 */
@Component
public class LlmProviderGuard {

    static final Set<String> ALLOWED_PROVIDERS = Set.of("mock");

    /**
     * @throws IllegalStateException 허용 목록 밖 값(기동 실패)
     */
    public LlmProviderGuard(@Value("${zslab.llm.provider:mock}") String provider) {
        if (!ALLOWED_PROVIDERS.contains(provider)) {
            throw new IllegalStateException("zslab.llm.provider(LLM_PROVIDER) 값이 허용 목록에 없습니다: '" + provider
                    + "' (허용: " + ALLOWED_PROVIDERS + ")");
        }
    }
}
