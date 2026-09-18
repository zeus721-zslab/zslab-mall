package com.zslab.mall.category.service;

import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.category.controller.request.CreateCategoryRequest;
import com.zslab.mall.category.controller.request.UpdateCategoryRequest;
import com.zslab.mall.category.controller.response.CreateCategoryResponse;
import com.zslab.mall.category.entity.Category;
import com.zslab.mall.category.exception.CategoryDuplicateException;
import com.zslab.mall.category.exception.CategoryHasProductsException;
import com.zslab.mall.category.exception.CategoryNotFoundException;
import com.zslab.mall.category.repository.CategoryRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.product.repository.ProductRepository;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 카테고리 Application Service(Track 46 생성 → Track 89-C D-185 수정·삭제·정렬 추가). ADMIN 쓰기 경로를 제공한다. 트랜잭션 경계는
 * 메서드 단위다.
 *
 * <p>중복 가드는 DB 제약(uk_category_dedup_key·V13)을 최종 방어선으로 삼는다(house 옵션 A·SellerProvisioningService 정합):
 * 앱단 existsBy 선검증 대신 flush가 던지는 {@link DataIntegrityViolationException}을 409로 변환한다(생성·수정 공통).
 * 메서드명 {@code createRootCategory}는 API가 루트 생성만 노출함을 명시하되, 도메인의 트리 전제(자식 확장 대비)를 유지한다.
 *
 * <p><b>수정·삭제 감사(89-A·89-B 규약)</b>: 값이 실제로 바뀐 경우에만 AuditRecorder에 남긴다. 수수료율은 정산 금액에 영향을 주는 값이라
 * 실변경 시 사유가 필수이고(서비스 diff 판정·공백 400), 표시명·정렬만 바뀔 때는 사유를 요구하지 않는다. 일괄 정렬은 노출 순서만 바꾸므로
 * 사유·감사 없이 처리한다.
 */
@Slf4j
@Service
@Transactional
public class CategoryService {

    /** API가 노출하는 루트 카테고리의 고정 계층 깊이. 자식 생성 경로는 본 트랙 범위 밖이다. */
    private static final int ROOT_DEPTH = 1;
    private static final Category ROOT_PARENT = null;

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final AuditRecorder auditRecorder;

