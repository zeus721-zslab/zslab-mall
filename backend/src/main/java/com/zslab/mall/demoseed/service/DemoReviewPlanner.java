package com.zslab.mall.demoseed.service;

import com.zslab.mall.demoseed.template.DemoReviewTemplates;
import com.zslab.mall.demoseed.template.DemoReviewTemplates.Band;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * 데모 리뷰 계획(D-245 · 순수 로직). 부족분만큼 후보 품목을 고르고 별점·키워드·본문·작성 시각을 정한다. 모든 선택은 품목 id로 정한 난수라
 * 같은 입력이면 같은 계획이 나온다. 품목은 구매자·상품·구매확정 월 중 덜 쓰인 쪽을 먼저 골라 고르게 흩고, 같은 상품 안에서 본문이 겹치면 그 품목을
 * 건너뛴다(다른 품목으로 대신 채운다).
 */
public final class DemoReviewPlanner {

    public static final int TARGET_PUBLIC_REVIEWS = 110;
    static final int[] RATINGS = {5, 4, 3, 2, 1};
    static final int[] RATING_WEIGHTS = {30, 30, 20, 12, 8};
    static final int KEYWORD_MAX_HIGH = 3;
    static final Duration WRITE_DELAY_MAX = Duration.ofDays(5);
    static final int WRITE_HOURS_MIN = 1;
    static final int WRITE_HOURS_MAX = 10;
    static final Duration NOW_MARGIN = Duration.ofMinutes(1);
    private static final long ORDER_SALT = 15_485_863L;
    private static final long RATING_SALT = 7_919L;
    private static final long CONTENT_SALT = 32_452_843L;
    private static final long KEYWORD_SALT = 104_729L;
    private static final long TIME_SALT = 1_299_709L;

    private DemoReviewPlanner() {
    }

    /** 리뷰를 쓸 수 있는 품목(데모 구매자 본인 주문 · 구매확정 · 리뷰 이력 없음 · 데모 셀러 상품). */
    public record Candidate(Long orderItemId, String orderItemPublicId, Long productId, String productName, Long categoryId,
            Long buyerId, String buyerEmail, LocalDateTime confirmedAt) {
    }

    public record PlannedReview(Candidate candidate, int rating, List<String> keywordCodes, String content, LocalDateTime writtenAt) {
    }

    /**
     * @param need                  만들 리뷰 수(목표 − 현재 공개 수)
     * @param existingContents      상품 id → 이미 있는 리뷰 본문(삭제분 제외 · 같은 상품 안 공개 중복 방지)
     * @param categoryNames         카테고리 id → 표시명(없으면 대체 세트)
     * @param usableKeywordCodes    카테고리 id → 쓸 수 있는 기존 키워드 code(표시 순)
     * @return 계획(후보·문구가 모자라면 need보다 적을 수 있다)
     */
    public static List<PlannedReview> plan(int need, List<Candidate> candidates, Map<Long, Set<String>> existingContents,
            Map<Long, String> categoryNames, Map<Long, List<String>> usableKeywordCodes, LocalDateTime now) {
        List<Candidate> remaining = new ArrayList<>(candidates);
        Map<Long, Integer> byBuyer = new HashMap<>();
        Map<Long, Integer> byProduct = new HashMap<>();
        Map<YearMonth, Integer> byMonth = new HashMap<>();
        Map<Long, Set<String>> usedContents = new HashMap<>();
        List<PlannedReview> planned = new ArrayList<>();
        Comparator<Candidate> leastUsedFirst = Comparator
                .comparingInt((Candidate candidate) -> byBuyer.getOrDefault(candidate.buyerId(), 0))
                .thenComparingInt(candidate -> byProduct.getOrDefault(candidate.productId(), 0))
                .thenComparingInt(candidate -> byMonth.getOrDefault(YearMonth.from(candidate.confirmedAt()), 0))
                .thenComparingLong(candidate -> seeded(candidate.orderItemId(), ORDER_SALT).nextLong())
                .thenComparingLong(Candidate::orderItemId);
        while (planned.size() < need && !remaining.isEmpty()) {
            Candidate candidate = remaining.stream().min(leastUsedFirst).orElseThrow();
            remaining.remove(candidate);
            int rating = rating(candidate.orderItemId());
            Set<String> used = usedContents.computeIfAbsent(candidate.productId(),
                    productId -> new HashSet<>(existingContents.getOrDefault(productId, Set.of())));
            // 카테고리 id가 없을 수 있다(불변 맵은 null 키 조회가 예외라 먼저 거른다)
            Long categoryId = candidate.categoryId();
            String content = pickContent(candidate, rating, categoryId == null ? null : categoryNames.get(categoryId), used);
            if (content == null) {
                continue;   // 이 상품의 해당 별점대 문구를 다 썼다 — 다른 품목으로 채운다
            }
            used.add(content);
            List<String> usableCodes = categoryId == null ? List.of() : usableKeywordCodes.getOrDefault(categoryId, List.of());
            planned.add(new PlannedReview(candidate, rating, keywords(candidate.orderItemId(), rating, usableCodes), content,
                    writtenAt(candidate, now)));
            byBuyer.merge(candidate.buyerId(), 1, Integer::sum);
            byProduct.merge(candidate.productId(), 1, Integer::sum);
            byMonth.merge(YearMonth.from(candidate.confirmedAt()), 1, Integer::sum);
        }
        return planned;
    }

