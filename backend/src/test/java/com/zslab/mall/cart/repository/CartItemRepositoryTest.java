package com.zslab.mall.cart.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.cart.entity.CartItem;
import jakarta.persistence.PersistenceException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link CartItemRepository} @DataJpaTest — CRUD·UK·FK constraint 검증.
 *
 * <p>product_variant는 Batch-3c 미구현 — nativeQuery seed(FK_CHECKS=0·LT-02 try-finally 복원).
 * user는 nativeQuery seed(public_id 필요).
 */
class CartItemRepositoryTest extends Batch1DataJpaTestBase {

    @Autowired
    private CartItemRepository cartItemRepository;

    private long seedUser(String publicId) {
        entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO `user` (public_id, created_at, updated_at) "
            + "VALUES ('" + publicId + "', NOW(6), NOW(6))")
            .executeUpdate();
        return ((Number) entityManager.getEntityManager()
            .createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }

    private long seedProductVariant(String publicId) {
        entityManager.getEntityManager()
            .createNativeQuery("SET FOREIGN_KEY_CHECKS=0")
            .executeUpdate();
        try {
            entityManager.getEntityManager().createNativeQuery(
                "INSERT INTO product_variant "
                + "(public_id, product_id, variant_code, additional_price, status, "
                + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                + "VALUES ('" + publicId + "', 1, 'VAR-001', 0, 'SALE', 0, 1, 1, NOW(6), NOW(6))")
                .executeUpdate();
            return ((Number) entityManager.getEntityManager()
                .createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
        } finally {
            entityManager.getEntityManager()
                .createNativeQuery("SET FOREIGN_KEY_CHECKS=1")
                .executeUpdate();
        }
    }

    @Test
    @DisplayName("save+findById 성공: userId·variantId·quantity·selected 확인")
    void save_findById_success() {
        long userId = seedUser("usr_crt01234567890123456789012");
        long variantId = seedProductVariant("var_crt01234567890123456789012");
        CartItem saved = cartItemRepository.saveAndFlush(CartItem.create(userId, variantId, 2));
        entityManager.clear();

        Optional<CartItem> found = cartItemRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getUserId()).isEqualTo(userId);
        assertThat(found.get().getVariantId()).isEqualTo(variantId);
        assertThat(found.get().getQuantity()).isEqualTo(2);
        assertThat(found.get().getSelected()).isTrue();
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("UK(user_id, variant_id) 중복 삽입 → DataIntegrityViolationException")
    void insert_duplicateUserVariant_throwsDataIntegrityViolation() {
        long userId = seedUser("usr_crt11234567890123456789012");
        long variantId = seedProductVariant("var_crt11234567890123456789012");
        cartItemRepository.saveAndFlush(CartItem.create(userId, variantId, 1));

        assertThatThrownBy(() ->
            cartItemRepository.saveAndFlush(CartItem.create(userId, variantId, 3))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("user_id FK 위반 삽입 → PersistenceException (FK RESTRICT)")
    void insert_invalidUserId_throwsPersistenceException() {
        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO cart_item (user_id, variant_id, quantity, selected, created_at, updated_at) "
                    + "VALUES (99999, 99999, 1, 1, NOW(6), NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("variant_id FK 위반 삽입 → PersistenceException (FK RESTRICT)")
    void insert_invalidVariantId_throwsPersistenceException() {
        long userId = seedUser("usr_crt21234567890123456789012");
        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO cart_item (user_id, variant_id, quantity, selected, created_at, updated_at) "
                    + "VALUES (" + userId + ", 99999, 1, 1, NOW(6), NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }

    // ===== Track 41 파생 쿼리(Cart Checkout·소비) =====

    @Test
    @DisplayName("findByUserIdAndSelectedTrue: selected=true만 반환·selected=false 제외·타 user 제외")
    void findByUserIdAndSelectedTrue_returnsOnlySelected() {
        long userId = seedUser(pid("usr_", "SELU1"));
        long otherUserId = seedUser(pid("usr_", "SELU2"));
        long variantSelected = seedProductVariant(pid("var_", "SELVA"));
        long variantUnselected = seedProductVariant(pid("var_", "SELVB"));
        long variantOther = seedProductVariant(pid("var_", "SELVC"));
        cartItemRepository.saveAndFlush(CartItem.create(userId, variantSelected, 2));    // selected=true(기본)
        seedCartItemSelected(userId, variantUnselected, 1, false);                        // selected=false
        cartItemRepository.saveAndFlush(CartItem.create(otherUserId, variantOther, 1));   // 타 user
        entityManager.clear();

        List<CartItem> result = cartItemRepository.findByUserIdAndSelectedTrue(userId);

        assertThat(result).extracting(CartItem::getVariantId).containsExactly(variantSelected);
    }

    @Test
    @DisplayName("deleteByUserIdAndVariantIdIn: 대상 삭제·buyer 스코프(타 user 미삭제)·비대상 잔존·반환 행수 1")
    void deleteByUserIdAndVariantIdIn_scopedDelete() {
        long userId = seedUser(pid("usr_", "DELU1"));
        long otherUserId = seedUser(pid("usr_", "DELU2"));
        long variantTarget = seedProductVariant(pid("var_", "DELVA"));
        long variantKeep = seedProductVariant(pid("var_", "DELVB"));
        cartItemRepository.saveAndFlush(CartItem.create(userId, variantTarget, 1));       // 삭제 대상
        cartItemRepository.saveAndFlush(CartItem.create(userId, variantKeep, 1));         // 비대상 잔존
        cartItemRepository.saveAndFlush(CartItem.create(otherUserId, variantTarget, 1));  // 타 user·미삭제

        long deleted = cartItemRepository.deleteByUserIdAndVariantIdIn(userId, List.of(variantTarget));
        entityManager.flush();
        entityManager.clear();

        assertThat(deleted).isEqualTo(1);
        assertThat(cartItemRepository.findByUserIdAndVariantId(userId, variantTarget)).isEmpty();
        assertThat(cartItemRepository.findByUserIdAndVariantId(userId, variantKeep)).isPresent();
        assertThat(cartItemRepository.findByUserIdAndVariantId(otherUserId, variantTarget)).isPresent();
    }

    @Test
    @DisplayName("deleteByUserIdAndVariantIdIn: 대상 부재 → 0 반환")
    void deleteByUserIdAndVariantIdIn_noMatch_returnsZero() {
        long userId = seedUser(pid("usr_", "DELU3"));
        long variantAbsent = seedProductVariant(pid("var_", "DELVZ"));

        long deleted = cartItemRepository.deleteByUserIdAndVariantIdIn(userId, List.of(variantAbsent));

        assertThat(deleted).isZero();
    }

    /** selected 플래그를 지정해 cart_item을 직접 seed한다(create()는 selected=true 고정이라 false 상태 재현용·positional 바인딩). */
    private void seedCartItemSelected(long userId, long variantId, int quantity, boolean selected) {
        entityManager.getEntityManager().createNativeQuery(
                "INSERT INTO cart_item (user_id, variant_id, quantity, selected, created_at, updated_at) "
                + "VALUES (?1, ?2, ?3, ?4, NOW(6), NOW(6))")
            .setParameter(1, userId)
            .setParameter(2, variantId)
            .setParameter(3, quantity)
            .setParameter(4, selected ? 1 : 0)
            .executeUpdate();
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
