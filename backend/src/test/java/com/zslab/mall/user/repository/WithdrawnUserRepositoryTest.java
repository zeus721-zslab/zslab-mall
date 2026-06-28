package com.zslab.mall.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.entity.WithdrawnUser;
import jakarta.persistence.PersistenceException;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link WithdrawnUserRepository} @DataJpaTest — CRUD·FK constraint 검증.
 */
class WithdrawnUserRepositoryTest extends Batch1DataJpaTestBase {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WithdrawnUserRepository withdrawnUserRepository;

    @Test
    @DisplayName("save+findById 성공: originalUser FK 보존·createdAt 자동")
    void save_findById_success() {
        User user = userRepository.saveAndFlush(User.create("withdraw@example.com", "탈퇴자", "010-1234-5678"));
        WithdrawnUser saved = withdrawnUserRepository.saveAndFlush(
            WithdrawnUser.create(user, "서비스 불만족", LocalDateTime.now().plusDays(180)));
        entityManager.clear();

        Optional<WithdrawnUser> found = withdrawnUserRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getOriginalUser().getId()).isEqualTo(user.getId());
        assertThat(found.get().getWithdrawReason()).isEqualTo("서비스 불만족");
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("original_user_id FK 위반 nativeQuery → PersistenceException (FK RESTRICT)")
    void insert_invalidOriginalUserId_throwsPersistenceException() {
        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO withdrawn_user (original_user_id, created_at, updated_at) "
                    + "VALUES (99999, NOW(6), NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }
}
