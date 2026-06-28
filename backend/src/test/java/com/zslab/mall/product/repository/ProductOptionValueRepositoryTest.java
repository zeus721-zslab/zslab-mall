package com.zslab.mall.product.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductOptionGroup;
import com.zslab.mall.product.entity.ProductOptionValue;
import jakarta.persistence.PersistenceException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link ProductOptionValueRepository} @DataJpaTest — CRUD·FK·deleted_at 없음 확인.
 *
 * <p>product 시딩은 seller→category→product 정상 경로(FK_CHECKS 불필요).
 */
class ProductOptionValueRepositoryTest extends Batch1DataJpaTestBase {

    @Autowired
    private ProductOptionValueRepository productOptionValueRepository;

    @Autowired
    private ProductOptionGroupRepository productOptionGroupRepository;

    private long seedCategory() {
        entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO category (display_name, depth, sort_order, created_at, updated_at) "
            + "VALUES ('테스트카테고리', 0, 1, NOW(6), NOW(6))")
            .executeUpdate();
        return ((Number) entityManager.getEntityManager()
            .createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }

    private long seedSeller(String publicId) {
        entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO seller (public_id, company_name, ceo_name, status, created_at, updated_at) "
            + "VALUES ('" + publicId + "', '테스트셀러', '대표', 'ACTIVE', NOW(6), NOW(6))")
            .executeUpdate();
        return ((Number) entityManager.getEntityManager()
            .createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }

    private Product seedProduct(String productPublicId, long sellerId, long categoryId) {
        entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO product (public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
            + "VALUES ('" + productPublicId + "', " + sellerId + ", " + categoryId + ", '테스트상품', 'SALE', 10000, NOW(6), NOW(6))")
            .executeUpdate();
        long id = ((Number) entityManager.getEntityManager()
            .createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
        return entityManager.find(Product.class, id);
    }

    @Test
    @DisplayName("save+findById 성공: optionGroup FK 보존·value·displayOrder 확인")
    void save_findById_success() {
        long categoryId = seedCategory();
        long sellerId = seedSeller("slr_ov001234567890123456789012");
        Product product = seedProduct("prd_ov001234567890123456789012", sellerId, categoryId);
        ProductOptionGroup group = productOptionGroupRepository.saveAndFlush(
            ProductOptionGroup.create(product, "색상", 1));

        ProductOptionValue saved = productOptionValueRepository.saveAndFlush(
            ProductOptionValue.create(group, "빨강", 1));
        entityManager.clear();

        Optional<ProductOptionValue> found = productOptionValueRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getOptionGroup().getId()).isEqualTo(group.getId());
        assertThat(found.get().getValue()).isEqualTo("빨강");
        assertThat(found.get().getDisplayOrder()).isEqualTo(1);
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("findByOptionGroupId: 동일 옵션 그룹의 값 목록 반환")
    void findByOptionGroupId_success() {
        long categoryId = seedCategory();
        long sellerId = seedSeller("slr_ov101234567890123456789012");
        Product product = seedProduct("prd_ov101234567890123456789012", sellerId, categoryId);
        ProductOptionGroup group = productOptionGroupRepository.saveAndFlush(
            ProductOptionGroup.create(product, "색상", 1));

        productOptionValueRepository.saveAndFlush(ProductOptionValue.create(group, "빨강", 1));
        productOptionValueRepository.saveAndFlush(ProductOptionValue.create(group, "파랑", 2));
        entityManager.clear();

        List<ProductOptionValue> values = productOptionValueRepository.findByOptionGroupId(group.getId());

        assertThat(values).hasSize(2);
    }

    @Test
    @DisplayName("option_group_id FK 위반 삽입 → PersistenceException (FK RESTRICT)")
    void insert_invalidOptionGroupId_throwsPersistenceException() {
        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO product_option_value "
                    + "(option_group_id, value, display_order, created_at, updated_at) "
                    + "VALUES (99999, '빨강', 1, NOW(6), NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }
}
