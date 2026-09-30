package com.zslab.mall.common.util;

import com.zslab.mall.common.exception.MalformedRequestException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 입력 중 즉시 답의 토큰 일치 점수(Track 106-2 D-239 §1-A γ에서 추출 → 106-3 FAQ 즉시 답과 공유). 검색 엔진·전문 인덱스·LLM 없이 앱 메모리에서
 * 후보 본문마다 질의 토큰이 몇 개 들어 있는지 센다 — 후보가 수백 건 규모일 때만 성립한다.
 *
 * <p>토큰: 질의를 공백·문장부호로 나눠 2자 이상만 앞에서부터 최대 5개. 3자 이상 토큰은 끝 1자를 뗀 형태도 일치로 인정한다(조사 대응 —
 * "사이즈가" → "사이즈"). 점수 = 일치한 토큰 수. 후보 조회·동점 정렬·결과 상한은 쓰는 쪽 책임이다.
 */
public final class KeywordMatcher {

    private static final int MIN_QUERY_LENGTH = 2;
    private static final int MAX_QUERY_LENGTH = 100;
    private static final int MIN_TOKEN_LENGTH = 2;
    private static final int MAX_TOKENS = 5;
    /** 이 길이 이상의 토큰은 끝 1자를 뗀 형태도 인정한다(1자 조사 대응 · 2자 토큰은 떼면 1자라 제외). */
    private static final int STEM_MIN_LENGTH = 3;
    /** 표시 텍스트 발췌 길이(넘으면 잘라 말줄임표를 붙인다). */
    private static final int EXCERPT_LENGTH = 120;
    private static final String ELLIPSIS = "…";
    private static final Pattern TOKEN_SEPARATOR = Pattern.compile("[\\s\\p{Z}\\p{P}]+");
    /** 본문 조각 경계: 줄바꿈, 또는 문장부호(. ! ? 。) 뒤 공백. */
    private static final Pattern FRAGMENT_SEPARATOR = Pattern.compile("\\R+|(?<=[.!?。])\\s+");

    private KeywordMatcher() {
    }

    /**
     * @return trim한 질의
     * @throws MalformedRequestException trim 후 2~100자 밖(400)
     */
    public static String requireQuery(String query) {
        String trimmed = query.trim();
        if (trimmed.length() < MIN_QUERY_LENGTH || trimmed.length() > MAX_QUERY_LENGTH) {
            throw new MalformedRequestException("질의는 " + MIN_QUERY_LENGTH + "~" + MAX_QUERY_LENGTH + "자여야 합니다.");
        }
        return trimmed;
    }

    /** 토큰마다 일치로 인정하는 형태 목록(원형 + 3자 이상이면 끝 1자 뗀 형태). 소문자로 맞춘다. 2자 이상 토큰이 없으면 빈 목록. */
    public static List<List<String>> tokenize(String query) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String raw : TOKEN_SEPARATOR.split(query.toLowerCase(Locale.ROOT))) {
            if (tokens.size() == MAX_TOKENS) {
                break;
            }
            if (raw.codePointCount(0, raw.length()) >= MIN_TOKEN_LENGTH) {
                tokens.add(raw);
            }
        }
        return tokens.stream().map(KeywordMatcher::formsOf).toList();
    }

    private static List<String> formsOf(String token) {
        if (token.codePointCount(0, token.length()) < STEM_MIN_LENGTH) {
            return List.of(token);
        }
        return List.of(token, token.substring(0, token.offsetByCodePoints(token.length(), -1)));
    }

    /**
     * 일치한 토큰 수(토큰의 형태 중 하나라도 본문에 있으면 1).
     *
     * @param matchText 소문자로 맞춘 후보 본문({@link #tokenize}가 토큰을 소문자로 만든다)
     */
    public static int score(String matchText, List<List<String>> tokenForms) {
        int matched = 0;
        for (List<String> forms : tokenForms) {
            if (forms.stream().anyMatch(matchText::contains)) {
                matched++;
            }
        }
        return matched;
    }

    /** 본문을 줄·문장 단위로 나눈다(빈 조각 제외 · 앞에서부터 최대 limit개). 평문 전제. */
    public static List<String> fragmentsOf(String text, int limit) {
        if (text == null) {
            return List.of();
        }
        return FRAGMENT_SEPARATOR.splitAsStream(text).map(String::trim).filter(fragment -> !fragment.isEmpty())
                .limit(limit).toList();
    }

    public static String excerpt(String text) {
        if (text.codePointCount(0, text.length()) <= EXCERPT_LENGTH) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, EXCERPT_LENGTH)) + ELLIPSIS;
    }
}
