package com.zslab.mall.productquestion.service;

import com.zslab.mall.common.exception.MalformedRequestException;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 질문 입력 중 즉시 답(Track 106-2·D-239 §1-A γ). 상품 1개의 후보(답변 달린 공개 질문·공개 리뷰 최근 N건 + 상품 설명 조각)를 메모리에서
 * 토큰 일치 수로 점수 매겨 상위만 돌려준다. 검색 엔진·전문 인덱스·LLM을 쓰지 않는다 — 후보가 상품 단위로 수백 건이라 앱 계산으로 충분하다.
 *
 * <p>토큰: 질의를 공백·문장부호로 나눠 2자 이상만 앞에서부터 최대 5개. 3자 이상 토큰은 끝 1자를 뗀 형태도 일치로 인정한다(조사 대응 —
 * "사이즈가" → "사이즈"). 점수 = 일치한 토큰 수, 동점이면 최신순. 숨김·삭제된 질문·리뷰는 후보 조회에서 빠진다.
 */
@Service
@Transactional(readOnly = true)
public class ProductQuestionSuggestService {

    static final int MIN_QUERY_LENGTH = 2;
    static final int MAX_QUERY_LENGTH = 100;
    static final int MIN_TOKEN_LENGTH = 2;
    static final int MAX_TOKENS = 5;
    /** 이 길이 이상의 토큰은 끝 1자를 뗀 형태도 인정한다(1자 조사 대응 · 2자 토큰은 떼면 1자라 제외). */
    static final int STEM_MIN_LENGTH = 3;
    static final int CANDIDATE_LIMIT = 200;
    /** 상품 설명 조각 후보 상한(앞에서부터). 긴 설명이 공개 GET 한 번에 만드는 후보 수를 질문·리뷰 후보와 같은 수준으로 묶는다. */
    static final int FRAGMENT_LIMIT = 200;
    static final int RESULT_LIMIT = 5;
    /** 결과 중 설명 조각 최대 수. 설명 조각은 동점이면 상품 수정 시각으로 앞서므로 질문·리뷰 자리를 남겨 둔다. */
    static final int PRODUCT_RESULT_LIMIT = 3;
    /** 표시 텍스트 발췌 길이(넘으면 잘라 말줄임표를 붙인다). */
    static final int EXCERPT_LENGTH = 120;
    private static final String ELLIPSIS = "…";
    private static final Pattern TOKEN_SEPARATOR = Pattern.compile("[\\s\\p{Z}\\p{P}]+");
    /** 상품 설명 조각 경계: 줄바꿈, 또는 문장부호(. ! ? 。) 뒤 공백. */
    private static final Pattern FRAGMENT_SEPARATOR = Pattern.compile("\\R+|(?<=[.!?。])\\s+");

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
        String trimmed = query.trim();
        if (trimmed.length() < MIN_QUERY_LENGTH || trimmed.length() > MAX_QUERY_LENGTH) {
            throw new MalformedRequestException("질의는 " + MIN_QUERY_LENGTH + "~" + MAX_QUERY_LENGTH + "자여야 합니다.");
        }
        Product product = exposedProductReader.require(productPublicId);
        List<List<String>> tokenForms = tokenize(trimmed);
        if (tokenForms.isEmpty()) {
            return List.of();
        }
        List<ScoredCandidate> ranked = candidatesOf(product).stream()
                .map(candidate -> new ScoredCandidate(candidate, score(candidate.matchText(), tokenForms)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(ScoredCandidate::score).reversed()
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

    /** 토큰마다 일치로 인정하는 형태 목록(원형 + 3자 이상이면 끝 1자 뗀 형태). 소문자로 맞춘다. */
    static List<List<String>> tokenize(String query) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String raw : TOKEN_SEPARATOR.split(query.toLowerCase(Locale.ROOT))) {
            if (tokens.size() == MAX_TOKENS) {
                break;
            }
            if (raw.codePointCount(0, raw.length()) >= MIN_TOKEN_LENGTH) {
                tokens.add(raw);
            }
        }
        return tokens.stream().map(ProductQuestionSuggestService::formsOf).toList();
    }

    private static List<String> formsOf(String token) {
        if (token.codePointCount(0, token.length()) < STEM_MIN_LENGTH) {
            return List.of(token);
        }
        return List.of(token, token.substring(0, token.offsetByCodePoints(token.length(), -1)));
    }

    /** 일치한 토큰 수(토큰의 형태 중 하나라도 본문에 있으면 1). */
    static int score(String matchText, List<List<String>> tokenForms) {
        int matched = 0;
        for (List<String> forms : tokenForms) {
            if (forms.stream().anyMatch(matchText::contains)) {
                matched++;
            }
        }
        return matched;
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
        for (String fragment : fragmentsOf(product.getDescription())) {
            candidates.add(new Candidate(ProductQuestionSuggestionType.PRODUCT, fragment, fragment, null, null, product.getUpdatedAt()));
        }
        return candidates;
    }

    /** 상품 설명을 줄·문장 단위로 나눈다(빈 조각 제외 · 앞에서부터 최대 200개). 설명은 HTML이 아닌 평문으로 쓰인다(정찰 B3). */
    static List<String> fragmentsOf(String description) {
        if (description == null) {
            return List.of();
        }
        return FRAGMENT_SEPARATOR.splitAsStream(description).map(String::trim).filter(fragment -> !fragment.isEmpty())
                .limit(FRAGMENT_LIMIT).toList();
    }

    static String excerpt(String text) {
        if (text.codePointCount(0, text.length()) <= EXCERPT_LENGTH) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, EXCERPT_LENGTH)) + ELLIPSIS;
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
            return new ProductQuestionSuggestionResponse(type, excerpt(displayText), answer, id);
        }
    }

    private record ScoredCandidate(Candidate candidate, int score) {
    }
}
