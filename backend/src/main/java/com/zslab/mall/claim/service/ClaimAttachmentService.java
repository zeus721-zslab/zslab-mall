package com.zslab.mall.claim.service;

import com.zslab.mall.attachment.entity.Attachment;
import com.zslab.mall.attachment.repository.AttachmentRepository;
import com.zslab.mall.claim.controller.response.ClaimAttachmentUploadResponse;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.file.controller.response.ImageUploadResponse;
import com.zslab.mall.file.service.ImageFormat;
import com.zslab.mall.file.service.ImageUploadService;
import com.zslab.mall.file.service.UploadLimits;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 반품 사진 첨부 Application Service(Track 81-B D-171·R2). 구매자가 클레임 요청 전에 사진을 올리면(미연결 Attachment·uploaded_by 기록)
 * 클레임 요청 본문의 attachmentIds로 연결한다. 파일 검증(형식·크기)은 {@link ImageUploadService}를 재사용하고 장수 상한만 본 서비스가 낮춘다.
 *
 * <p><b>소유권·재사용 차단</b>: 요청자가 올린 파일(uploaded_by = buyerId)만 연결할 수 있고, 이미 대상에 연결된 첨부(target_id NOT NULL)는
 * 다시 쓸 수 없다. 미존재·타인·연결됨·중복 id는 모두 400이다(클레임 존재 은닉과 무관한 입력 오류). 연결은 {@code target_id IS NULL} 조건부
 * UPDATE라 동시 재사용도 1건만 성공한다(D-172).
 */
@Slf4j
@Service
@Transactional
public class ClaimAttachmentService {

    /** 클레임 1건당(=업로드 요청 1회당) 사진 상한. */
    public static final int MAX_ATTACHMENTS = 5;
    /** 구매자 첨부 파일당 상한 5MB(D-174·관리자 상품 이미지 10MB와 분리). */
    static final long MAX_ATTACHMENT_FILE_SIZE = 5L * 1024 * 1024;
    /** 사용자별 미연결 첨부 보유 상한(D-174·기존 미연결 + 이번 요청 장수가 초과하면 400). */
    static final int MAX_UNLINKED_PER_USER = 20;
    private static final UploadLimits ATTACHMENT_LIMITS = new UploadLimits(MAX_ATTACHMENTS, MAX_ATTACHMENT_FILE_SIZE);
    /** 멀티파트 경계·파트 헤더 여유(D-230). */
    private static final long MULTIPART_OVERHEAD_BYTES = 1024L * 1024;
    /**
     * 구매자 첨부 요청 합계 상한 = 파일당 5MB × 5장 + 여유 1MB = 26MB(D-230). 파싱 뒤 앱 검증만으로는 전역 max-request-size(220MB)까지
     * 수신·임시 저장되므로 {@code ClaimAttachmentRequestSizeFilter}가 멀티파트 파싱 전에 Content-Length로 거부한다.
     */
    public static final long MAX_ATTACHMENT_REQUEST_BYTES = MAX_ATTACHMENT_FILE_SIZE * MAX_ATTACHMENTS + MULTIPART_OVERHEAD_BYTES;

    private static final String CLAIM_DIRECTORY = "claims";

    private final ImageUploadService imageUploadService;
    private final AttachmentRepository attachmentRepository;

    public ClaimAttachmentService(ImageUploadService imageUploadService, AttachmentRepository attachmentRepository) {
        this.imageUploadService = imageUploadService;
        this.attachmentRepository = attachmentRepository;
    }

