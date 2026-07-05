package com.zslab.mall.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.SQLRestriction;

/**
 * full audit + 소프트 삭제 3컬럼(deleted_at·deleted_by·delete_reason)을 제공하는 추상 엔티티.
 *
 * <p>적용 대상: deletion-policy.md SOFT 8 — 직접 상속 3(Category·UserAddress·ProductImage)
 * + {@link AbstractPublicIdSoftDeletableEntity} 경유 5(User·Seller·Product·ProductVariant·Attachment).
 * V1 DDL이 deleted_at 컬럼을 정확히 이 8개 테이블에만 부여한다(광의 SOFT 분류는 is_system·is_active·Root cascade로 관리).
 *
 * <p>{@code @SQLRestriction("deleted_at IS NULL")}로 조회 시 삭제분을 자동 제외한다(COM-2 가드). 실제 삭제는
 * Service에서 deleted_at 마킹으로 수행한다 — A5 결정 정합으로 {@code @SQLDelete}는 사용하지 않는다.
 *
 * <p><b>equals/hashCode 가이드 (Q8=C 정합)</b>:
 * <ul>
 *   <li>PublicIdEntity 2종 상속: publicId가 해당 추상 클래스에서 {@code @EqualsAndHashCode.Include} 처리됨·Entity 추가 작업 없음</li>
 *   <li>표준 id Entity: id 필드에 {@code @EqualsAndHashCode.Include} 명시</li>
 *   <li>특수 PK Entity(@MapsId·논리 PK·@EmbeddedId): 도메인 키 또는 PK 필드에 {@code @EqualsAndHashCode.Include} 명시</li>
 *   <li>클래스 어노테이션: {@code @EqualsAndHashCode(onlyExplicitlyIncluded = true)}·{@code @ToString(onlyExplicitlyIncluded = true)} 부착</li>
 * </ul>
 */
@MappedSuperclass
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(onlyExplicitlyIncluded = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public abstract class AbstractSoftDeletableEntity extends AbstractFullAuditableEntity {

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "deleted_by")
    private Long deletedBy;

    @Column(name = "delete_reason", length = 255)
    private String deleteReason;

    /**
     * 소프트 삭제로 마킹한다(Track 58 BL-4). {@code deleted_at}을 현재 시각으로 채워
     * {@code @SQLRestriction("deleted_at IS NULL")} 조회에서 자동 제외되게 한다. 실제 행 삭제(@SQLDelete)는
     * A5 결정에 따라 사용하지 않는다. 삭제 주체는 감사 컬럼 {@code updated_by}(@LastModifiedBy)가 자동 기록한다.
     */
    public void markDeleted() {
        this.deletedAt = LocalDateTime.now();
    }
}
