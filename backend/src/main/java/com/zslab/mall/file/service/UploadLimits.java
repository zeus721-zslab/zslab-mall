package com.zslab.mall.file.service;

/**
 * 업로드 경로별 한도(D-174). 요청당 장수·파일당 바이트를 호출부(관리자 상품 이미지·구매자 클레임 첨부)가 지정한다.
 * 형식·해상도 검증은 경로 공통이며 본 레코드는 양적 한도만 담는다.
 *
 * @param maxFiles    요청당 최대 장수
 * @param maxFileSize 파일당 최대 바이트
 */
public record UploadLimits(int maxFiles, long maxFileSize) {

    public UploadLimits {
        if (maxFiles <= 0 || maxFileSize <= 0) {
            throw new IllegalArgumentException("UploadLimits는 양수여야 합니다: maxFiles=" + maxFiles + ", maxFileSize=" + maxFileSize);
        }
    }
}
