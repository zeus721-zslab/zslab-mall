package com.zslab.mall.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.user.entity.User;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link UserRepository} @DataJpaTest — CRUD·UK·soft-delete @SQLRestriction(LT-03) 검증.
 */
class UserRepositoryTest extends Batch1DataJpaTestBase {

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("save+findById 성공: public_id usr_ prefix 자동 생성·createdAt·updatedAt 자동")
    void save_findById_success() {
        User saved = userRepository.saveAndFlush(User.create("test@example.com", "홍길동", "010-1234-5678"));
        entityManager.clear();

        Optional<User> found = userRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getPublicId()).startsWith("usr");
        assertThat(found.get().getEmail()).isEqualTo("test@example.com");
        assertThat(found.get().getCreatedAt()).isNotNull();
        assertThat(found.get().getUpdatedAt()).isNotNull();
        assertThat(found.get().getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("UK(email) 중복 삽입 → DataIntegrityViolationException")
    void insert_duplicateEmail_throwsDataIntegrityViolation() {
        userRepository.saveAndFlush(User.create("dup@example.com", "사용자1", "010-1111-1111"));

        assertThatThrownBy(() ->
            userRepository.saveAndFlush(User.create("dup@example.com", "사용자2", "010-2222-2222"))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("email NULL 다건 허용 (D-22 정합·MariaDB NULL UK 비교 제외)")
    void insert_multipleNullEmail_success() {
        User user1 = userRepository.saveAndFlush(User.create(null, "이름1", "010-1111-1111"));
        User user2 = userRepository.saveAndFlush(User.create(null, "이름2", "010-2222-2222"));

        assertThat(user1.getId()).isNotNull();
        assertThat(user2.getId()).isNotNull();
        assertThat(user1.getId()).isNotEqualTo(user2.getId());
    }

    @Test
    @DisplayName("soft-delete 후 findById Optional.empty (@SQLRestriction LT-03 검증)")
    void findById_afterSoftDelete_returnsEmpty() {
        User saved = userRepository.saveAndFlush(User.create("soft@example.com", "삭제대상", "010-9999-9999"));
        entityManager.clear();

        entityManager.getEntityManager()
            .createNativeQuery("UPDATE `user` SET deleted_at = NOW(6) WHERE id = :id")
            .setParameter("id", saved.getId())
            .executeUpdate();
        entityManager.clear();

        Optional<User> found = userRepository.findById(saved.getId());
        assertThat(found).isEmpty();
    }
}
