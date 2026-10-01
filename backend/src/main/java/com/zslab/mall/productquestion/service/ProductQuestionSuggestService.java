package com.zslab.mall.productquestion.service;

import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.common.util.KeywordMatcher;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.exception.ProductNotFoundException;
import com.zslab.mall.productquestion.controller.response.ProductQuestionSuggestionResponse;
import com.zslab.mall.productquestion.entity.ProductQuestion;
import com.zslab.mall.productquestion.enums.ProductQuestionStatus;
import com.zslab.mall.productquestion.enums.ProductQuestionSuggestionType;
import com.zslab.mall.productquestion.repository.ProductQuestionRepository;
import com.zslab.mall.review.entity.Review;
import com.zslab.mall.review.enums.ReviewStatus;
import com.zslab.mall.review.repository.ReviewRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 질문 입력 중 즉시 답(Track 106-2·D-239 §1-A γ). 상품 1개의 후보(답변 달린 공개 질문·공개 리뷰 최근 N건 + 상품 설명 조각)를 메모리에서
 * 토큰 일치 수로 점수 매겨 상위만 돌려준다. 검색 엔진·전문 인덱스·LLM을 쓰지 않는다 — 후보가 상품 단위로 수백 건이라 앱 계산으로 충분하다.
 *
 * <p>토큰화·점수·발췌는 {@link KeywordMatcher}(106-3 FAQ 즉시 답과 공유). 동점이면 유형 우선(QNA → REVIEW → PRODUCT · 셀러가 이미 답한
 * Q&A가 가장 직접적인 답) → 최신순. 숨김·삭제된 질문·리뷰는 후보 조회에서 빠진다.
 */
@Service
@Transactional(readOnly = true)
public class ProductQuestionSuggestService {

    /** 답변된 질문·리뷰 후보 상한(최신순). 답안 초안의 답변된 질문 후보도 같은 값을 쓴다(D-253). */
    public static final int CANDIDATE_LIMIT = 200;
    /** 상품 설명 조각 후보 상한(앞에서부터). 긴 설명이 공개 GET 한 번에 만드는 후보 수를 질문·리뷰 후보와 같은 수준으로 묶는다. */
    static final int FRAGMENT_LIMIT = 200;
    static final int RESULT_LIMIT = 5;
    /** 결과 중 설명 조각 최대 수. 설명 조각은 동점이면 상품 수정 시각으로 앞서므로 질문·리뷰 자리를 남겨 둔다. */
    static final int PRODUCT_RESULT_LIMIT = 3;
    /** 동점일 때 유형 우선순위(앞이 먼저 · D-239 규칙 변경). enum 선언 순서에 기대지 않도록 명시한다. */
    static final List<ProductQuestionSuggestionType> TYPE_PRIORITY = List.of(
            ProductQuestionSuggestionType.QNA, ProductQuestionSuggestionType.REVIEW, ProductQuestionSuggestionType.PRODUCT);

    private final ProductQuestionRepository productQuestionRepository;
    private final ReviewRepository reviewRepository;
    private final ExposedProductReader exposedProductReader;

    public ProductQuestionSuggestService(ProductQuestionRepository productQuestionRepository, ReviewRepository reviewRepository,
            ExposedProductReader exposedProductReader) {
        this.productQuestionRepository = productQuestionRepository;
        this.reviewRepository = reviewRepository;
        this.exposedProductReader = exposedProductReader;
    }

