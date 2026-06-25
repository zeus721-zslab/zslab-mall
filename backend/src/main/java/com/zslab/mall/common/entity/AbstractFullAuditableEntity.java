package com.zslab.mall.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 생성·수정 감사(full audit) 4컬럼(created_at·created_by·updated_at·updated_by)을 제공하는 최상위 추상 엔티티.
 *
 * <p>적용 대상: audit 5분류 중 full(비-soft) 21개 — 직접 상속 15
 * + {@link AbstractPublicIdFullAuditableEntity} 경유 6. 대표 예시: Settlement·Inventory·SellerBankAccount
 * (audit-policy.md §8·RECON.md §6 참조).
 *
 * <p>created_by·updated_by는 BIGINT NULL·FK 없음(시스템 작업 허용·db-schema §1.7). 값 주입은
 * {@link org.springframework.data.jpa.domain.support.AuditingEntityListener}가
 * {@link com.zslab.mall.common.config.AuditorAwareImpl} 기준으로 채운다. UNIQUE 등 제약은 DDL(V1) 책임이다.
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
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(onlyExplicitlyIncluded = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public abstract class AbstractFullAuditableEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @LastModifiedBy
    @Column(name = "updated_by")
    private Long updatedBy;
}
