package com.zslab.mall.review.service;

import com.zslab.mall.attachment.entity.Attachment;
import com.zslab.mall.attachment.repository.AttachmentRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.file.controller.response.ImageUploadResponse;
import com.zslab.mall.file.service.ImageFormat;
import com.zslab.mall.file.service.ImageUploadService;
import com.zslab.mall.file.service.UploadLimits;
import com.zslab.mall.review.controller.response.ReviewAttachmentUploadResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 리뷰 사진 첨부(Track 106-1). 클레임 첨부({@code ClaimAttachmentService})와 같은 미연결 업로드 → 본문 attachmentIds 연결 방식이며, 연결 규칙
 * (작성·수정 시 전체 교체)이 달라 흐름을 복제해 분리했다. 공개 서빙이라 원본을 재인코딩해 EXIF를 지운다({@link ImageUploadService#uploadReencoded}).
 *
 * <p><b>한도</b>: 요청당 1장(운영 gateway {@code /api} 본문 10m 안에 들도록 — 요청 크기 필터 6MB)·파일당 5MB·리뷰당 5장·사용자별 미연결
 * 20개(클레임과 같은 규칙). 미연결 첨부는 24시간 뒤 정리 배치가 지운다.
 *
 * <p><b>소유권·재사용·유형 차단</b>: 요청자가 올린 REVIEW 첨부만 연결한다. 다른 리뷰에 연결된 첨부·클레임 첨부·타인 파일·중복 id는 400이다.
 * 연결은 {@code target_id IS NULL AND target_type = REVIEW} 조건부 UPDATE라 동시 재사용도 1건만 성공한다.
 */
@Slf4j
@Service
@Transactional
public class ReviewAttachmentService {

    /** 리뷰 1건당 사진 상한. */
    public static final int MAX_PHOTOS_PER_REVIEW = 5;
    /** 요청당 사진 수 — 5장 한 요청(26MB)은 운영 gateway {@code /api} 본문 한도 10m를 넘으므로 1장씩 올린다. */
    static final int MAX_FILES_PER_REQUEST = 1;
    /** 파일당 상한 5MB(구매자 첨부 기준·D-174). */
    static final long MAX_PHOTO_FILE_SIZE = 5L * 1024 * 1024;
    /** 사용자별 미연결 첨부 보유 상한(클레임 첨부와 같은 규칙·D-174). */
    static final int MAX_UNLINKED_PER_USER = 20;
    private static final UploadLimits PHOTO_LIMITS = new UploadLimits(MAX_FILES_PER_REQUEST, MAX_PHOTO_FILE_SIZE);
    /** 멀티파트 경계·파트 헤더 여유(D-230). */
    private static final long MULTIPART_OVERHEAD_BYTES = 1024L * 1024;
    /** 요청 합계 상한 = 파일당 5MB × 1장 + 여유 1MB = 6MB. {@code ReviewAttachmentRequestSizeFilter}가 멀티파트 파싱 전에 판정한다. */
    public static final long MAX_PHOTO_REQUEST_BYTES = MAX_PHOTO_FILE_SIZE * MAX_FILES_PER_REQUEST + MULTIPART_OVERHEAD_BYTES;

    private static final String REVIEW_DIRECTORY = "reviews";

    private final ImageUploadService imageUploadService;
    private final AttachmentRepository attachmentRepository;

    public ReviewAttachmentService(ImageUploadService imageUploadService, AttachmentRepository attachmentRepository) {
        this.imageUploadService = imageUploadService;
        this.attachmentRepository = attachmentRepository;
    }

    /**
     * 리뷰 사진 업로드. 성공 파일마다 미연결 Attachment(REVIEW·target_id NULL·uploaded_by)를 저장한다. 이미지 처리는 디코딩 차례를 기다리므로
     * 커넥션을 쥔 트랜잭션 밖에서 한다(D-230 — 클레임 첨부와 같다).
     *
     * @throws MalformedRequestException 파일 없음·{@value #MAX_FILES_PER_REQUEST}장 초과·미연결 보유 {@value #MAX_UNLINKED_PER_USER}개 초과(400)
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReviewAttachmentUploadResponse upload(Long buyerId, List<MultipartFile> files) {
        if (files != null && files.size() > MAX_FILES_PER_REQUEST) {
            throw new MalformedRequestException("리뷰 사진은 한 번에 " + MAX_FILES_PER_REQUEST + "장씩 올릴 수 있습니다.");
        }
        long unlinked = attachmentRepository.countByTargetTypeAndTargetIdIsNullAndUploadedBy(PolymorphicTargetType.REVIEW, buyerId);
        if (files != null && unlinked + files.size() > MAX_UNLINKED_PER_USER) {
            throw new MalformedRequestException("연결되지 않은 사진이 너무 많습니다(최대 " + MAX_UNLINKED_PER_USER
                    + "개·현재 " + unlinked + "개). 리뷰에 연결하거나 24시간 후 다시 시도해 주세요.");
        }
        ImageUploadResponse uploaded = imageUploadService.uploadReencoded(files, REVIEW_DIRECTORY, PHOTO_LIMITS);
        List<Attachment> saved;
        try {
            List<Attachment> attachments = new ArrayList<>();
            for (ImageUploadResponse.Item item : uploaded.results()) {
                if (item.success()) {
                    attachments.add(Attachment.createUnlinked(
                            PolymorphicTargetType.REVIEW, buyerId, item.fileName(), item.url(), mimeTypeOf(item.url()), item.size()));
                }
            }
            saved = attachmentRepository.saveAll(attachments);
        } catch (RuntimeException saveFailure) {
            // 행이 없으면 정리 배치(행 기준)가 파일을 찾지 못한다 → 이번 요청 저장분을 여기서 지운다(D-232·클레임 첨부와 같다).
            uploaded.results().stream()
                    .filter(ImageUploadResponse.Item::success)
                    .forEach(item -> imageUploadService.deleteByUrl(item.url()));
            throw saveFailure;
        }
        List<ReviewAttachmentUploadResponse.Item> results = new ArrayList<>();
        Iterator<Attachment> savedAttachments = saved.iterator();
        for (ImageUploadResponse.Item item : uploaded.results()) {
            results.add(item.success()
                    ? ReviewAttachmentUploadResponse.Item.success(item, savedAttachments.next())
                    : ReviewAttachmentUploadResponse.Item.failure(item));
        }
        log.info("[ReviewAttachment] 업로드 buyerId={} requested={} success={}", buyerId, uploaded.results().size(),
                uploaded.successCount());
        return ReviewAttachmentUploadResponse.of(results);
    }

    /**
     * 리뷰에 연결할 사진을 검증해 요청 순서대로 돌려준다.
     *
     * @param reviewId 수정 대상 리뷰 id(이미 이 리뷰에 연결된 사진은 다시 보내도 된다). 작성이면 null
     * @throws MalformedRequestException {@value #MAX_PHOTOS_PER_REVIEW}장 초과·중복 id·미존재·타인 업로드·리뷰 사진 아님·다른 리뷰에 연결됨(400)
     */
    public List<Attachment> resolveForLink(Long buyerId, List<String> attachmentIds, Long reviewId) {
        if (attachmentIds.isEmpty()) {
            return List.of();
        }
        if (attachmentIds.size() > MAX_PHOTOS_PER_REVIEW) {
            throw new MalformedRequestException("리뷰 사진은 최대 " + MAX_PHOTOS_PER_REVIEW + "장까지 첨부할 수 있습니다.");
        }
        Set<String> distinct = new HashSet<>(attachmentIds);
        if (distinct.size() != attachmentIds.size()) {
            throw new MalformedRequestException("attachmentIds에 중복된 첨부 id가 있습니다.");
        }
        Map<String, Attachment> found = attachmentRepository.findByPublicIdIn(distinct).stream()
                .collect(Collectors.toMap(Attachment::getPublicId, Function.identity()));
        List<Attachment> ordered = new ArrayList<>();
        for (String attachmentId : attachmentIds) {
            Attachment attachment = found.get(attachmentId);
            if (attachment == null || !buyerId.equals(attachment.getUploadedBy())
                    || attachment.getTargetType() != PolymorphicTargetType.REVIEW) {
                // 타인 파일·클레임 첨부도 미존재와 같은 메시지로 은닉한다(파일 존재 여부 추측 차단).
                throw new MalformedRequestException("첨부를 찾을 수 없습니다: " + attachmentId);
            }
            if (attachment.isLinked() && !Objects.equals(attachment.getTargetId(), reviewId)) {
                throw new MalformedRequestException("이미 다른 리뷰에 연결된 사진입니다: " + attachmentId);
            }
            ordered.add(attachment);
        }
        return ordered;
    }

    /**
     * 리뷰의 사진을 요청 목록으로 통째로 바꾼다(작성·수정 공용). 기존 연결을 모두 푼 뒤 요청 순서대로 조건부 연결한다 — 빠진 사진은 미연결이 되어
     * 공개 서빙 404·정리 배치 대상이 된다. {@link #resolveForLink} 이후 다른 요청이 먼저 연결했거나 정리 배치가 지웠으면 영향 행 0 → 400
     * (리뷰 변경까지 롤백).
     *
     * @throws MalformedRequestException 그 사이 다른 리뷰에 연결됐거나 정리된 사진(영향 행 0)
     */
    public void replaceLinks(List<Attachment> attachments, Long reviewId, Long buyerId) {
        attachmentRepository.unlinkAll(PolymorphicTargetType.REVIEW, reviewId);
        for (int index = 0; index < attachments.size(); index++) {
            Attachment attachment = attachments.get(index);
            int affected = attachmentRepository.linkIfUnlinked(attachment.getId(), buyerId, PolymorphicTargetType.REVIEW, reviewId, index);
            if (affected == 0) {
                throw new MalformedRequestException("사용할 수 없는 사진입니다. 다시 올려 주세요: " + attachment.getPublicId());
            }
        }
    }

    private static String mimeTypeOf(String url) {
        int dot = url.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        return ImageFormat.fromExtension(url.substring(dot + 1)).map(ImageFormat::contentType).orElse(null);
    }
}
