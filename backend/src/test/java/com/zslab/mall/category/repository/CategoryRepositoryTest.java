package com.zslab.mall.category.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.category.entity.Category;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link CategoryRepository} @DataJpaTest — 루트 삽입·soft-delete 후 @SQLRestriction 검증.
 */
class CategoryRepositoryTest extends Batch1DataJpaTestBase {

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("루트 카테고리 삽입 (parent_id=null): id 할당·parent null 보존")
    void insert_rootCategory_parentIsNull() {
        Category root = categoryRepository.saveAndFlush(
                Category.create(null, "의류", 0, 1));
        entityManager.clear();

        Optional<Category> found = categoryRepository.findById(root.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getParent()).isNull();
        assertThat(found.get().getDisplayName()).isEqualTo("의류");
    }

    @Test
    @DisplayName("soft-delete 후 findById → Optional.empty() (@SQLRestriction 적용)")
    void findById_afterSoftDelete_returnsEmpty() {
        Category root = categoryRepository.saveAndFlush(
                Category.create(null, "가전", 0, 1));

        // JdbcTemplate soft-delete: @SQLRestriction("deleted_at IS NULL") 적용 검증
        jdbcTemplate.update(
                "UPDATE category SET deleted_at = NOW(6), deleted_by = 1 WHERE id = ?",
                root.getId());
        entityManager.clear();

        Optional<Category> found = categoryRepository.findById(root.getId());

        assertThat(found).isEmpty();
    }
}
