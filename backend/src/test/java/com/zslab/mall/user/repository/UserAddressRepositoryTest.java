package com.zslab.mall.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.entity.UserAddress;
import jakarta.persistence.PersistenceException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link UserAddressRepository} @DataJpaTest — CRUD·FK·soft-delete @SQLRestriction(LT-03) 검증.
 */
class UserAddressRepositoryTest extends Batch1DataJpaTestBase {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserAddressRepository userAddressRepository;

    @Test
    @DisplayName("save+findById 성공: user FK 보존·is_default·deletedAt null")
    void save_findById_success() {
        User user = userRepository.saveAndFlush(User.create("addr@example.com", "배송지사용자", "010-1234-5678"));
        UserAddress saved = userAddressRepository.saveAndFlush(
            UserAddress.create(user, true, null, "홍길동", "010-9876-5432", "12345", "서울시 강남구 테헤란로 1", null, null));
        entityManager.clear();

        Optional<UserAddress> found = userAddressRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getUser().getId()).isEqualTo(user.getId());
        assertThat(found.get().isDefault()).isTrue();
        assertThat(found.get().getRecipientName()).isEqualTo("홍길동");
        assertThat(found.get().getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("user_id FK 위반 nativeQuery → PersistenceException (FK RESTRICT)")
    void insert_invalidUserId_throwsPersistenceException() {
        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO user_address (user_id, is_default, recipient_name, recipient_phone, "
                    + "zonecode, address_road, created_at, updated_at) "
                    + "VALUES (99999, 0, '수령인', '010-0000-0000', '00000', '도로명주소', NOW(6), NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("soft-delete 후 findById Optional.empty (@SQLRestriction LT-03 검증)")
    void findById_afterSoftDelete_returnsEmpty() {
        User user = userRepository.saveAndFlush(User.create("softaddr@example.com", "삭제배송지", "010-5555-5555"));
        UserAddress saved = userAddressRepository.saveAndFlush(
            UserAddress.create(user, false, null, "삭제수령인", "010-1111-2222", "99999", "삭제도로명 1", null, null));
        entityManager.clear();

        entityManager.getEntityManager()
            .createNativeQuery("UPDATE user_address SET deleted_at = NOW(6) WHERE id = :id")
            .setParameter("id", saved.getId())
            .executeUpdate();
        entityManager.clear();

        Optional<UserAddress> found = userAddressRepository.findById(saved.getId());
        assertThat(found).isEmpty();
    }
}
