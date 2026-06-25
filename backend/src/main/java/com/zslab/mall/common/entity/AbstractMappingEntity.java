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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 매핑(N:M) 테이블용 — created_at 단일 컬럼 추상 엔티티(생성 시각만 추적).
 *
 * <p>적용 대상: 매핑 2 — UserRole·RolePermission(audit-policy.md §8·권한 매핑 추적). 권한 회수는 HARD delete이며
 * 회수 이력은 AuditLog로 보존한다(AUTH-4·deletion-policy.md §4).
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
public abstract class AbstractMappingEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
