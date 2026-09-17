package com.zslab.mall.attachment.service;

import com.zslab.mall.attachment.entity.Attachment;
import com.zslab.mall.attachment.repository.AttachmentRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 미연결 첨부 정리 단건 Application Service(D-174). 업로드 후 클레임에 연결되지 않은 채 유예가 지난 Attachment 행 1건을 조건부
 * DELETE({@code target_id IS NULL} 재확인)로 지운다. 파일 삭제는 본 트랜잭션이 커밋된 뒤 호출부(스케줄러)가 반환 URL로 수행한다 —
 * 행 삭제가 롤백되는데 파일만 사라지는 상태를 막기 위해서다.
 *
 * <p><b>단건 트랜잭션 경계</b>: {@link #cleanupOne}은 첨부 1건당 독립 {@code @Transactional}이다(OrderAutoCancelService 패턴).
 * 조회~삭제 사이에 클레임 요청이 먼저 연결({@code linkIfUnlinked})했으면 조건부 DELETE 영향 행이 0이라 보존된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentCleanupService {

    private final AttachmentRepository attachmentRepository;

    /**
     * 미연결 첨부 1건을 삭제한다.
     *
     * @param attachmentId 정리 대상 첨부 id
     * @return 행이 실제로 삭제됐으면 파일 URL(file_path·호출부가 커밋 후 파일 삭제), 이미 연결·미존재면 empty
     */
    @Transactional
    public Optional<String> cleanupOne(Long attachmentId) {
        Optional<Attachment> found = attachmentRepository.findById(attachmentId);
        if (found.isEmpty() || found.get().isLinked()) {
            log.debug("[AttachmentCleanup] skip: 미존재 또는 이미 연결 attachmentId={}", attachmentId);
            return Optional.empty();
        }
        String filePath = found.get().getFilePath();
        int affected = attachmentRepository.deleteIfUnlinked(attachmentId);
        if (affected == 0) {
            // 조회~삭제 사이 연결됨(조건부 DELETE 0건) — 보존.
            log.debug("[AttachmentCleanup] skip: 삭제 직전 연결됨 attachmentId={}", attachmentId);
            return Optional.empty();
        }
        log.info("[AttachmentCleanup] 미연결 첨부 행 삭제 attachmentId={} filePath={}", attachmentId, filePath);
        return Optional.of(filePath);
    }
}
