package com.zslab.mall.claim.controller;

import com.zslab.mall.claim.exception.ClaimAttachmentLengthRequiredException;
import com.zslab.mall.claim.exception.ClaimAttachmentRequestTooLargeException;
import com.zslab.mall.claim.service.ClaimAttachmentService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.UrlPathHelper;

/**
 * 구매자 첨부 업로드 요청 합계 상한(D-230). 멀티파트는 DispatcherServlet이 핸들러 호출 전에 전역 max-request-size(220MB)까지 파싱·임시
 * 저장하므로, 이 경로만 파싱 전에 Content-Length로 413을 낸다(관리자·셀러 업로드 한도는 불변). 길이가 없는 청크 전송은 판정을
 * 우회하므로 411로 거부한다. 응답 포맷을 한곳에 두기 위해 예외를 {@link HandlerExceptionResolver}로 넘겨 GlobalExceptionHandler가 쓴다.
 *
 * <p>TraceIdFilter 바로 뒤(보안 필터 체인보다 앞)에서 동작해 본문을 읽기 전에 끝낸다. MockMvc는 서블릿 멀티파트 파서를 거치지 않아
 * 테스트는 이 필터의 Content-Length 판정만 검증한다.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ClaimAttachmentRequestSizeFilter extends OncePerRequestFilter {

    static final String ATTACHMENT_UPLOAD_PATH = "/api/v1/claims/attachments";
    private static final long UNKNOWN_CONTENT_LENGTH = -1L;

    private final HandlerExceptionResolver handlerExceptionResolver;

    public ClaimAttachmentRequestSizeFilter(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver) {
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 원시 URI가 아니라 디코딩한 경로로 비교한다 — MVC 매칭은 디코딩 값이라 "attachment%73" 같은 인코딩 변형이 필터만 건너뛰고 핸들러에 닿는다.
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        return !(HttpMethod.POST.matches(request.getMethod()) && ATTACHMENT_UPLOAD_PATH.equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        // 길이 없음 + Transfer-Encoding = 청크 전송(본문 크기 미상). 둘 다 없으면 HTTP/1.1상 본문이 비어 판정할 것이 없다.
        if (contentLength == UNKNOWN_CONTENT_LENGTH && request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) {
            log.warn("[ClaimAttachment] Content-Length 없는 첨부 업로드 거부(411)");
            handlerExceptionResolver.resolveException(request, response, null,
                    new ClaimAttachmentLengthRequiredException("요청 길이(Content-Length)가 필요합니다."));
            return;
        }
        if (contentLength > ClaimAttachmentService.MAX_ATTACHMENT_REQUEST_BYTES) {
            log.warn("[ClaimAttachment] 첨부 업로드 합계 상한 초과 거부(413) contentLength={}", contentLength);
            handlerExceptionResolver.resolveException(request, response, null,
                    new ClaimAttachmentRequestTooLargeException("반품 사진 업로드 용량 한도를 초과했습니다(파일당 5MB·최대 5장)."));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
