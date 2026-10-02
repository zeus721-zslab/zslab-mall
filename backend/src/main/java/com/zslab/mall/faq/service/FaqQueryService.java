package com.zslab.mall.faq.service;

import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.common.util.KeywordMatcher;
import com.zslab.mall.faq.controller.response.AdminFaqResponse;
import com.zslab.mall.faq.controller.response.FaqResponse;
import com.zslab.mall.faq.entity.Faq;
import com.zslab.mall.faq.repository.FaqRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FAQ 조회(Track 106-3 · read-only). 구매자 전체 목록·즉시 답·관리자 목록. 행 수가 수십~수백 건이라 전량 조회 후 메모리에서 정렬·점수화한다
 * (즉시 답 방식은 106-2와 같은 {@link KeywordMatcher} · 검색 엔진·LLM 미사용).
 */
@Service
@Transactional(readOnly = true)
public class FaqQueryService {

    static final int SUGGEST_RESULT_LIMIT = 5;
    /** 노출 순서: 카테고리 선언 순서 → 카테고리 안 sortOrder → id(동시 등록으로 sortOrder가 겹칠 때). */
    private static final Comparator<Faq> DISPLAY_ORDER = Comparator.comparing(Faq::getCategory)
            .thenComparingInt(Faq::getSortOrder)
            .thenComparing(Faq::getId);

    private final FaqRepository faqRepository;

    public FaqQueryService(FaqRepository faqRepository) {
        this.faqRepository = faqRepository;
    }

    /** 공개 FAQ 전체(숨김·삭제 제외 · 노출 순서). */
    public List<FaqResponse> listVisible() {
        return faqRepository.findByVisibleTrue().stream().sorted(DISPLAY_ORDER).map(FaqResponse::from).toList();
    }

    /**
     * 질문 + 답 텍스트에서 질의 토큰 일치 수가 높은 공개 FAQ 상위 5건. 동점이면 노출 순서.
     *
     * @param query 자유 입력(trim 후 2~100자)
     * @return 일치 0건이면 빈 목록
     * @throws MalformedRequestException 질의 길이 범위 밖(400)
     */
    public List<FaqResponse> suggest(String query) {
        return rankVisible(KeywordMatcher.tokenize(KeywordMatcher.requireQuery(query)), SUGGEST_RESULT_LIMIT).stream()
                .map(FaqResponse::from)
                .toList();
    }

    /**
     * 답안 초안 근거(D-253): 즉시 답과 같은 점수·정렬로 고른 공개 FAQ 중 일치 비율 ≥ 0.5({@link KeywordMatcher#meetsDraftMatchRatio})만.
     * 문의 본문(최대 500자)을 그대로 받으려고 질의 길이 검사는 하지 않는다 — 토큰은 {@link KeywordMatcher#tokenize}가 앞에서부터 최대 5개만 쓴다.
     *
     * <p>점수 내림차순 결과를 limit로 자른 뒤 거르므로 "거른 뒤 limit"와 같은 집합이다(비율 미달은 항상 뒤쪽).
     *
     * @return 자격 있는 일치가 없으면 빈 목록
     */
    public List<Faq> matchVisible(String text, int limit) {
        List<List<String>> tokenForms = KeywordMatcher.tokenize(text);
        return rankVisible(tokenForms, limit).stream()
                .filter(faq -> KeywordMatcher.meetsDraftMatchRatio(KeywordMatcher.score(matchTextOf(faq), tokenForms), tokenForms.size()))
                .toList();
    }

    private List<Faq> rankVisible(List<List<String>> tokenForms, int limit) {
        if (tokenForms.isEmpty()) {
            return List.of();
        }
        return faqRepository.findByVisibleTrue().stream()
                .map(faq -> new ScoredFaq(faq, KeywordMatcher.score(matchTextOf(faq), tokenForms)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(ScoredFaq::score).reversed()
                        .thenComparing(ScoredFaq::faq, DISPLAY_ORDER))
                .limit(limit)
                .map(ScoredFaq::faq)
                .toList();
    }

    /** 관리자 목록(숨김 포함 · 삭제 제외 · 노출 순서). */
    public List<AdminFaqResponse> listForAdmin() {
        return faqRepository.findAll().stream().sorted(DISPLAY_ORDER).map(AdminFaqResponse::from).toList();
    }

    private static String matchTextOf(Faq faq) {
        return (faq.getQuestion() + " " + faq.getAnswer()).toLowerCase(Locale.ROOT);
    }

    private record ScoredFaq(Faq faq, int score) {
    }
}
