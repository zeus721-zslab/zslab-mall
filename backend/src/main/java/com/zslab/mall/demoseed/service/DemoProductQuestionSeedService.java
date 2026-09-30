package com.zslab.mall.demoseed.service;

import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.auth.exception.SuperAdminRequiredException;
import com.zslab.mall.auth.repository.UserRoleRepository;
import com.zslab.mall.category.entity.Category;
import com.zslab.mall.category.repository.CategoryRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.demoseed.controller.response.DemoSeedProductQuestionResponse;
import com.zslab.mall.demoseed.controller.response.DemoSeedProductQuestionResponse.ExcludedSeller;
import com.zslab.mall.demoseed.controller.response.DemoSeedProductQuestionResponse.FailedProduct;
import com.zslab.mall.demoseed.controller.response.DemoSeedProductQuestionResponse.ProductPlan;
import com.zslab.mall.demoseed.repository.DemoSeedRepository;
import com.zslab.mall.demoseed.service.DemoProductQuestionWriter.ProductTarget;
import com.zslab.mall.demoseed.template.DemoProductQuestionTemplates;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.productquestion.entity.ProductQuestion;
import com.zslab.mall.productquestion.enums.ProductQuestionStatus;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.seller.repository.SellerRepository;
import com.zslab.mall.seller.repository.SellerUserRepository;
import com.zslab.mall.user.entity.User;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 데모 상품 Q&A 적재(D-244 · SUPER_ADMIN 전용 관리자 API). 대상 = 상호 '데모 %' · ACTIVE · 소유 구성원이 있는 셀러의 SALE 상품. 상품마다 공개
 * 질문 10건 이상 · 공개 미답변 1~5건이 되도록 부족분만 만든다(재호출 추가 0). 트랜잭션은 상품 단위({@link DemoProductQuestionWriter})라 이
 * 클래스는 트랜잭션을 열지 않는다 — 한 상품 실패는 그 상품만 롤백하고 다음 상품으로 진행한다. dryRun이면 계획만 돌려주고 쓰기·감사가 없다.
 */
@Slf4j
@Service
public class DemoProductQuestionSeedService {

    static final String DEMO_SELLER_PATTERN = "데모 %";
    static final String DEMO_BUYER_EMAIL_PATTERN = "%@demo.zslab-mall.com";
    /** 실행 단위 감사라 특정 행이 없다(PolymorphicTargetType.DEMO_SEED). */
    private static final long AUDIT_TARGET_ID = 0L;
    static final String REASON_NOT_ACTIVE = "셀러 상태가 ACTIVE가 아님";
    static final String REASON_NO_OWNER = "소유 구성원 없음";
    static final String REASON_NO_BUYER = "질문 작성자로 쓸 데모 구매자 없음";

    private final UserRoleRepository userRoleRepository;
    private final SellerRepository sellerRepository;
    private final SellerUserRepository sellerUserRepository;
    private final CategoryRepository categoryRepository;
    private final DemoSeedRepository demoSeedRepository;
    private final DemoProductQuestionWriter writer;
    private final AuditRecorder auditRecorder;

    public DemoProductQuestionSeedService(UserRoleRepository userRoleRepository, SellerRepository sellerRepository,
            SellerUserRepository sellerUserRepository, CategoryRepository categoryRepository, DemoSeedRepository demoSeedRepository,
            DemoProductQuestionWriter writer, AuditRecorder auditRecorder) {
        this.userRoleRepository = userRoleRepository;
        this.sellerRepository = sellerRepository;
        this.sellerUserRepository = sellerUserRepository;
        this.categoryRepository = categoryRepository;
        this.demoSeedRepository = demoSeedRepository;
        this.writer = writer;
        this.auditRecorder = auditRecorder;
    }

    /** 대상 셀러와 그 소유 구성원(답변자). */
    private record TargetSeller(Seller seller, Long ownerUserId) {
    }

