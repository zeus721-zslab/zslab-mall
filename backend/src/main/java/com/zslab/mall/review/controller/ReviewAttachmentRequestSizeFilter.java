package com.zslab.mall.review.controller;

import com.zslab.mall.review.exception.ReviewAttachmentLengthRequiredException;
import com.zslab.mall.review.exception.ReviewAttachmentRequestTooLargeException;
import com.zslab.mall.review.service.ReviewAttachmentService;
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
 * 리뷰 사진 업로드 요청 합계 상한(Track 106-1·클레임 첨부 {@code ClaimAttachmentRequestSizeFilter}와 같은 방식). 멀티파트는 핸들러 호출 전에
 * 전역 max-request-size(220MB)까지 파싱·임시 저장되므로 이 경로만 파싱 전에 Content-Length로 413을 낸다. 길이 없는 청크 전송은 411.
 *
 * <p>TraceIdFilter 바로 뒤(보안 필터 체인보다 앞)에서 동작한다. MockMvc는 서블릿 멀티파트 파서를 거치지 않아 테스트는 Content-Length
 * 판정만 검증한다.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ReviewAttachmentRequestSizeFilter extends OncePerRequestFilter {

    static final String PHOTO_UPLOAD_PATH = "/api/v1/reviews/attachments";
    private static final long UNKNOWN_CONTENT_LENGTH = -1L;

    private final HandlerExceptionResolver handlerExceptionResolver;

    public ReviewAttachmentRequestSizeFilter(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver) {
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 디코딩한 경로로 비교한다 — MVC 매칭은 디코딩 값이라 인코딩 변형이 필터만 건너뛰고 핸들러에 닿지 않게 한다(D-230 선례).
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        return !(HttpMethod.POST.matches(request.getMethod()) && PHOTO_UPLOAD_PATH.equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength == UNKNOWN_CONTENT_LENGTH && request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) {
            log.warn("[ReviewAttachment] Content-Length 없는 사진 업로드 거부(411)");
            handlerExceptionResolver.resolveException(request, response, null,
                    new ReviewAttachmentLengthRequiredException("요청 길이(Content-Length)가 필요합니다."));
            return;
        }
        if (contentLength > ReviewAttachmentService.MAX_PHOTO_REQUEST_BYTES) {
            log.warn("[ReviewAttachment] 사진 업로드 상한 초과 거부(413) contentLength={}", contentLength);
            handlerExceptionResolver.resolveException(request, response, null,
                    new ReviewAttachmentRequestTooLargeException("리뷰 사진 업로드 용량 한도를 초과했습니다(1장씩·파일당 5MB)."));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
