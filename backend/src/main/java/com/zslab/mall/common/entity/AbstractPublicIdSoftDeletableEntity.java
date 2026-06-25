package com.zslab.mall.common.entity;

import com.zslab.mall.common.util.PublicIdGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * full audit + 소프트 삭제 + 외부 노출 public_id(CHAR(30)·ULID+prefix)를 제공하는 추상 엔티티.
 *
 * <p>적용 대상: 5 — User·Seller·Product·ProductVariant·Attachment(RECON.md PR-04.5 §1·V1 DDL).
 * 소프트 삭제 3컬럼·{@code @SQLRestriction}은 {@link AbstractSoftDeletableEntity}에서 상속한다.
 *
 * <p>public_id는 {@code @PrePersist}에서 {@link PublicIdGenerator}로 생성한다. prefix는 하위 구체 엔티티가
 * {@link #getPublicIdPrefix()}로 제공한다(예: "usr"). UNIQUE 제약은 DDL(V1) 책임이며 본 클래스에서 unique=true를 선언하지 않는다.
 *
 * <p><b>equals/hashCode 가이드 (Q8=C 정합)</b>: publicId가 본 클래스에서 {@code @EqualsAndHashCode.Include}로 처리되므로
 * 하위 Entity는 추가 작업이 필요 없다.
 */
@MappedSuperclass
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(onlyExplicitlyIncluded = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public abstract class AbstractPublicIdSoftDeletableEntity extends AbstractSoftDeletableEntity {

    @EqualsAndHashCode.Include
    @ToString.Include
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "public_id", length = 30, nullable = false, updatable = false)
    private String publicId;

    /**
     * public_id 생성에 사용할 3자 prefix를 반환한다(예: "usr"·"prd"). 구체 엔티티가 구현한다.
     */
    protected abstract String getPublicIdPrefix();

    @PrePersist
    private void generatePublicId() {
        if (publicId == null) {
            publicId = PublicIdGenerator.generate(getPublicIdPrefix());
        }
    }
}
