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
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 집계 Read Model용 — updated_at 단일 컬럼 추상 엔티티(갱신 시각만 추적).
 *
 * <p>적용 대상: 집계 2 — BuyerPurchaseAggregate(이벤트 핸들러 E6 갱신)·SellerSalesDaily(배치 갱신)
 * (audit-policy.md §8·D-10). 원천(Write Model)으로부터 재계산 가능한 파생 데이터다.
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
public abstract class AbstractAggregateEntity {

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
