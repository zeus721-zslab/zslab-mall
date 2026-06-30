package com.zslab.mall.common.web;

import com.github.f4b6a3.ulid.UlidCreator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청별 traceId(ULID)를 MDC와 응답 헤더(X-Trace-Id)에 주입한다(§14·D-48). 로그 패턴 {@code [%X{traceId}]}로 출력된다
 * (application.yml {@code logging.pattern.level}). 요청 종료 시 MDC를 정리해 스레드 재사용 시 누수를 막는다.
 *
 * <p>correlationId는 traceId와 동일 값으로 동시 주입한다(D-100 Q3 β′·Q13 α·요청 기원 한정·요청 외 기원 도입 시 분리
 * 재평가). 응답 헤더는 X-Trace-Id 단독이며 correlationId는 MDC 전용이다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID = "traceId";
    public static final String CORRELATION_ID = "correlationId";
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = UlidCreator.getMonotonicUlid().toString();
        MDC.put(TRACE_ID, traceId);
        MDC.put(CORRELATION_ID, traceId); // Q13 α·요청 기원 체인 동일 값
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID);
            MDC.remove(CORRELATION_ID); // 종료조건 #3·스레드 재사용 누수 차단
        }
    }
}