    /**
     * @param query 입력 중인 질문(trim 후 2~100자)
     * @return 점수 상위 최대 5건(일치 0건이면 빈 목록)
     * @throws MalformedRequestException 질의 길이 범위 밖(400)
     * @throws ProductNotFoundException 상품 미존재·비노출(404)
     */
    public List<ProductQuestionSuggestionResponse> suggest(String productPublicId, String query) {
        String trimmed = KeywordMatcher.requireQuery(query);
        Product product = exposedProductReader.require(productPublicId);
        List<List<String>> tokenForms = KeywordMatcher.tokenize(trimmed);
        if (tokenForms.isEmpty()) {
            return List.of();
        }
        List<ScoredCandidate> ranked = candidatesOf(product).stream()
                .map(candidate -> new ScoredCandidate(candidate, KeywordMatcher.score(candidate.matchText(), tokenForms)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(ScoredCandidate::score).reversed()
                        .thenComparingInt(scored -> TYPE_PRIORITY.indexOf(scored.candidate().type()))
                        .thenComparing(scored -> scored.candidate().writtenAt(), Comparator.reverseOrder()))
                .toList();
        return pickTop(ranked);
    }

    /** 순위대로 최대 5건을 고르되 설명 조각은 3건까지만 담는다(넘는 조각은 건너뛰고 다른 유형으로 채운다 · 부족하면 5건 미만). */
    private static List<ProductQuestionSuggestionResponse> pickTop(List<ScoredCandidate> ranked) {
        List<ProductQuestionSuggestionResponse> picked = new ArrayList<>();
        int productCount = 0;
        for (ScoredCandidate scored : ranked) {
            if (picked.size() == RESULT_LIMIT) {
                break;
            }
            boolean isProduct = scored.candidate().type() == ProductQuestionSuggestionType.PRODUCT;
            if (isProduct && productCount == PRODUCT_RESULT_LIMIT) {
                continue;
            }
            if (isProduct) {
                productCount++;
            }
            picked.add(scored.candidate().toResponse());
        }
        return picked;
    }

    private List<Candidate> candidatesOf(Product product) {
        List<Candidate> candidates = new ArrayList<>();
        PageRequest recent = PageRequest.of(0, CANDIDATE_LIMIT);
        for (ProductQuestion question : productQuestionRepository.findRecentAnswered(product.getId(), ProductQuestionStatus.VISIBLE,
                recent)) {
            candidates.add(new Candidate(ProductQuestionSuggestionType.QNA, question.getContent(),
                    question.getContent() + " " + question.getAnswerContent(), question.getAnswerContent(), question.getPublicId(),
                    question.getCreatedAt()));
        }
        for (Review review : reviewRepository.findRecentForSuggest(product.getId(), ReviewStatus.VISIBLE, recent)) {
            candidates.add(new Candidate(ProductQuestionSuggestionType.REVIEW, review.getContent(), review.getContent(), null,
                    review.getPublicId(), review.getCreatedAt()));
        }
        // 설명 조각은 작성 시각이 없어 상품 수정 시각을 쓴다(동점 정렬 기준). 같은 시각끼리는 정렬이 안정적이라 설명 순서가 유지된다.
        // 설명은 HTML이 아닌 평문으로 쓰인다(정찰 B3).
        for (String fragment : KeywordMatcher.fragmentsOf(product.getDescription(), FRAGMENT_LIMIT)) {
            candidates.add(new Candidate(ProductQuestionSuggestionType.PRODUCT, fragment, fragment, null, null, product.getUpdatedAt()));
        }
        return candidates;
    }

    /**
     * @param displayText 표시 원문(응답 시 발췌)
     * @param matchText   점수 계산 대상(QNA는 질문 + 답변)
     * @param answer      QNA 답변(그 밖은 null)
     * @param id          QNA·REVIEW public_id(PRODUCT는 null)
     * @param writtenAt   동점 정렬 기준 시각
     */
    private record Candidate(ProductQuestionSuggestionType type, String displayText, String matchText, String answer, String id,
            LocalDateTime writtenAt) {

        Candidate {
            matchText = matchText.toLowerCase(Locale.ROOT);
        }

        ProductQuestionSuggestionResponse toResponse() {
            return new ProductQuestionSuggestionResponse(type, KeywordMatcher.excerpt(displayText), answer, id);
        }
    }

    private record ScoredCandidate(Candidate candidate, int score) {
    }
}
