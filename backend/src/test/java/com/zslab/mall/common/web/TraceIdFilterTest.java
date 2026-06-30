package com.zslab.mall.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Track 16 D-100 Q3 β′·Q13 α·종료조건 #3 검증 단위 테스트.
 *
 * <p><b>의도</b>: TraceIdFilter가 HTTP 요청 진입 시 traceId·correlationId 2종 MDC 동시 주입·동일 값 (Q13 α)·
 * 요청 종료 시 try-finally로 양 키 모두 정리하는지 실측한다.
 *
 * <p><b>스코프</b>: HTTP filter 단독 책임·이벤트 핸들러 진입 MDC 상속은 MdcPropagationTest 책임 분리.
 * eventName 키는 Step 3 (Notification 7건 prefix) 범위·본 테스트는 traceId·correlationId 2종 한정.
 */
class TraceIdFilterTest {

    private static final int ULID_LENGTH = 26;

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void tearDown() {
        // 단위 테스트 스레드 재사용 시 누수 차단 (filter 정상 정리와 별개 안전망).
        MDC.clear();
    }

    @Test
    @DisplayName("케이스1 traceId·correlationId 동시 주입·동일 값·X-Trace-Id 응답 헤더")
    void injects_traceId_and_correlationId_with_same_value() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MdcCapturingFilterChain chain = new MdcCapturingFilterChain();

        filter.doFilter(request, response, chain);

        // chain 진입 시점(요청 처리 중) MDC 스냅샷 검증.
        assertThat(chain.capturedTraceId).isNotNull();
        assertThat(chain.capturedTraceId).hasSize(ULID_LENGTH);
        assertThat(chain.capturedCorrelationId).isEqualTo(chain.capturedTraceId);
        assertThat(response.getHeader("X-Trace-Id")).isEqualTo(chain.capturedTraceId);
    }

    @Test
    @DisplayName("케이스2 요청 종료 시 MDC 양 키 정리 (종료조건 #3)")
    void clears_mdc_after_request() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(MDC.get(TraceIdFilter.TRACE_ID)).isNull();
        assertThat(MDC.get(TraceIdFilter.CORRELATION_ID)).isNull();
    }

    @Test
    @DisplayName("케이스3 FilterChain 예외 발생 시에도 try-finally MDC 정리 보장")
    void clears_mdc_even_when_chain_throws() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain throwingChain =
                (req, res) -> {
                    throw new RuntimeException("chain boom");
                };

        assertThatThrownBy(() -> filter.doFilter(request, response, throwingChain))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("chain boom");

        assertThat(MDC.get(TraceIdFilter.TRACE_ID)).isNull();
        assertThat(MDC.get(TraceIdFilter.CORRELATION_ID)).isNull();
    }

    /** chain 진입 시점(요청 처리 중)의 MDC 값을 캡처해 주입 동시성·동일 값을 실측하는 FilterChain. */
    private static final class MdcCapturingFilterChain implements FilterChain {

        private String capturedTraceId;
        private String capturedCorrelationId;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            this.capturedTraceId = MDC.get(TraceIdFilter.TRACE_ID);
            this.capturedCorrelationId = MDC.get(TraceIdFilter.CORRELATION_ID);
        }
    }
}
