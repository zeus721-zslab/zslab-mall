package com.zslab.mall.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Spring MVC 표준 예외의 GEH 매핑 통합 테스트(Track 95 D-201·LT-27). 인증 필터가 선행하면 401이 매핑 유무를 가리므로(LT-17)
 * permitAll 공개 경로만 무인증으로 친다. 수정 전에는 셋 다 {@code Exception} catch-all 500 INTERNAL_ERROR였다(RED 선증명).
 * detail은 요청 경로·내부 메시지를 노출하지 않는 고정 문구다.
 */
@AutoConfigureMockMvc
class StandardErrorMappingIntegrationTest extends AbstractIntegrationTest {

    private static final String ERROR_TYPE_BASE = "https://zslab-mall.duckdns.org/errors/";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("T1 공개 경로 매핑 부재(GET /api/v1/products/…/extra) → 404 RESOURCE_NOT_FOUND·고정 detail·RFC7807")
    void unmappedPublicPath_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/products/no-such-path/extra/more"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("요청한 리소스를 찾을 수 없습니다."))
                .andExpect(jsonPath("$.type").value(ERROR_TYPE_BASE + "resource-not-found"))
                .andExpect(jsonPath("$.instance").value("/api/v1/products/no-such-path/extra/more"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("T2 미지원 메서드(GET /api/v1/auth/login·POST 전용) → 405 METHOD_NOT_ALLOWED·고정 detail")
    void unsupportedMethod_returns405() throws Exception {
        mockMvc.perform(get("/api/v1/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.detail").value("지원하지 않는 HTTP 메서드입니다."))
                .andExpect(jsonPath("$.type").value(ERROR_TYPE_BASE + "method-not-allowed"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("T3 미지원 Content-Type(POST /api/v1/auth/login text/plain) → 415 UNSUPPORTED_MEDIA_TYPE·고정 detail")
    void unsupportedMediaType_returns415() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.detail").value("지원하지 않는 Content-Type입니다."))
                .andExpect(jsonPath("$.type").value(ERROR_TYPE_BASE + "unsupported-media-type"))
                .andExpect(jsonPath("$.traceId").exists());
    }
}
