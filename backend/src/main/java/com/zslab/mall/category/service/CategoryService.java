package com.zslab.mall.category.service;

import com.zslab.mall.category.controller.request.CreateCategoryRequest;
import com.zslab.mall.category.controller.response.CreateCategoryResponse;
import com.zslab.mall.category.entity.Category;
import com.zslab.mall.category.exception.CategoryDuplicateException;
import com.zslab.mall.category.repository.CategoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 카테고리 Application Service(Track 46). ADMIN이 루트 카테고리를 생성하는 쓰기 경로를 제공한다. 트랜잭션 경계는 메서드 단위다.
 *
 * <p>중복 가드는 DB 제약(uk_category_dedup_key·V13)을 최종 방어선으로 삼는다(house 옵션 A·SellerProvisioningService 정합):
 * 앱단 existsBy 선검증 대신 {@code saveAndFlush}가 던지는 {@link DataIntegrityViolationException}을 409로 변환한다.
 * 메서드명 {@code createRootCategory}는 API가 루트 생성만 노출함을 명시하되, 도메인의 트리 전제(자식 확장 대비)를 유지한다.
 */
@Slf4j
@Service
@Transactional
public class CategoryService {

    /** API가 노출하는 루트 카테고리의 고정 계층 깊이. 자식 생성 경로는 본 트랙 범위 밖이다. */
    private static final int ROOT_DEPTH = 1;
    private static final Category ROOT_PARENT = null;

    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
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
}
