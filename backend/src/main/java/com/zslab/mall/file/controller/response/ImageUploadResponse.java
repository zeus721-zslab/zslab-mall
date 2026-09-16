package com.zslab.mall.file.controller.response;

import java.util.List;

/**
 * 이미지 업로드 결과(Track 77). 파일별 독립 처리·부분 실패 허용(Track 76 bulk 응답 방식 정합). 실패 항목은 code·message를 담고
 * url 계열은 null이다. HTTP는 항상 200이며 성공/실패 집계는 본 응답으로 판단한다.
 *
 * <p>url·thumbnailUrl은 동일 Origin 상대경로({@code /api/v1/files/...})다. 축소가 필요 없는(가로 ≤ 썸네일 폭) 이미지는 thumbnailUrl=url.
 */
public record ImageUploadResponse(List<Item> results, int successCount, int failureCount) {

    public record Item(
            String fileName,
            boolean success,
            String url,
            String thumbnailUrl,
            Integer width,
            Integer height,
            Long size,
            String code,
            String message) {

        public static Item success(String fileName, String url, String thumbnailUrl, int width, int height, long size) {
            return new Item(fileName, true, url, thumbnailUrl, width, height, size, null, null);
        }

        public static Item failure(String fileName, String code, String message) {
            return new Item(fileName, false, null, null, null, null, null, code, message);
        }
    }

    public static ImageUploadResponse of(List<Item> results) {
        int successCount = (int) results.stream().filter(Item::success).count();
        return new ImageUploadResponse(results, successCount, results.size() - successCount);
    }
}
