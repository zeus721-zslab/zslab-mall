package com.zslab.mall.stats.service;

import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.grade.enums.BuyerGradeCode;
import com.zslab.mall.stats.controller.response.AdminMemberStatsResponse;
import com.zslab.mall.stats.controller.response.BuyerSplitResponse;
import com.zslab.mall.stats.controller.response.GradeDistributionResponse;
import com.zslab.mall.stats.controller.response.MemberSummaryResponse;
import com.zslab.mall.stats.controller.response.SignupTrendBucketResponse;
import com.zslab.mall.stats.controller.response.TopBuyerResponse;
import com.zslab.mall.stats.enums.StatsCompare;
import com.zslab.mall.stats.enums.StatsUnit;
import com.zslab.mall.stats.repository.AdminMemberStatsRepository;
import com.zslab.mall.stats.repository.BuyerOrdersProjection;
import com.zslab.mall.stats.repository.GradeCountProjection;
import com.zslab.mall.stats.repository.GradeRevenueProjection;
import com.zslab.mall.stats.repository.StatsCountBucketProjection;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 회원 통계 조회(Track 88·D-182). 기간·비교 기간({@link StatsPeriod})·구간 키({@link StatsBuckets})는 매출 통계와 공유하고 집계는
 * {@link AdminMemberStatsRepository}가 수행한다. 상위 회원 이름·이메일은 {@link UserRepository#findByIdIn} 배치 enrich(대시보드 선례).
 *
 * <p>활성 누적 = 기간 시작 시점 (가입 누계 − 탈퇴 누계)를 기준값으로 두고 구간별 (가입 − 탈퇴)를 순서대로 더한다(윈도우 함수 없이 서비스 누적).
 * 재구매율·1회/재구매 분리·상위 20은 구매자별 집계 1쿼리 결과에서 함께 산출한다. 비교 기간은 요약·가입 추이에만 적용하며 비교 기간에
 * 가입·탈퇴·구매자가 모두 0이면 compareSummary·compareSignupTrend는 null(D-181 규약).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdminMemberStatsQueryService {

    private static final int TOP_BUYER_LIMIT = 20;
    private static final long REPEAT_ORDER_THRESHOLD = 2L;

    private final AdminMemberStatsRepository statsRepository;
    private final UserRepository userRepository;

    /** 구매자별 집계에서 파생한 재구매·분리 지표(요약과 buyerSplit이 공유). */
    private record BuyerAggregate(long buyerCount, long repeatBuyerCount, long repeatRevenue, long firstTimeBuyerCount,
            long firstTimeRevenue) {
    }

    /**
     * 요약 + 가입 추이 + 등급 분포 + 구매자 분리 + 상위 회원.
     *
     * @throws MalformedRequestException from &gt; to(400)
     */
    public AdminMemberStatsResponse getMemberStats(LocalDate from, LocalDate to, StatsUnit unit, StatsCompare compare) {
        StatsPeriod period = StatsPeriod.of(from, to);
        List<StatsBuckets.Bucket> buckets = StatsBuckets.of(period.from(), unit, StatsBuckets.count(period, unit));

        List<BuyerOrdersProjection> buyerOrders = statsRepository.aggregateOrdersByBuyer(period.start(), period.end());
        BuyerAggregate buyers = aggregateBuyers(buyerOrders);
        MemberSummaryResponse summary = summary(period, buyers);
        List<SignupTrendBucketResponse> signupTrend = signupTrend(period, unit, buckets);
        List<GradeDistributionResponse> gradeDistribution = gradeDistribution(period);
        BuyerSplitResponse buyerSplit = new BuyerSplitResponse(buyers.firstTimeBuyerCount(), buyers.firstTimeRevenue(),
                buyers.repeatBuyerCount(), buyers.repeatRevenue());
        List<TopBuyerResponse> topBuyers = topBuyers(buyerOrders);

        StatsPeriod comparePeriod = period.compareWith(compare);
        MemberSummaryResponse compareSummary = comparePeriod == null ? null
                : summary(comparePeriod, aggregateBuyers(
                        statsRepository.aggregateOrdersByBuyer(comparePeriod.start(), comparePeriod.end())));
        if (compareSummary == null || compareSummary.hasNoData()) {
            return new AdminMemberStatsResponse(summary, null, signupTrend, null, gradeDistribution, buyerSplit, topBuyers);
        }
        List<StatsBuckets.Bucket> compareBuckets = StatsBuckets.of(comparePeriod.from(), unit, buckets.size());
        return new AdminMemberStatsResponse(summary, compareSummary, signupTrend,
                signupTrend(comparePeriod, unit, compareBuckets), gradeDistribution, buyerSplit, topBuyers);
    }

    private MemberSummaryResponse summary(StatsPeriod period, BuyerAggregate buyers) {
        long newCount = statsRepository.countSignups(RoleCode.BUYER, period.start(), period.end());
        long withdrawnCount = statsRepository.countWithdrawals(RoleCode.BUYER, period.start(), period.end());
        long activeTotal = activeBefore(period.end());
        return MemberSummaryResponse.of(newCount, withdrawnCount, activeTotal, buyers.buyerCount(), buyers.repeatBuyerCount());
    }

    /** 기간 시작 시점 활성 누적을 기준값으로 구간별 (가입 − 탈퇴)를 누적한다. 구간에 없는 키는 0. */
    private List<SignupTrendBucketResponse> signupTrend(StatsPeriod period, StatsUnit unit, List<StatsBuckets.Bucket> buckets) {
        String pattern = StatsBuckets.pattern(unit);
        Map<String, Long> signups = countByBucket(
                statsRepository.countSignupsByBucket(pattern, RoleCode.BUYER, period.start(), period.end()));
        Map<String, Long> withdrawals = countByBucket(
                statsRepository.countWithdrawalsByBucket(pattern, RoleCode.BUYER, period.start(), period.end()));
        long cumulative = activeBefore(period.start());
        List<SignupTrendBucketResponse> rows = new ArrayList<>(buckets.size());
        for (StatsBuckets.Bucket bucket : buckets) {
            long newCount = signups.getOrDefault(bucket.dbKey(), 0L);
            cumulative += newCount - withdrawals.getOrDefault(bucket.dbKey(), 0L);
            rows.add(new SignupTrendBucketResponse(bucket.key(), bucket.label(), newCount, cumulative));
        }
        return rows;
    }

    private long activeBefore(LocalDateTime before) {
        return statsRepository.countSignupsBefore(RoleCode.BUYER, before)
                - statsRepository.countWithdrawalsBefore(RoleCode.BUYER, before);
    }

    /** 3등급 전부(0건 포함·enum 순서). 인원은 현재 등급 활성 회원, 매출은 구매자의 현재 등급 경유(주문 시점 스냅샷 없음·D-182). */
    private List<GradeDistributionResponse> gradeDistribution(StatsPeriod period) {
        Map<BuyerGradeCode, Long> members = statsRepository.countActiveMembersByGrade().stream()
                .collect(Collectors.toMap(GradeCountProjection::getGradeCode, GradeCountProjection::getMemberCount));
        Map<BuyerGradeCode, Long> revenue = statsRepository.sumRevenueByGrade(period.start(), period.end()).stream()
                .collect(Collectors.toMap(GradeRevenueProjection::getGradeCode, GradeRevenueProjection::getRevenue));
        long totalMembers = members.values().stream().mapToLong(Long::longValue).sum();
        long totalRevenue = revenue.values().stream().mapToLong(Long::longValue).sum();
        List<GradeDistributionResponse> rows = new ArrayList<>(BuyerGradeCode.values().length);
        for (BuyerGradeCode code : BuyerGradeCode.values()) {
            rows.add(GradeDistributionResponse.of(code, members.getOrDefault(code, 0L), totalMembers,
                    revenue.getOrDefault(code, 0L), totalRevenue));
        }
        return rows;
    }

    private static BuyerAggregate aggregateBuyers(List<BuyerOrdersProjection> buyerOrders) {
        long repeatBuyers = 0;
        long repeatRevenue = 0;
        long firstTimeBuyers = 0;
        long firstTimeRevenue = 0;
        for (BuyerOrdersProjection row : buyerOrders) {
            if (row.getOrderCount() >= REPEAT_ORDER_THRESHOLD) {
                repeatBuyers++;
                repeatRevenue += row.getRevenue();
            } else {
                firstTimeBuyers++;
                firstTimeRevenue += row.getRevenue();
            }
        }
        return new BuyerAggregate(buyerOrders.size(), repeatBuyers, repeatRevenue, firstTimeBuyers, firstTimeRevenue);
    }

    /** 매출 내림차순 상위 20(Repository 정렬 유지). soft-delete 회원은 findByIdIn에서 빠져 publicId·name·email null. */
    private List<TopBuyerResponse> topBuyers(List<BuyerOrdersProjection> buyerOrders) {
        List<BuyerOrdersProjection> top = buyerOrders.stream().limit(TOP_BUYER_LIMIT).toList();
        if (top.isEmpty()) {
            return List.of();
        }
        Map<Long, User> users = userRepository.findByIdIn(top.stream().map(BuyerOrdersProjection::getBuyerId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return top.stream()
                .map(row -> {
                    User user = users.get(row.getBuyerId());
                    return new TopBuyerResponse(user == null ? null : user.getPublicId(), user == null ? null : user.getName(),
                            user == null ? null : user.getEmail(), row.getOrderCount(), row.getRevenue());
                })
                .toList();
    }

    private static Map<String, Long> countByBucket(List<StatsCountBucketProjection> rows) {
        return rows.stream().collect(Collectors.toMap(StatsCountBucketProjection::getBucket,
                StatsCountBucketProjection::getBucketCount));
    }
}