    public CategoryService(CategoryRepository categoryRepository, ProductRepository productRepository,
            AuditRecorder auditRecorder) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.auditRecorder = auditRecorder;
    }

    /**
     * 루트 카테고리를 생성한다. parent=null·depth=1로 고정한다.
     *
     * @param request 생성 요청(displayName·sortOrder)
     * @return 생성된 카테고리 식별자·표시명·depth·sortOrder
     * @throws CategoryDuplicateException 같은 스코프에 동일 displayName 활성 카테고리가 이미 있을 때(409·uk_category_dedup_key)
     */
    public CreateCategoryResponse createRootCategory(CreateCategoryRequest request) {
        Category category = Category.create(ROOT_PARENT, request.displayName(), ROOT_DEPTH, request.sortOrder());
        try {
            // saveAndFlush로 uk_category_dedup_key(V13) 위반을 트랜잭션 내에서 즉시 표면화한다. 위반 시 아래 catch가 409로 변환한다.
            Category saved = categoryRepository.saveAndFlush(category);
            log.info("[Category] 루트 카테고리 생성 categoryId={} displayName={}", saved.getId(), saved.getDisplayName());
            return new CreateCategoryResponse(
                    saved.getId(), saved.getDisplayName(), saved.getDepth(), saved.getSortOrder());
        } catch (DataIntegrityViolationException exception) {
            log.warn("[Category] 카테고리 중복 차단(409·uk_category_dedup_key) displayName={}: {}",
                    request.displayName(), exception.getMostSpecificCause().getMessage());
            throw new CategoryDuplicateException(
                    "이미 존재하는 카테고리입니다: displayName=" + request.displayName());
        }
    }

    /**
     * 카테고리 수정(전체 치환·Track 89-C D-185). 표시명·정렬·수수료율 3필드를 그대로 반영한다. 수수료율 변경은 변경 이후 체크아웃되는
     * 주문의 order_item 스냅샷에만 반영되며 기존 주문·생성된 정산·재생성에는 영향이 없다(CommissionRateResolver·SettlementCreationService).
     *
     * @return 수정 반영된 카테고리
     * @throws CategoryNotFoundException  categoryId 미존재(404)
     * @throws MalformedRequestException  수수료율이 실제로 바뀌는데 사유가 공백일 때(400)
     * @throws CategoryDuplicateException 다른 활성 카테고리와 displayName 중복(409·uk_category_dedup_key)
     */
    public Category update(Long categoryId, UpdateCategoryRequest request, AuditContext auditContext) {
        Category category = findRoot(categoryId);
        String displayName = request.displayName().trim();
        boolean commissionRateChanged = !Objects.equals(category.getCommissionRate(), request.commissionRate());
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (commissionRateChanged && reason.isEmpty()) {
            throw new MalformedRequestException("수수료율 변경 시 사유는 필수입니다: categoryId=" + categoryId);
        }
        boolean changed = commissionRateChanged
                || !displayName.equals(category.getDisplayName())
                || request.sortOrder() != category.getSortOrder();
        if (!changed) {
            log.info("[Category] 수정 요청 값 무변경 → 감사 skip categoryId={}", categoryId);
            return category;
        }
        Map<String, Object> before = snapshot(category);
        category.update(displayName, request.sortOrder(), request.commissionRate());
        try {
            // flush로 uk_category_dedup_key(V13) 위반을 즉시 표면화한다(생성 경로와 같은 409 변환).
            categoryRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            log.warn("[Category] 카테고리 수정 중복 차단(409·uk_category_dedup_key) displayName={}: {}",
                    displayName, exception.getMostSpecificCause().getMessage());
            throw new CategoryDuplicateException("이미 존재하는 카테고리입니다: displayName=" + displayName);
        }
        Map<String, Object> after = snapshot(category);
        if (!reason.isEmpty()) {
            after.put("reason", reason);
        }
        auditRecorder.record(auditContext, AuditLogAction.UPDATE, PolymorphicTargetType.CATEGORY, categoryId, before, after);
        log.info("[Category] 수정 categoryId={} commissionRateChanged={}", categoryId, commissionRateChanged);
        return category;
    }

    /**
     * 카테고리 soft-delete(Track 89-C D-185). 활성 상품이 0건일 때만 허용한다 — soft-delete는 fk_product_category RESTRICT를 우회하므로
     * 상품이 남은 채 삭제되면 카탈로그 enrich에서 카테고리명이 빠진다. 삭제 후 공개 목록·상품 등록 드롭다운에서는 {@code @SQLRestriction}이 제외한다.
     *
     * @throws CategoryNotFoundException    categoryId 미존재(404)
     * @throws CategoryHasProductsException 활성 상품이 1건 이상 연결돼 있을 때(409·메시지에 건수)
     */
    public void delete(Long categoryId, AuditContext auditContext) {
        Category category = findRoot(categoryId);
        long productCount = productRepository.countByCategoryId(categoryId);
        if (productCount > 0) {
            throw new CategoryHasProductsException(
                    "연결된 상품이 있어 삭제할 수 없습니다: categoryId=" + categoryId + " productCount=" + productCount);
        }
        Map<String, Object> before = snapshot(category);
        before.put("deleted", false);
        category.markDeleted();
        auditRecorder.record(auditContext, AuditLogAction.DELETE, PolymorphicTargetType.CATEGORY, categoryId,
                before, Map.of("deleted", true));
        log.info("[Category] soft-delete categoryId={} displayName={}", categoryId, category.getDisplayName());
    }

    /**
     * 루트 카테고리 일괄 정렬 변경(Track 89-C D-185). 배열 index를 sortOrder로 반영한다. 활성 루트 전체와 정확히 일치해야 한다.
     *
     * @throws MalformedRequestException 중복 id·누락 id·미존재(또는 삭제) id가 있을 때(400)
     */
    public void reorder(List<Long> categoryIds) {
        Set<Long> uniqueIds = new HashSet<>(categoryIds);
        if (uniqueIds.size() != categoryIds.size()) {
            throw new MalformedRequestException("정렬 요청에 중복 categoryId가 있습니다.");
        }
        Map<Long, Category> rootById = categoryRepository.findByParentIsNullOrderBySortOrderAscIdAsc().stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        if (!uniqueIds.equals(rootById.keySet())) {
            throw new MalformedRequestException("정렬 요청은 활성 루트 카테고리 전체 id와 일치해야 합니다: 요청 "
                    + categoryIds + " / 현재 " + rootById.keySet());
        }
        for (int index = 0; index < categoryIds.size(); index++) {
            rootById.get(categoryIds.get(index)).changeSortOrder(index);
        }
        log.info("[Category] 정렬 변경 order={}", categoryIds);
    }

    private Category findRoot(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .filter(category -> category.getParent() == null)
                .orElseThrow(() -> new CategoryNotFoundException("카테고리를 찾을 수 없습니다: categoryId=" + categoryId));
    }

    /** 감사 before/after 필드맵. commissionRate가 null일 수 있어 Map.of 대신 LinkedHashMap을 쓴다. */
    private static Map<String, Object> snapshot(Category category) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("displayName", category.getDisplayName());
        fields.put("sortOrder", category.getSortOrder());
        fields.put("commissionRate", category.getCommissionRate());
        return fields;
    }
}
