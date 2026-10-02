package com.zslab.mall.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Spring MVC 표준 예외의 GEH 매핑 통합 테스트(Track 95 D-201·LT-27). 인증 필터가 선행하면 401이 매핑 유무를 가리므로(LT-17)
 * permitAll 공개 경로만 무인증으로 친다. 수정 전에는 셋 다 {@code Exception} catch-all 500 INTERNAL_ERROR였다(RED 선증명).
 * detail은 요청 경로·내부 메시지를 노출하지 않는 고정 문구다.
 */
@AutoConfigureMockMvc
class StandardErrorMappingIntegrationTest extends AbstractIntegrationTest {

    private static final String ERROR_TYPE_BASE = "https://zslab-mall.duckdns.org/errors/";
    private static final long ADMIN_ID = 77100L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;

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
    @DisplayName("T2 미지원 메서드(GET /api/v1/auth/buyer/login·POST 전용) → 405 METHOD_NOT_ALLOWED·고정 detail")
    void unsupportedMethod_returns405() throws Exception {
        mockMvc.perform(get("/api/v1/auth/buyer/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.detail").value("지원하지 않는 HTTP 메서드입니다."))
                .andExpect(jsonPath("$.type").value(ERROR_TYPE_BASE + "method-not-allowed"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("T3 미지원 Content-Type(POST /api/v1/users 가입 text/plain) → 415 UNSUPPORTED_MEDIA_TYPE·고정 detail")
    void unsupportedMediaType_returns415() throws Exception {
        mockMvc.perform(post("/api/v1/users").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.detail").value("지원하지 않는 Content-Type입니다."))
                .andExpect(jsonPath("$.type").value(ERROR_TYPE_BASE + "unsupported-media-type"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("T4 깨진 JSON 본문(POST /api/v1/users) → 400 MALFORMED_REQUEST·고정 detail(파서 원문 미포함 · SEC-25)")
    void malformedJsonBody_returnsFixedDetail() throws Exception {
        mockMvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON).content("{\"email\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").value("요청 본문을 읽을 수 없습니다. 형식을 확인해 주세요."));
    }

    @Test
    @DisplayName("T5 쿼리 타입 불일치(GET /api/v1/products?page=abc) → 400 MALFORMED_REQUEST·고정 detail(변환 원문 미포함 · SEC-25)")
    void typeMismatchParam_returnsFixedDetail() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").value("요청 값의 형식이 올바르지 않습니다."));
    }

    @Test
    @DisplayName("T6 필수 쿼리 누락(GET /api/v1/faqs/suggest · q 없음) → 400 MALFORMED_REQUEST·고정 detail(파라미터 원문 미포함 · SEC-25)")
    void missingRequestParameter_returnsFixedDetail() throws Exception {
        mockMvc.perform(get("/api/v1/faqs/suggest"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").value("필수 요청 파라미터가 누락되었습니다."));
    }

    @Test
    @DisplayName("T7 필수 파트 누락(POST /api/v1/admin/files/images · files 없음) → 400 MALFORMED_REQUEST·고정 detail(파트 원문 미포함 · SEC-25)")
    void missingRequestPart_returnsFixedDetail() throws Exception {
        // 인증 필터가 401로 가리지 않도록 관리자 쿠키를 싣는다(업로드는 공개 경로가 아님). 파트 이름만 다른 파일 1개를 보낸다.
        mockMvc.perform(multipart("/api/v1/admin/files/images")
                        .file(new MockMultipartFile("other", "a.png", "image/png", new byte[] {1}))
                        .with(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").value("필수 요청 항목이 누락되었습니다."));
    }
}
