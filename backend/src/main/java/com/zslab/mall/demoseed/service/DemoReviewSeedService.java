package com.zslab.mall.demoseed.service;

import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.auth.exception.SuperAdminRequiredException;
import com.zslab.mall.category.entity.Category;
import com.zslab.mall.category.repository.CategoryRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.common.security.PublicDemoSessionGuard;
import com.zslab.mall.demoseed.controller.response.DemoSeedReviewResponse;
import com.zslab.mall.demoseed.controller.response.DemoSeedReviewResponse.FailedItem;
import com.zslab.mall.demoseed.repository.DemoReviewCandidateRow;
import com.zslab.mall.demoseed.repository.DemoReviewContentRow;
import com.zslab.mall.demoseed.repository.DemoSeedRepository;
import com.zslab.mall.demoseed.service.DemoReviewPlanner.Candidate;
import com.zslab.mall.demoseed.service.DemoReviewPlanner.PlannedReview;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.review.entity.ReviewKeyword;
import com.zslab.mall.review.enums.ReviewStatus;
import com.zslab.mall.review.repository.ReviewKeywordRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 데모 리뷰 적재(D-245 · SUPER_ADMIN 전용 관리자 API). 데모 구매자의 공개 리뷰가 {@link DemoReviewPlanner#TARGET_PUBLIC_REVIEWS}건이 되도록
 * 부족분만 만든다(재호출 추가 0). 대상 = 데모 구매자 본인 주문의 구매확정 품목 중 리뷰 이력(삭제 포함)이 없고 데모 상호 셀러 상품인 것 · 작성자 = 그
 * 품목의 구매자. 사진·도움됐어요는 만들지 않고 키워드는 이미 있는 code만 쓴다. 트랜잭션은 품목 단위({@link DemoReviewWriter})라 이 클래스는
 * 트랜잭션을 열지 않는다. dryRun이면 계획만 돌려주고 쓰기·감사가 없다.
 */
@Slf4j
@Service
public class DemoReviewSeedService {

    /** 실행 단위 감사라 특정 행이 없다(PolymorphicTargetType.DEMO_SEED). */
    private static final long AUDIT_TARGET_ID = 0L;

    private final DemoSeedAuthorization authorization;
    private final DemoSeedRepository demoSeedRepository;
    private final CategoryRepository categoryRepository;
    private final ReviewKeywordRepository reviewKeywordRepository;
    private final DemoReviewWriter writer;
    private final AuditRecorder auditRecorder;
    private final PublicDemoSessionGuard publicDemoSessionGuard;

    public DemoReviewSeedService(DemoSeedAuthorization authorization, DemoSeedRepository demoSeedRepository,
            CategoryRepository categoryRepository, ReviewKeywordRepository reviewKeywordRepository, DemoReviewWriter writer,
            AuditRecorder auditRecorder, PublicDemoSessionGuard publicDemoSessionGuard) {
        this.authorization = authorization;
        this.demoSeedRepository = demoSeedRepository;
        this.categoryRepository = categoryRepository;
        this.reviewKeywordRepository = reviewKeywordRepository;
        this.writer = writer;
        this.auditRecorder = auditRecorder;
        this.publicDemoSessionGuard = publicDemoSessionGuard;
    }

    /**
     * @throws SuperAdminRequiredException 호출자가 SUPER_ADMIN이 아닐 때(403 · dryRun 포함)
     * @throws com.zslab.mall.common.exception.PublicDemoSessionRestrictedException 공개 관리자 데모 세션(403 · dryRun 포함)
     */
    public DemoSeedReviewResponse seed(Long callerUserId, boolean dryRun, AuditContext auditContext) {
        publicDemoSessionGuard.requireNotPublicDemoSession();
        authorization.requireSuperAdmin(callerUserId);
        long publicCountBefore = demoSeedRepository.countDemoPublicReviews(DemoProductQuestionSeedService.DEMO_BUYER_EMAIL_PATTERN,
                RoleCode.BUYER, ReviewStatus.VISIBLE);
        int need = (int) Math.max(0, DemoReviewPlanner.TARGET_PUBLIC_REVIEWS - publicCountBefore);
        List<Candidate> candidates = need == 0 ? List.of() : findCandidates();
        List<PlannedReview> planned = need == 0 ? List.of() : plan(need, candidates);

        List<FailedItem> failed = new ArrayList<>();
        int createdCount = 0;
        if (!dryRun) {
            for (PlannedReview review : planned) {
                try {
                    writer.write(review);
                    createdCount++;
                } catch (RuntimeException exception) {
                    // 품목 단위 격리: 이 품목만 롤백됐고 앞서 끝난 품목은 커밋돼 있다 — 응답에 실패로 남기고 다음 품목으로 진행한다
                    log.warn("[DemoSeed] 리뷰 적재 실패(롤백) orderItemPublicId={} reason={}", review.candidate().orderItemPublicId(),
                            exception.getMessage(), exception);
                    failed.add(new FailedItem(review.candidate().orderItemPublicId(),
                            exception.getClass().getSimpleName() + ": " + exception.getMessage()));
                }
            }
            recordAudit(auditContext, publicCountBefore, planned.size(), createdCount, failed.size());
        }
        log.info("[DemoSeed] 리뷰 dryRun={} 공개 {} → 목표 {} · 후보 {} · 계획 {} · 생성 {} · 실패 {}", dryRun, publicCountBefore,
                DemoReviewPlanner.TARGET_PUBLIC_REVIEWS, candidates.size(), planned.size(), createdCount, failed.size());
        return new DemoSeedReviewResponse(dryRun, publicCountBefore, DemoReviewPlanner.TARGET_PUBLIC_REVIEWS, candidates.size(),
                planned.size(), createdCount, byBuyer(planned), byRating(planned), failed);
    }

    /** 후보 품목에서 리뷰 이력(삭제 포함)이 있는 품목을 뺀다. */
    private List<Candidate> findCandidates() {
        List<DemoReviewCandidateRow> rows = demoSeedRepository.findReviewCandidates(DemoProductQuestionSeedService.DEMO_BUYER_EMAIL_PATTERN,
                RoleCode.BUYER, OrderItemStatus.CONFIRMED, DemoProductQuestionSeedService.DEMO_SELLER_PATTERN);
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> reviewed = new HashSet<>(demoSeedRepository.findReviewedOrderItemIds(
                rows.stream().map(DemoReviewCandidateRow::getOrderItemId).toList()));
        return rows.stream()
                .filter(row -> !reviewed.contains(row.getOrderItemId()))
                .map(row -> new Candidate(row.getOrderItemId(), row.getOrderItemPublicId(), row.getProductId(), row.getProductName(),
                        row.getCategoryId(), row.getBuyerId(), row.getBuyerEmail(), row.getConfirmedAt()))
                .toList();
    }

    private List<PlannedReview> plan(int need, List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Long> productIds = candidates.stream().map(Candidate::productId).distinct().toList();
        Map<Long, Set<String>> existingContents = demoSeedRepository.findReviewContentsByProductIds(productIds).stream()
                .collect(Collectors.groupingBy(DemoReviewContentRow::getProductId,
                        Collectors.mapping(DemoReviewContentRow::getContent, Collectors.toSet())));
        List<Long> categoryIds = candidates.stream().map(Candidate::categoryId).distinct().toList();
        Map<Long, String> categoryNames = categoryRepository.findByIdIn(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, Category::getDisplayName));
        Map<Long, List<String>> usableKeywordCodes = new LinkedHashMap<>();
        for (Long categoryId : categoryIds) {
            usableKeywordCodes.put(categoryId,
                    reviewKeywordRepository.findUsable(categoryId).stream().map(ReviewKeyword::getCode).toList());
        }
        return DemoReviewPlanner.plan(need, candidates, existingContents, categoryNames, usableKeywordCodes, LocalDateTime.now());
    }

    /** 구매자별 건수. 대상이 데모 도메인 계정뿐이고 SUPER_ADMIN 전용 응답이라 이메일 원문으로 둔다(앞 2글자 마스킹은 buyer01~10이 모두 겹친다). */
    private static Map<String, Integer> byBuyer(List<PlannedReview> planned) {
        Map<String, Integer> counts = new TreeMap<>();
        planned.forEach(review -> counts.merge(review.candidate().buyerEmail(), 1, Integer::sum));
        return counts;
    }

    private static Map<Integer, Integer> byRating(List<PlannedReview> planned) {
        Map<Integer, Integer> counts = new TreeMap<>();
        planned.forEach(review -> counts.merge(review.rating(), 1, Integer::sum));
        return counts;
    }

    /** 실행 1회당 감사 1건(0건 적재여도 남긴다 — 누가 언제 실행했는지가 목적). */
    private void recordAudit(AuditContext auditContext, long publicReviewCountBefore, int plannedReviewCount, int createdReviewCount,
            int failedItemCount) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("publicReviewCountBefore", publicReviewCountBefore);
        after.put("plannedReviewCount", plannedReviewCount);
        after.put("createdReviewCount", createdReviewCount);
        after.put("failedItemCount", failedItemCount);
        auditRecorder.record(auditContext, AuditLogAction.CREATE, PolymorphicTargetType.DEMO_SEED, AUDIT_TARGET_ID, Map.of(), after);
    }
}
