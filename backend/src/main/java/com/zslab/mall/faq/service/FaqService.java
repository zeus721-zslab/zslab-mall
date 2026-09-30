package com.zslab.mall.faq.service;

import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.faq.controller.request.FaqWriteRequest;
import com.zslab.mall.faq.entity.Faq;
import com.zslab.mall.faq.enums.FaqCategory;
import com.zslab.mall.faq.exception.FaqNotFoundException;
import com.zslab.mall.faq.repository.FaqRepository;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 FAQ 쓰기(Track 106-3 · Category 선례 모양). 등록·카테고리 이동은 해당 카테고리 끝에 둔다. 감사는 수정·삭제만 남기고(값이 실제로 바뀐
 * 경우) 등록·정렬은 남기지 않는다 — 등록은 행 자체가 이력이고, 정렬은 노출 순서만 바꾼다(Category 선례).
 *
 * <p>락·버전 없이 단일 운영자를 전제한다(Category 선례) — 동시 편집 시 마지막 저장이 이긴다.
 */
@Slf4j
@Service
@Transactional
public class FaqService {

    private final FaqRepository faqRepository;
    private final AuditRecorder auditRecorder;

    public FaqService(FaqRepository faqRepository, AuditRecorder auditRecorder) {
        this.faqRepository = faqRepository;
        this.auditRecorder = auditRecorder;
    }

    /** @return 생성된 FAQ id */
    public Long create(FaqWriteRequest request) {
        FaqCategory category = FaqCategory.valueOf(request.category());
        Faq saved = faqRepository.save(Faq.create(category, request.question().trim(), request.answer().trim(),
                faqRepository.nextSortOrder(category), request.visible()));
        log.info("[Faq] 등록 faqId={} category={}", saved.getId(), category);
        return saved.getId();
    }

    /**
     * 전체 치환. 값이 그대로면 감사 없이 반환한다.
     *
     * @throws FaqNotFoundException 미존재·삭제(404)
     */
    public void update(Long faqId, FaqWriteRequest request, AuditContext auditContext) {
        Faq faq = find(faqId);
        FaqCategory category = FaqCategory.valueOf(request.category());
        String question = request.question().trim();
        String answer = request.answer().trim();
        boolean categoryChanged = category != faq.getCategory();
        boolean changed = categoryChanged
                || !question.equals(faq.getQuestion())
                || !answer.equals(faq.getAnswer())
                || request.visible() != faq.isVisible();
        if (!changed) {
            log.info("[Faq] 수정 요청 값 무변경 → 감사 skip faqId={}", faqId);
            return;
        }
        Map<String, Object> before = snapshot(faq);
        int sortOrder = categoryChanged ? faqRepository.nextSortOrder(category) : faq.getSortOrder();
        faq.update(category, question, answer, sortOrder, request.visible());
        auditRecorder.record(auditContext, AuditLogAction.UPDATE, PolymorphicTargetType.FAQ, faqId, before, snapshot(faq));
        log.info("[Faq] 수정 faqId={} categoryChanged={}", faqId, categoryChanged);
    }

    /** @throws FaqNotFoundException 미존재·이미 삭제(404) */
    public void delete(Long faqId, AuditContext auditContext) {
        Faq faq = find(faqId);
        Map<String, Object> before = snapshot(faq);
        before.put("deleted", false);
        faq.markDeleted();
        auditRecorder.record(auditContext, AuditLogAction.DELETE, PolymorphicTargetType.FAQ, faqId, before, Map.of("deleted", true));
        log.info("[Faq] soft-delete faqId={}", faqId);
    }

    /**
     * 카테고리 안 일괄 정렬. 배열 index를 sortOrder로 반영한다. 해당 카테고리의 FAQ 전체(숨김 포함)와 정확히 일치해야 한다.
     *
     * @throws MalformedRequestException 중복 id·누락 id·다른 카테고리·미존재(또는 삭제) id가 있을 때(400)
     */
    public void reorder(FaqCategory category, List<Long> faqIds) {
        Set<Long> uniqueIds = new HashSet<>(faqIds);
        if (uniqueIds.size() != faqIds.size()) {
            throw new MalformedRequestException("정렬 요청에 중복 faqId가 있습니다.");
        }
        Map<Long, Faq> faqById = faqRepository.findByCategory(category).stream()
                .collect(Collectors.toMap(Faq::getId, Function.identity()));
        if (!uniqueIds.equals(faqById.keySet())) {
            throw new MalformedRequestException("정렬 요청은 카테고리 FAQ 전체 id와 일치해야 합니다: category=" + category + " 요청 "
                    + faqIds + " / 현재 " + faqById.keySet());
        }
        for (int index = 0; index < faqIds.size(); index++) {
            faqById.get(faqIds.get(index)).changeSortOrder(index);
        }
        log.info("[Faq] 정렬 변경 category={} order={}", category, faqIds);
    }

    private Faq find(Long faqId) {
        return faqRepository.findById(faqId)
                .orElseThrow(() -> new FaqNotFoundException("FAQ를 찾을 수 없습니다: faqId=" + faqId));
    }

    /** 감사 before/after 필드맵(순서 유지 · 삭제 시 deleted를 덧붙이므로 가변 맵). */
    private static Map<String, Object> snapshot(Faq faq) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("category", faq.getCategory().name());
        fields.put("question", faq.getQuestion());
        fields.put("answer", faq.getAnswer());
        fields.put("sortOrder", faq.getSortOrder());
        fields.put("visible", faq.isVisible());
        return fields;
    }
}
