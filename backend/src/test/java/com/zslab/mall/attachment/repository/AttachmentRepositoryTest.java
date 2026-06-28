package com.zslab.mall.attachment.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.attachment.entity.Attachment;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import jakarta.persistence.PersistenceException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link AttachmentRepository} @DataJpaTest — CRUD·LT-01(public_id CHAR(30))·LT-03 soft-delete 검증.
 */
class AttachmentRepositoryTest extends Batch1DataJpaTestBase {

    @Autowired
    private AttachmentRepository attachmentRepository;

    private Attachment createAttachment(PolymorphicTargetType targetType, Long targetId, String fileName) {
        return Attachment.create(targetType, targetId, fileName,
            "/upload/files/" + fileName, "image/jpeg", 1024L, 1);
    }

    @Test
    @DisplayName("save+findByPublicId 성공: targetType·targetId·fileName·publicId att_ 확인")
    void save_findByPublicId_success() {
        Attachment saved = attachmentRepository.saveAndFlush(
            createAttachment(PolymorphicTargetType.PRODUCT, 1L, "product-img.jpg"));
        entityManager.clear();

        Optional<Attachment> found = attachmentRepository.findByPublicId(saved.getPublicId());

        assertThat(found).isPresent();
        assertThat(found.get().getTargetType()).isEqualTo(PolymorphicTargetType.PRODUCT);
        assertThat(found.get().getTargetId()).isEqualTo(1L);
        assertThat(found.get().getFileName()).isEqualTo("product-img.jpg");
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("LT-01 검증: public_id 길이 att_ prefix 포함 30자 확인 (CHAR(30) 정합)")
    void publicId_length_isThirty() {
        Attachment saved = attachmentRepository.saveAndFlush(
            createAttachment(PolymorphicTargetType.PRODUCT, 2L, "lt01-check.jpg"));

        assertThat(saved.getPublicId()).hasSize(30);
        assertThat(saved.getPublicId()).startsWith("att_");
    }

    @Test
    @DisplayName("findByTargetTypeAndTargetId: polymorphic 조회 목록 반환")
    void findByTargetTypeAndTargetId_success() {
        attachmentRepository.saveAndFlush(
            createAttachment(PolymorphicTargetType.ORDER, 100L, "order-doc1.pdf"));
        attachmentRepository.saveAndFlush(
            createAttachment(PolymorphicTargetType.ORDER, 100L, "order-doc2.pdf"));
        attachmentRepository.saveAndFlush(
            createAttachment(PolymorphicTargetType.PRODUCT, 100L, "product-img.jpg"));
        entityManager.clear();

        List<Attachment> results = attachmentRepository
            .findByTargetTypeAndTargetId(PolymorphicTargetType.ORDER, 100L);

        assertThat(results).hasSize(2);
    }

    @Test
    @DisplayName("LT-03 검증: soft-delete 후 findByPublicId Optional.empty (deleted_at IS NULL 필터)")
    void softDelete_findByPublicId_returnsEmpty() {
        Attachment saved = attachmentRepository.saveAndFlush(
            createAttachment(PolymorphicTargetType.SELLER, 10L, "seller-doc.pdf"));
        String publicId = saved.getPublicId();
        entityManager.clear();

        entityManager.getEntityManager().createNativeQuery(
            "UPDATE attachment SET deleted_at = NOW(6), deleted_by = 1 WHERE id = " + saved.getId())
            .executeUpdate();
        entityManager.clear();

        Optional<Attachment> found = attachmentRepository.findByPublicId(publicId);
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("public_id UK 중복 삽입 → PersistenceException")
    void insert_duplicatePublicId_throwsPersistenceException() {
        Attachment saved = attachmentRepository.saveAndFlush(
            createAttachment(PolymorphicTargetType.PRODUCT, 3L, "dup-check.jpg"));
        entityManager.clear();

        assertThatThrownBy(() ->
            entityManager.getEntityManager().createNativeQuery(
                "INSERT INTO attachment (public_id, target_type, target_id, file_name, file_path, display_order, created_at, updated_at) "
                + "VALUES ('" + saved.getPublicId() + "', 'PRODUCT', 3, 'dup.jpg', '/upload/dup.jpg', 1, NOW(6), NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }
}