    /**
     * 구매자 사진 업로드. 성공 파일마다 미연결 Attachment(CLAIM·target_id NULL·uploaded_by)를 저장한다.
     *
     * <p>D-230: 이미지 처리는 동시 디코딩 차례를 최대 대기 한도까지 기다리므로 DB 커넥션을 쥔 트랜잭션 밖에서 한다(대기가 커넥션 풀을
     * 고갈시키지 않게). 미연결 보유 수 조회는 단독 읽기, 첨부 행 저장은 {@code saveAll} 한 트랜잭션으로 전부 저장·전부 롤백을 유지한다.
     *
     * @throws MalformedRequestException 파일 없음·{@value #MAX_ATTACHMENTS}장 초과·미연결 보유 {@value #MAX_UNLINKED_PER_USER}개 초과(400)
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ClaimAttachmentUploadResponse upload(Long buyerId, List<MultipartFile> files) {
        if (files != null && files.size() > MAX_ATTACHMENTS) {
            throw new MalformedRequestException("반품 사진은 최대 " + MAX_ATTACHMENTS + "장까지 첨부할 수 있습니다.");
        }
        long unlinked = attachmentRepository.countByTargetTypeAndTargetIdIsNullAndUploadedBy(PolymorphicTargetType.CLAIM, buyerId);
        if (files != null && unlinked + files.size() > MAX_UNLINKED_PER_USER) {
            // D-174: 연결하지 않은 업로드를 무제한 쌓는 디스크 소모를 막는다. 미연결 첨부는 24시간 후 정리 배치가 지운다.
            throw new MalformedRequestException("연결되지 않은 첨부가 너무 많습니다(최대 " + MAX_UNLINKED_PER_USER
                    + "개·현재 " + unlinked + "개). 클레임 요청에 연결하거나 24시간 후 다시 시도해 주세요.");
        }
        ImageUploadResponse uploaded = imageUploadService.upload(files, CLAIM_DIRECTORY, ATTACHMENT_LIMITS);
        List<Attachment> attachments = new ArrayList<>();
        for (ImageUploadResponse.Item item : uploaded.results()) {
            if (item.success()) {
                attachments.add(Attachment.createUnlinked(
                        PolymorphicTargetType.CLAIM, buyerId, item.fileName(), item.url(), mimeTypeOf(item.url()), item.size()));
            }
        }
        List<Attachment> saved = attachmentRepository.saveAll(attachments);
        List<ClaimAttachmentUploadResponse.Item> results = new ArrayList<>();
        Iterator<Attachment> savedAttachments = saved.iterator();
        for (ImageUploadResponse.Item item : uploaded.results()) {
            results.add(item.success()
                    ? ClaimAttachmentUploadResponse.Item.success(item, savedAttachments.next())
                    : ClaimAttachmentUploadResponse.Item.failure(item));
        }
        log.info("[ClaimAttachment] 업로드 buyerId={} requested={} success={}", buyerId, uploaded.results().size(),
                uploaded.successCount());
        return ClaimAttachmentUploadResponse.of(results);
    }

    /**
     * 클레임 연결 대상 첨부를 검증해 요청 순서대로 돌려준다.
     *
     * @throws MalformedRequestException 중복 id·미존재·타인 업로드·이미 연결됨(400)
     */
    public List<Attachment> resolveForLink(Long buyerId, List<String> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return List.of();
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
            if (attachment == null || !buyerId.equals(attachment.getUploadedBy())) {
                // 타인 파일도 미존재와 같은 메시지로 은닉한다(파일 존재 여부 추측 차단).
                throw new MalformedRequestException("첨부를 찾을 수 없습니다: " + attachmentId);
            }
            if (attachment.isLinked()) {
                throw new MalformedRequestException("이미 다른 클레임에 연결된 첨부입니다: " + attachmentId);
            }
            ordered.add(attachment);
        }
        return ordered;
    }

    /**
     * 검증된 첨부를 클레임에 순서대로 연결한다(D-172·Q5 조건부 UPDATE). {@link #resolveForLink}가 읽은 시점 이후 다른 요청이 먼저 연결했으면
     * 영향 행이 0이므로 400을 던져 클레임 생성까지 같은 TX로 롤백한다(같은 첨부로 서로 다른 품목 동시 반품 요청 → 1건만 성공).
     *
     * @throws MalformedRequestException 이미 다른 클레임에 연결됐거나 요청자 소유가 아닌 첨부(영향 행 0)
     */
    public void link(List<Attachment> attachments, Long claimId, Long buyerId) {
        for (int index = 0; index < attachments.size(); index++) {
            Attachment attachment = attachments.get(index);
            int affected = attachmentRepository.linkIfUnlinked(attachment.getId(), buyerId, claimId, index);
            if (affected == 0) {
                throw new MalformedRequestException("이미 다른 클레임에 연결된 첨부입니다: " + attachment.getPublicId());
            }
        }
    }

    /** 클레임 1건의 첨부 URL(순서 보존). */
    @Transactional(readOnly = true)
    public List<String> urlsOf(Long claimId) {
        return attachmentRepository.findByTargetTypeAndTargetIdOrderByDisplayOrderAsc(PolymorphicTargetType.CLAIM, claimId).stream()
                .map(Attachment::getFilePath)
                .toList();
    }

    private static String mimeTypeOf(String url) {
        int dot = url.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        return ImageFormat.fromExtension(url.substring(dot + 1)).map(ImageFormat::contentType).orElse(null);
    }
}