    /** 품목 id로 정한 별점(가중치 5:30 · 4:30 · 3:20 · 2:12 · 1:8). */
    public static int rating(long orderItemId) {
        int total = 0;
        for (int weight : RATING_WEIGHTS) {
            total += weight;
        }
        int roll = seeded(orderItemId, RATING_SALT).nextInt(total);
        for (int index = 0; index < RATINGS.length; index++) {
            roll -= RATING_WEIGHTS[index];
            if (roll < 0) {
                return RATINGS[index];
            }
        }
        return RATINGS[RATINGS.length - 1];
    }

    /** 별점대 문장 중 이 상품에 아직 없는 것(품목 id로 정한 시작점부터). 모두 쓰였으면 null. */
    static String pickContent(Candidate candidate, int rating, String categoryName, Set<String> used) {
        List<String> templates = DemoReviewTemplates.forCategory(categoryName, Band.of(rating));
        int start = seeded(candidate.orderItemId(), CONTENT_SALT).nextInt(templates.size());
        for (int offset = 0; offset < templates.size(); offset++) {
            String content = DemoReviewTemplates.render(templates.get((start + offset) % templates.size()), candidate.productName());
            if (!used.contains(content)) {
                return content;
            }
        }
        return null;
    }

    /** 별점에 맞춘 기존 키워드 0~3개: 4~5점 1~3개 · 3점 0~1개 · 1~2점 0개(긍정 위주 기본 세트와 어긋나지 않게). */
    static List<String> keywords(long orderItemId, int rating, List<String> usableCodes) {
        Random random = seeded(orderItemId, KEYWORD_SALT);
        int count;
        if (rating >= 4) {
            count = 1 + random.nextInt(KEYWORD_MAX_HIGH);
        } else if (rating == 3) {
            count = random.nextInt(2);
        } else {
            count = 0;
        }
        List<String> shuffled = new ArrayList<>(usableCodes);
        Collections.shuffle(shuffled, random);
        return List.copyOf(shuffled.subList(0, Math.min(count, shuffled.size())));
    }

    /**
     * 품목 id로 정한 난수. 연속된 id를 그대로 {@link Random} 시드로 쓰면 첫 추출값이 거의 같아(상위 비트 편향) 분산이 무너지므로
     * {@link SplittableRandom}으로 시드를 한 번 섞는다.
     */
    static Random seeded(long orderItemId, long salt) {
        return new Random(new SplittableRandom(orderItemId ^ salt).nextLong());
    }

    /** 작성 시각 = 구매확정 + 0~5일 + 1~10시간, 현재 − 1분을 넘기지 않고 구매확정 이전으로 가지 않는다. */
    static LocalDateTime writtenAt(Candidate candidate, LocalDateTime now) {
        Random random = seeded(candidate.orderItemId(), TIME_SALT);
        Duration delay = Duration.ofNanos((long) (WRITE_DELAY_MAX.toNanos() * random.nextDouble()))
                .plusHours(WRITE_HOURS_MIN + random.nextInt(WRITE_HOURS_MAX - WRITE_HOURS_MIN + 1));
        LocalDateTime written = candidate.confirmedAt().plus(delay);
        LocalDateTime latest = now.minus(NOW_MARGIN);
        if (written.isAfter(latest)) {
            written = latest;
        }
        return written.isBefore(candidate.confirmedAt()) ? candidate.confirmedAt() : written;
    }
}
