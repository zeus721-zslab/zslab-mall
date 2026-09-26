package com.zslab.mall.file.exception;

/**
 * 이미지 디코딩 동시 실행 한도가 가득 차 대기 시간 안에 차례를 얻지 못했을 때 발생한다(D-230). 요청 전체를 거부하며 전역 예외 핸들러가
 * HTTP 503 {@code UPLOAD_BUSY}로 응답한다.
 */
public class UploadBusyException extends RuntimeException {

    public UploadBusyException(String message) {
        super(message);
    }
}