    /**
     * @throws SuperAdminRequiredException 호출자가 SUPER_ADMIN이 아닐 때(403 · dryRun 포함)
     */
    public DemoSeedProductQuestionResponse seed(Long callerUserId, boolean dryRun, AuditContext auditContext) {
        if (!userRoleRepository.existsByUserIdAndRole_Code(callerUserId, RoleCode.SUPER_ADMIN)) {
            log.warn("[DemoSeed] SUPER_ADMIN 아님 차단(403) callerUserId={}", callerUserId);
            throw new SuperAdminRequiredException("SUPER_ADMIN만 데모 데이터를 적재할 수 있습니다.");
        }
        List<ExcludedSeller> excluded = new ArrayList<>();
        Map<Long, TargetSeller> targets = selectSellers(excluded);
        List<Product> products = targets.isEmpty() ? List.of()
                : demoSeedRepository.findProductsBySellerIdsAndStatus(targets.keySet(), ProductStatus.SALE);
        Map<Long, List<ProductQuestion>> questionsByProduct = products.isEmpty() ? Map.of()
                : demoSeedRepository.findQuestionsByProductIds(products.stream().map(Product::getId).toList()).stream()
                        .collect(Collectors.groupingBy(ProductQuestion::getProductId));
        Map<Long, String> categoryNames = categoryRepository.findByIdIn(products.stream().map(Product::getCategoryId).distinct().toList())
                .stream().collect(Collectors.toMap(Category::getId, Category::getDisplayName));
        List<User> buyers = demoSeedRepository.findDemoBuyers(DEMO_BUYER_EMAIL_PATTERN, RoleCode.BUYER);
        LocalDateTime now = LocalDateTime.now();

        List<ProductPlan> plans = new ArrayList<>();
        List<FailedProduct> failed = new ArrayList<>();
        int createdQuestionCount = 0;
        int createdAnswerCount = 0;
        for (Product product : products) {
            TargetSeller target = targets.get(product.getSellerId());
            List<ProductQuestion> questions = questionsByProduct.getOrDefault(product.getId(), List.of());
            int publicCount = (int) questions.stream().filter(DemoProductQuestionSeedService::isPublic).count();
            int publicUnanswered = (int) questions.stream().filter(question -> isPublic(question) && !question.isAnswered()).count();
            DemoQuestionQuota quota = DemoQuestionQuota.of(product.getId(), publicCount, publicUnanswered);
            plans.add(new ProductPlan(product.getPublicId(), product.getName(), target.seller().getCompanyName(), publicCount,
                    publicUnanswered, quota.total(), quota.addAnswered()));
            if (quota.total() == 0) {
                continue;
            }
            if (buyers.isEmpty()) {
                failed.add(new FailedProduct(product.getPublicId(), REASON_NO_BUYER));
                continue;
            }
            if (dryRun) {
                continue;
            }
            Set<String> existingContents = questions.stream().map(ProductQuestion::getContent).collect(Collectors.toSet());
            ProductTarget productTarget = new ProductTarget(product.getId(), product.getPublicId(), product.getCreatedAt(),
                    product.getSellerId(), target.ownerUserId(),
                    DemoProductQuestionTemplates.forCategory(categoryNames.get(product.getCategoryId())), existingContents, quota);
            try {
                writer.write(productTarget, buyers, now);
                createdQuestionCount += quota.total();
                createdAnswerCount += quota.addAnswered();
            } catch (RuntimeException exception) {
                // 상품 단위 격리: 이 상품만 롤백됐고 앞서 끝난 상품은 커밋돼 있다 — 응답에 실패로 남기고 다음 상품으로 진행한다
                log.warn("[DemoSeed] 상품 적재 실패(롤백) productPublicId={} reason={}", product.getPublicId(), exception.getMessage(), exception);
                failed.add(new FailedProduct(product.getPublicId(), exception.getClass().getSimpleName() + ": " + exception.getMessage()));
            }
        }
        if (!dryRun) {
            recordAudit(auditContext, products.size(), createdQuestionCount, createdAnswerCount, failed.size());
        }
        log.info("[DemoSeed] 상품 Q&A dryRun={} 대상 셀러 {} · 상품 {} · 질문 +{} · 답변 +{} · 제외 셀러 {} · 실패 상품 {}", dryRun, targets.size(),
                products.size(), createdQuestionCount, createdAnswerCount, excluded.size(), failed.size());
        return new DemoSeedProductQuestionResponse(dryRun, targets.size(), products.size(), createdQuestionCount, createdAnswerCount,
                plans, excluded, failed);
    }

    /** 데모 상호 셀러 중 ACTIVE이고 소유 구성원이 있는 셀러(id 순). 나머지는 사유와 함께 제외 목록에 넣는다. */
    private Map<Long, TargetSeller> selectSellers(List<ExcludedSeller> excluded) {
        List<Seller> sellers = new ArrayList<>(sellerRepository.findByIdIn(sellerRepository.findIdsByCompanyNameLike(DEMO_SELLER_PATTERN)));
        sellers.sort(Comparator.comparing(Seller::getId));
        Map<Long, TargetSeller> targets = new LinkedHashMap<>();
        for (Seller seller : sellers) {
            if (seller.getStatus() != SellerStatus.ACTIVE) {
                excluded.add(new ExcludedSeller(seller.getPublicId(), seller.getCompanyName(), REASON_NOT_ACTIVE));
                continue;
            }
            List<Long> owners = sellerUserRepository.findUserIdsBySellerIdAndRoleCode(seller.getId(), RoleCode.SELLER_OWNER);
            if (owners.isEmpty()) {
                excluded.add(new ExcludedSeller(seller.getPublicId(), seller.getCompanyName(), REASON_NO_OWNER));
                continue;
            }
            targets.put(seller.getId(), new TargetSeller(seller, Collections.min(owners)));
        }
        return targets;
    }

    private static boolean isPublic(ProductQuestion question) {
        return question.getStatus() == ProductQuestionStatus.VISIBLE;
    }

    /** 실행 1회당 감사 1건(0건 적재여도 남긴다 — 누가 언제 실행했는지가 목적). */
    private void recordAudit(AuditContext auditContext, int targetProductCount, int createdQuestionCount, int createdAnswerCount,
            int failedProductCount) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("targetProductCount", targetProductCount);
        after.put("createdQuestionCount", createdQuestionCount);
        after.put("createdAnswerCount", createdAnswerCount);
        after.put("failedProductCount", failedProductCount);
        auditRecorder.record(auditContext, AuditLogAction.CREATE, PolymorphicTargetType.DEMO_SEED, AUDIT_TARGET_ID, Map.of(), after);
    }
}
