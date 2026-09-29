package com.zslab.mall.attachment.scheduler;

import com.zslab.mall.attachment.repository.AttachmentRepository;
import com.zslab.mall.attachment.service.AttachmentCleanupService;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.file.service.ImageUploadService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 유예({@link #GRACE_HOURS}시간) 경과한 미연결 첨부(클레임 반품 사진·리뷰 사진)를 주기적으로 정리하는 배치 스케줄러(D-174·OrderAutoCancelScheduler 원형).
 * 트랜잭션을 갖지 않으며 오케스트레이션만 담당한다 — 후보를 한 배치(최대 {@link #BATCH_SIZE}건) 조회한 뒤 id별로
 * {@link AttachmentCleanupService#cleanupOne}(각자 독립 트랜잭션)을 호출하고, 행 삭제가 커밋된 뒤 원본·썸네일 파일을 지운다.
 *
 * <p><b>파일 삭제 실패</b>: warn 로그만 남기고 재시도하지 않는다 — 행은 이미 없으므로 파일만 남는 경우는 기존 고아 파일 정리 이월(D-166 9)에 합류한다.
 *
 * <p><b>부분 실패 격리</b>: id 단위 try/catch로 한 건 실패가 배치 전체를 중단시키지 않는다. {@link Exception}만 흡수하고
 * {@link Error}는 전파한다.
 *
 * <p><b>발화 억제(테스트·운영 킬스위치)</b>: {@code zslab.attachment.cleanup.enabled=false}면 본 빈이 생성되지 않아 {@code @Scheduled}가
 * 비활성된다(기본 활성·다른 스케줄러 킬스위치 정합).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "zslab.attachment.cleanup.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class AttachmentCleanupScheduler {

    /** 1회 배치 처리 상한. */
    static final int BATCH_SIZE = 100;

    /** 정리 배치 실행 간격(1시간). 직전 실행 종료 후 고정 지연. */
    private static final long FIXED_DELAY_MS = 60 * 60 * 1000L;

    /** 미연결 첨부 유예(24시간). created_at이 now−유예 이전인 target_id NULL 첨부가 대상이다. */
    static final long GRACE_HOURS = 24L;

    private final AttachmentRepository attachmentRepository;
    private final AttachmentCleanupService attachmentCleanupService;
    private final ImageUploadService imageUploadService;

    /** 미연결 업로드가 생기는 대상 유형(클레임 반품 사진·리뷰 사진 Track 106-1). 유형마다 한 배치씩 처리한다. */
    private static final List<PolymorphicTargetType> CLEANUP_TARGET_TYPES =
            List.of(PolymorphicTargetType.CLAIM, PolymorphicTargetType.REVIEW);

    /**
     * 대상 유형마다 정리 후보를 한 배치 조회해 id별로 {@link AttachmentCleanupService#cleanupOne}을 호출하고, 삭제된 행의 파일을 지운다.
     */
    @Scheduled(fixedDelay = FIXED_DELAY_MS)
    public void cleanupBatch() {
        String schedulerRunId = UUID.randomUUID().toString();
        LocalDateTime threshold = LocalDateTime.now().minusHours(GRACE_HOURS);
        for (PolymorphicTargetType targetType : CLEANUP_TARGET_TYPES) {
            cleanupBatchOf(targetType, threshold, schedulerRunId);
        }
    }

    private void cleanupBatchOf(PolymorphicTargetType targetType, LocalDateTime threshold, String schedulerRunId) {
        List<Long> targetIds = attachmentRepository.findUnlinkedIdsCreatedBefore(
                targetType, threshold, PageRequest.of(0, BATCH_SIZE));

        if (targetIds.isEmpty()) {
            log.debug("[AttachmentCleanup] schedulerRunId={} targetType={} 정리 대상 없음", schedulerRunId, targetType);
            return;
        }

        int deleted = 0;
        int failed = 0;
        for (Long attachmentId : targetIds) {
            try {
                Optional<String> deletedFileUrl = attachmentCleanupService.cleanupOne(attachmentId);
                if (deletedFileUrl.isPresent()) {
                    deleted++;
                    // 행 삭제 커밋 후 파일 삭제. 실패는 warn(ImageUploadService.deleteByUrl)·재시도 없음.
                    imageUploadService.deleteByUrl(deletedFileUrl.get());
                }
            } catch (Exception exception) {
                failed++;
                log.error("[AttachmentCleanup] schedulerRunId={} cleanupOne 실패 attachmentId={} — 격리 후 진행",
                        schedulerRunId, attachmentId, exception);
            }
        }

        log.info("[AttachmentCleanup] schedulerRunId={} targetType={} 배치 완료 대상={} 삭제={} 실패={}",
                schedulerRunId, targetType, targetIds.size(), deleted, failed);
    }
}
