package com.zslab.mall.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Security 필터 계층(인증 진입점·인가 거부)의 오류 응답을 {@link com.zslab.mall.common.web.GlobalExceptionHandler}와
 * 동일한 RFC7807 ProblemDetail(code·traceId 속성) 포맷으로 직렬화한다(Track 31 Phase 3·Track 33 P5 프로파일 무제약 승격).
 * {@code @RestControllerAdvice}는 필터 계층 예외를 잡지 못하므로 본 핸들러가 동일 포맷을 재현해 401/403 응답 본문 계약
 * (예: {@code $.code=UNAUTHENTICATED})을 유지한다. GlobalExceptionHandler와의 포맷 일치를 위해 TYPE_BASE·toTitle을 의도적으로 병기한다.
 *
 * <p>단일 SecurityFilterChain(전 프로파일)에서 JWT 필터가 전파한 인증 실패와 AuthorizationFilter의 인가 거부를 각각
 * authenticationEntryPoint(401)·accessDeniedHandler(403)로 위임받아 응답한다.
 */
@Component
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String TYPE_BASE = "https://zslab-mall.duckdns.org/errors/";
    private static final String CODE_UNAUTHENTICATED = "UNAUTHENTICATED";
    private static final String CODE_FORBIDDEN = "FORBIDDEN";

    private final ObjectMapper objectMapper;

    public SecurityErrorHandler(ObjectMapper objectMapper) {
        // ProblemDetail의 properties(code·traceId)를 최상위로 평탄화하는 mixin을 보장한다(GlobalExceptionHandler 출력과 동일 구조).
        this.objectMapper = objectMapper.copy().addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, CODE_UNAUTHENTICATED, "인증이 필요합니다.");
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, CODE_FORBIDDEN, "접근 권한이 없습니다.");
    }

    private void write(
            HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setType(URI.create(TYPE_BASE + code.toLowerCase().replace('_', '-')));
        problemDetail.setTitle(toTitle(code));
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("code", code);
        problemDetail.setProperty("traceId", MDC.get(TraceIdFilter.TRACE_ID));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problemDetail);
    }

    /** SCREAMING_SNAKE_CASE code를 "Title Case"로 변환한다(GlobalExceptionHandler.toTitle 병기). */
    private String toTitle(String code) {
        StringBuilder title = new StringBuilder();
        for (String part : code.toLowerCase().split("_")) {
            if (!part.isEmpty()) {
                title.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(' ');
            }
        }
        return title.toString().trim();
    }
}
