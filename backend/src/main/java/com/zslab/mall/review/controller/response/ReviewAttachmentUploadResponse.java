package com.zslab.mall.review.controller.response;

import com.zslab.mall.attachment.entity.Attachment;
import com.zslab.mall.file.controller.response.ImageUploadResponse;
import java.util.List;

/**
 * 리뷰 사진 업로드 결과(Track 106-1). 클레임 첨부 업로드와 같은 파일별 부분 실패 방식이며 성공 항목은 리뷰 작성·수정 본문에 넘길
 * {@code attachmentId}(att_)를 담는다. HTTP는 항상 200. url·thumbnailUrl은 재인코딩본 경로이며 리뷰에 연결돼 공개되기 전까지는 서빙 404다.
 */
public record ReviewAttachmentUploadResponse(List<Item> results, int successCount, int failureCount) {

    public record Item(
            String fileName,
            boolean success,
            String attachmentId,
            String url,
            String thumbnailUrl,
            String code,
            String message) {

        public static Item success(ImageUploadResponse.Item uploaded, Attachment attachment) {
            return new Item(uploaded.fileName(), true, attachment.getPublicId(), uploaded.url(), uploaded.thumbnailUrl(), null, null);
        }

        public static Item failure(ImageUploadResponse.Item uploaded) {
            return new Item(uploaded.fileName(), false, null, null, null, uploaded.code(), uploaded.message());
        }
    }

    public static ReviewAttachmentUploadResponse of(List<Item> results) {
        int successCount = (int) results.stream().filter(Item::success).count();
        return new ReviewAttachmentUploadResponse(results, successCount, results.size() - successCount);
    }
}
