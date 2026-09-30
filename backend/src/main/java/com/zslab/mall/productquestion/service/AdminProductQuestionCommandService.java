package com.zslab.mall.productquestion.service;

import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.productquestion.entity.ProductQuestion;
import com.zslab.mall.productquestion.enums.ProductQuestionStatus;
import com.zslab.mall.productquestion.exception.ProductQuestionInvalidStateException;
import com.zslab.mall.productquestion.exception.ProductQuestionNotFoundException;
import com.zslab.mall.productquestion.repository.ProductQuestionRepository;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 질문 숨김·숨김 해제(Track 106-2·리뷰 숨김 선례). 질문 행 락으로 질문자 수정·삭제·셀러 답변과 직렬화하고 같은 트랜잭션에서 감사
 * (UPDATE·PRODUCT_QUESTION·before {status} / after {status, reason})를 적재한다. 질문·답변 본문은 감사 diff에 싣지 않는다 — 숨김은 본문을
 * 바꾸지 않고, append-only 감사 로그에 사용자 작성 글을 복제하지 않는다.
 *
 * <p>감사 호출은 이 클래스에만 둔다(AuditFieldMaskingPolicyTest가 감사 호출 파일의 Map 키를 파일 단위로 스캔한다).
 */
@Slf4j
@Service
@Transactional
public class AdminProductQuestionCommandService {

    private final ProductQuestionRepository productQuestionRepository;
    private final AuditRecorder auditRecorder;

    public AdminProductQuestionCommandService(ProductQuestionRepository productQuestionRepository, AuditRecorder auditRecorder) {
        this.productQuestionRepository = productQuestionRepository;
        this.auditRecorder = auditRecorder;
    }

    /**
     * @param target 목표 상태(VISIBLE·HIDDEN·DTO @Pattern 선검증)
     * @param reason 사유(필수·감사 이력)
     * @throws ProductQuestionNotFoundException 미존재·삭제 질문(404)
     * @throws ProductQuestionInvalidStateException 같은 상태 재요청(422)
     */
    public void changeStatus(String questionPublicId, ProductQuestionStatus target, String reason, AuditContext auditContext) {
        ProductQuestion question = productQuestionRepository.findByPublicIdForUpdate(questionPublicId)
                .orElseThrow(() -> new ProductQuestionNotFoundException("질문을 찾을 수 없습니다: " + questionPublicId));
        ProductQuestionStatus before = question.getStatus();
        String trimmedReason = reason.trim();
        try {
            question.changeStatus(target, trimmedReason);
        } catch (IllegalStateException exception) {
            throw new ProductQuestionInvalidStateException(exception.getMessage() + " questionPublicId=" + questionPublicId);
        }
        auditRecorder.record(auditContext, AuditLogAction.UPDATE, PolymorphicTargetType.PRODUCT_QUESTION, question.getId(),
                Map.of("status", before.name()),
                Map.of("status", target.name(), "reason", trimmedReason));
        log.info("[AdminProductQuestion] 상태 전이 {} → {} questionPublicId={} byActor={}", before, target, questionPublicId,
                auditContext.actorUserId());
    }
}
