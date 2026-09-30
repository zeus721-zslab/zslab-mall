package com.zslab.mall.inquiry.controller.request;

import com.zslab.mall.inquiry.entity.Inquiry;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 관리자 답변 등록·수정 요청(Track 106-4·PUT 1개로 덮어쓰기·106-2 셀러 답변과 같은 형식). 형식 위반 400 · 미존재·삭제 404는 서비스가 판정한다.
 *
 * @param content 답변 본문(1~1000자·공백만 불가)
 */
public record AdminInquiryAnswerRequest(
        @NotBlank @Size(max = Inquiry.MAX_ANSWER_LENGTH) String content) {
}
