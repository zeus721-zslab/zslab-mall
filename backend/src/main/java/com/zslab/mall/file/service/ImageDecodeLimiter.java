package com.zslab.mall.file.service;

import com.zslab.mall.file.exception.UploadBusyException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 이미지 업로드 힙 상한(D-230). 두 한도를 한 곳에서 관리하고 획득 순서를 입장권 → 디코딩 차례로 고정한다.
 * <ul>
 *   <li>파일 처리 입장권 {@code upload.max-inflight-files}(기본 5 = 처리 1 + 대기 4): 파일 바이트를 힙에 올리기 전에 대기 없이 즉시
 *       판정한다. 디코딩 차례를 기다리는 요청마다 파일 바이트(최대 10MB)를 쥐고 있어 대기자 수에 비례해 힙이 늘던 문제를 막는다.</li>
 *   <li>디코딩 차례 {@code upload.max-concurrent-decodes}(기본 1 — 힙 밖 progressive JPEG 메모리까지 최대 사용량 고정): 이미지당 예산만으로는
 *       동시 요청이 겹칠 때 힙(512m)을 넘을 수 있어 공정 세마포어로 묶고, 대기가 {@code upload.decode-wait-timeout}을 넘으면 거부한다.</li>
 * </ul>
 * 두 경우 모두 {@link UploadBusyException}(503)이다.
 */
@Slf4j
@Component
public class ImageDecodeLimiter {

    static final String UPLOAD_BUSY_MESSAGE = "이미지 처리 요청이 많습니다. 잠시 후 다시 시도해 주세요.";

    private final Semaphore admissions;
    private final Semaphore permits;
    private final Duration waitTimeout;

    public ImageDecodeLimiter(
            @Value("${upload.max-inflight-files}") int maxInflightFiles,
            @Value("${upload.max-concurrent-decodes}") int maxConcurrentDecodes,
            @Value("${upload.decode-wait-timeout}") Duration waitTimeout) {
        this.admissions = new Semaphore(maxInflightFiles);
        this.permits = new Semaphore(maxConcurrentDecodes, true);
        this.waitTimeout = waitTimeout;
    }

    /**
     * 파일 처리 입장권을 대기 없이 얻는다. 성공하면 호출자는 반드시 finally에서 {@link #releaseFile()}을 호출한다.
     *
     * @throws UploadBusyException 입장권 소진(503·즉시)
     */
    public void admitFile() {
        if (!admissions.tryAcquire()) {
            log.warn("[ImageUpload] 파일 처리 입장 한도 초과(503)");
            throw new UploadBusyException(UPLOAD_BUSY_MESSAGE);
        }
    }

    public void releaseFile() {
        admissions.release();
    }

    /**
     * 디코딩 차례를 얻는다. 입장권을 쥔 상태에서만 호출한다. 성공하면 호출자는 반드시 finally에서 {@link #release()}를 호출한다.
     *
     * @throws UploadBusyException 대기 시간 초과 또는 대기 중 인터럽트(503·인터럽트 플래그는 복원)
     */
    public void acquire() {
        try {
            if (!permits.tryAcquire(waitTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("[ImageUpload] 디코딩 대기 시간 초과(503) waitMs={}", waitTimeout.toMillis());
                throw new UploadBusyException(UPLOAD_BUSY_MESSAGE);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("[ImageUpload] 디코딩 대기 중 인터럽트(503)");
            throw new UploadBusyException(UPLOAD_BUSY_MESSAGE);
        }
    }

    public void release() {
        permits.release();
    }

    /** 남은 디코딩 차례 수(누수 검증용). */
    public int availablePermits() {
        return permits.availablePermits();
    }

    /** 남은 입장권 수(누수 검증용). */
    public int availableAdmissions() {
        return admissions.availablePermits();
    }
}
