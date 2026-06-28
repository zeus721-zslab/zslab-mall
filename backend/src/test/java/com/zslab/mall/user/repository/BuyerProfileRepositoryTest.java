package com.zslab.mall.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.grade.entity.BuyerGrade;
import com.zslab.mall.grade.enums.BuyerGradeCode;
import com.zslab.mall.grade.repository.BuyerGradeRepository;
import com.zslab.mall.user.entity.BuyerProfile;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.enums.GradeSource;
import jakarta.persistence.PersistenceException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link BuyerProfileRepository} @DataJpaTest — 공유 PK @MapsId·FK constraint·GradeSource 검증.
 */
class BuyerProfileRepositoryTest extends Batch1DataJpaTestBase {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BuyerGradeRepository buyerGradeRepository;

    @Autowired
    private BuyerProfileRepository buyerProfileRepository;

    private BuyerGrade seedBuyerGrade() {
        return buyerGradeRepository.saveAndFlush(BuyerGrade.create(BuyerGradeCode.SILVER, "실버"));
    }

    @Test
    @DisplayName("save+findById(userId) 성공: @MapsId 자동 채움·userId = user.id·gradeSource 보존")
    void save_findById_success_mapsIdAutoFill() {
        User user = userRepository.saveAndFlush(User.create("buyer@example.com", "구매자", "010-1234-5678"));
        BuyerGrade grade = seedBuyerGrade();

        BuyerProfile saved = buyerProfileRepository.saveAndFlush(
            BuyerProfile.create(user, grade.getId(), GradeSource.AUTO));
        entityManager.clear();

        Optional<BuyerProfile> found = buyerProfileRepository.findById(user.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getUserId()).isEqualTo(user.getId());
        assertThat(found.get().getGradeId()).isEqualTo(grade.getId());
        assertThat(found.get().getGradeSource()).isEqualTo(GradeSource.AUTO);
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("grade_id FK 위반 nativeQuery → PersistenceException (FK RESTRICT buyer_grade)")
    void insert_invalidGradeId_throwsPersistenceException() {
        User user = userRepository.saveAndFlush(User.create("fk@example.com", "FK위반자", "010-9999-8888"));

        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO buyer_profile (user_id, grade_id, grade_source, created_at, updated_at) "
                    + "VALUES (:userId, 99999, 'AUTO', NOW(6), NOW(6))")
                .setParameter("userId", user.getId())
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("user_id(공유 PK) 중복 nativeQuery → PersistenceException (PK UNIQUE 위반)")
    void insert_duplicateUserId_throwsPersistenceException() {
        User user = userRepository.saveAndFlush(User.create("dup@example.com", "중복자", "010-7777-7777"));
        BuyerGrade grade = seedBuyerGrade();
        buyerProfileRepository.saveAndFlush(BuyerProfile.create(user, grade.getId(), GradeSource.MANUAL));

        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO buyer_profile (user_id, grade_id, grade_source, created_at, updated_at) "
                    + "VALUES (:userId, :gradeId, 'EVENT', NOW(6), NOW(6))")
                .setParameter("userId", user.getId())
                .setParameter("gradeId", grade.getId())
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }
}
