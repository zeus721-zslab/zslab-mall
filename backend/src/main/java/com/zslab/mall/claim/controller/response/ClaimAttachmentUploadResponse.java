package com.zslab.mall.claim.controller.response;

import com.zslab.mall.attachment.entity.Attachment;
import com.zslab.mall.file.controller.response.ImageUploadResponse;
import java.util.List;

/**
 * 반품 사진 업로드 결과(Track 81-B D-171). {@link ImageUploadResponse}와 같은 파일별 부분 실패 방식이며 성공 항목은 클레임 요청에 넘길
 * {@code attachmentId}(att_)를 추가로 담는다. HTTP는 항상 200.
 */
public record ClaimAttachmentUploadResponse(List<Item> results, int successCount, int failureCount) {

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

    public static ClaimAttachmentUploadResponse of(List<Item> results) {
        int successCount = (int) results.stream().filter(Item::success).count();
        return new ClaimAttachmentUploadResponse(results, successCount, results.size() - successCount);
    }
}
