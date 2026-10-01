package com.zslab.mall.inquiry.service;

import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.inbox.stream.InboxSignalPublisher;
import com.zslab.mall.inquiry.entity.Inquiry;
import com.zslab.mall.inquiry.exception.InquiryNotFoundException;
import com.zslab.mall.inquiry.repository.InquiryRepository;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 문의 답변 등록·수정(Track 106-4). 문의 행 락으로 구매자 수정·삭제·확인과 직렬화하고 같은 트랜잭션에서 감사(UPDATE·INQUIRY·
 * before {answer 이전 본문}(등록이면 빈 맵) / after {answer 새 본문})를 적재한다. 운영자가 쓴 답변만 싣고 구매자 문의 본문은 싣지 않는다 —
 * append-only 감사 로그에 사용자 작성 글을 복제하지 않는다(106-2 선례). 같은 본문으로 다시 저장하면 아무것도 바꾸지 않는다(감사도 없음).
 *
 * <p>감사 호출은 이 클래스에만 둔다(AuditFieldMaskingPolicyTest가 감사 호출 파일의 Map 키를 파일 단위로 스캔한다).
 */
@Slf4j
@Service
@Transactional
public class AdminInquiryCommandService {

    private final InquiryRepository inquiryRepository;
    private final AuditRecorder auditRecorder;
    private final InboxSignalPublisher inboxSignalPublisher;

    public AdminInquiryCommandService(InquiryRepository inquiryRepository, AuditRecorder auditRecorder,
            InboxSignalPublisher inboxSignalPublisher) {
        this.inquiryRepository = inquiryRepository;
        this.auditRecorder = auditRecorder;
        this.inboxSignalPublisher = inboxSignalPublisher;
    }

    /**
     * 답변 등록·수정(덮어쓰기). 수정이면 구매자 확인 시각을 비워 다시 미확인이 된다. 기존 답변과 같은 본문(trim 후)이면 무변경 204.
     *
     * @param content 답변 본문(DTO가 공백만·1000자 초과를 선검증 · trim해 저장)
     * @throws InquiryNotFoundException 미존재·삭제 문의(404 — 구매자 삭제가 먼저 커밋되면 락 해제 뒤 조회에서 빠진다)
     */
    public void answer(String inquiryPublicId, String content, AuditContext auditContext) {
        Inquiry inquiry = inquiryRepository.findByPublicIdForUpdate(inquiryPublicId)
                .orElseThrow(() -> new InquiryNotFoundException("문의를 찾을 수 없습니다: " + inquiryPublicId));
        String previousAnswer = inquiry.getAnswerContent();
        String trimmed = content.trim();
        if (trimmed.equals(previousAnswer)) {
            // 같은 본문 재저장은 멱등 — 답변자·시각·구매자 확인 상태를 바꾸면 감사 없이 행이 달라지고 구매자에게 변화 없는 "미확인"이 뜬다.
            log.info("[AdminInquiry] 답변 변경 없음 inquiryPublicId={} byActor={}", inquiryPublicId, auditContext.actorUserId());
            return;
        }
        inquiry.answer(trimmed, auditContext.actorUserId(), LocalDateTime.now());
        auditRecorder.record(auditContext, AuditLogAction.UPDATE, PolymorphicTargetType.INQUIRY, inquiry.getId(),
                previousAnswer == null ? Map.of() : Map.of("answer", previousAnswer),
                Map.of("answer", trimmed));
        inboxSignalPublisher.adminChanged(); // 관리자 미답변 문의 이탈
        log.info("[AdminInquiry] 답변 {} inquiryPublicId={} byActor={}", previousAnswer == null ? "등록" : "수정", inquiryPublicId,
                auditContext.actorUserId());
    }
}
