package com.zslab.mall.attachment.entity;

import com.zslab.mall.common.entity.AbstractPublicIdSoftDeletableEntity;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * 첨부파일(ATT Aggregate Root·SOFT·public_id {@code att_}).
 *
 * <p>{@link AbstractPublicIdSoftDeletableEntity} 상속(full audit + soft-delete 3컬럼 + public_id att_·LT-01 처치).
 * polymorphic 참조(target_type/target_id) — FK 없음·D-01 ID only. DTO @ValidEnum은 Track 8+ 이연(D-86 Q4).
 *
 * <p>{@code @SQLRestriction}은 Hibernate 6.6 HHH-17453 버그로 {@code @MappedSuperclass}에서
 * {@code @Entity}로 전파되지 않아 본 클래스에 직접 선언한다(LT-03·D-82·D-86).
 */
@Entity
@Table(name = "attachment")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Attachment extends AbstractPublicIdSoftDeletableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 50)
    private PolymorphicTargetType targetType;

    /** 대상 id. NULL이면 업로드만 되고 아직 대상에 연결되지 않은 첨부(Track 81-B D-171·V25). */
    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "file_name", nullable = false, length = 200)
    private String fileName;

    @Column(name = "file_path", nullable = false, length = 2048)
    private String filePath;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    /** 업로더 user id(D-171·소유권 검증 기준). created_by는 AuditorAware 미구현으로 NULL이라 별도 기록한다. */
    @Column(name = "uploaded_by")
    private Long uploadedBy;

    /**
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static Attachment create(
            PolymorphicTargetType targetType,
            Long targetId,
            String fileName,
            String filePath,
            String mimeType,
            Long fileSize,
            int displayOrder) {
        if (targetType == null || targetId == null || fileName == null || fileName.isBlank()
                || filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException(
                    "Attachment 필수값 누락(targetType·targetId·fileName·filePath).");
        }
        Attachment attachment = new Attachment();
        attachment.targetType = targetType;
        attachment.targetId = targetId;
        attachment.fileName = fileName;
        attachment.filePath = filePath;
        attachment.mimeType = mimeType;
        attachment.fileSize = fileSize;
        attachment.displayOrder = displayOrder;
        return attachment;
    }

    /**
     * 대상 미연결 첨부를 생성한다(Track 81-B D-171·구매자 업로드 시점). {@link #linkTo}로 대상에 연결하기 전까지 target_id는 NULL이다.
     *
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static Attachment createUnlinked(
            PolymorphicTargetType targetType,
            Long uploadedBy,
            String fileName,
            String filePath,
            String mimeType,
            Long fileSize) {
        if (targetType == null || uploadedBy == null || fileName == null || fileName.isBlank()
                || filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException(
                    "Attachment 필수값 누락(targetType·uploadedBy·fileName·filePath).");
        }
        Attachment attachment = new Attachment();
        attachment.targetType = targetType;
        attachment.uploadedBy = uploadedBy;
        attachment.fileName = fileName;
        attachment.filePath = filePath;
        attachment.mimeType = mimeType;
        attachment.fileSize = fileSize;
        attachment.displayOrder = 0;
        return attachment;
    }

    /** 대상에 연결됐는지(target_id 보유). */
    public boolean isLinked() {
        return targetId != null;
    }

    @Override
    protected String getPublicIdPrefix() {
        return "att";
    }
}
