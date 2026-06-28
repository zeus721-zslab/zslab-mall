package com.zslab.mall.product.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductImage;
import jakarta.persistence.PersistenceException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link ProductImageRepository} @DataJpaTest — CRUD·FK·LT-03 soft-delete 검증.
 *
 * <p>product 시딩은 seller→category→product 정상 경로(FK_CHECKS 불필요).
 * product.status 유효 ENUM: DRAFT·PENDING·APPROVED·REJECTED·SALE·HIDDEN·STOPPED (ACTIVE 없음).
 */
class ProductImageRepositoryTest extends Batch1DataJpaTestBase {

    @Autowired
    private ProductImageRepository productImageRepository;

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
    @DisplayName("save+findById 성공: product FK 보존·imageUrl·displayOrder·isMain 확인")
    void save_findById_success() {
        long categoryId = seedCategory();
        long sellerId = seedSeller("slr_pi001234567890123456789012");
        Product product = seedProduct("prd_pi001234567890123456789012", sellerId, categoryId);

        ProductImage saved = productImageRepository.saveAndFlush(
            ProductImage.create(product, "https://cdn.example.com/img1.jpg", 1, true));
        entityManager.clear();

        Optional<ProductImage> found = productImageRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getProduct().getId()).isEqualTo(product.getId());
        assertThat(found.get().getImageUrl()).isEqualTo("https://cdn.example.com/img1.jpg");
        assertThat(found.get().getDisplayOrder()).isEqualTo(1);
        assertThat(found.get().isMain()).isTrue();
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("findByProductId: 동일 product의 이미지 목록 반환")
    void findByProductId_success() {
        long categoryId = seedCategory();
        long sellerId = seedSeller("slr_pi101234567890123456789012");
        Product product = seedProduct("prd_pi101234567890123456789012", sellerId, categoryId);

        productImageRepository.saveAndFlush(
            ProductImage.create(product, "https://cdn.example.com/a.jpg", 1, true));
        productImageRepository.saveAndFlush(
            ProductImage.create(product, "https://cdn.example.com/b.jpg", 2, false));
        entityManager.clear();

        List<ProductImage> images = productImageRepository.findByProductId(product.getId());

        assertThat(images).hasSize(2);
    }

    @Test
    @DisplayName("LT-03 검증: soft-delete 후 findById Optional.empty (deleted_at IS NULL 필터)")
    void softDelete_findById_returnsEmpty() {
        long categoryId = seedCategory();
        long sellerId = seedSeller("slr_pi201234567890123456789012");
        Product product = seedProduct("prd_pi201234567890123456789012", sellerId, categoryId);

        ProductImage saved = productImageRepository.saveAndFlush(
            ProductImage.create(product, "https://cdn.example.com/del.jpg", 1, false));
        entityManager.clear();

        entityManager.getEntityManager().createNativeQuery(
            "UPDATE product_image SET deleted_at = NOW(6), deleted_by = 1 WHERE id = " + saved.getId())
            .executeUpdate();
        entityManager.clear();

        Optional<ProductImage> found = productImageRepository.findById(saved.getId());
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("product_id FK 위반 삽입 → PersistenceException (FK RESTRICT)")
    void insert_invalidProductId_throwsPersistenceException() {
        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO product_image "
                    + "(product_id, image_url, display_order, is_main, created_at, updated_at) "
                    + "VALUES (99999, 'https://cdn.example.com/x.jpg', 1, 0, NOW(6), NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }
}
