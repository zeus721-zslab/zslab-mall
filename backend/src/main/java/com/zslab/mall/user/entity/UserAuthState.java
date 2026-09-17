package com.zslab.mall.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * 인증 필터용 회원 상태 읽기 모델(Track 84). {@link User}는 {@code @SQLRestriction("deleted_at IS NULL")}이라 soft-delete 회원을
 * 조회할 수 없는데, 토큰 거부 판정은 삭제 회원도 봐야 하므로 같은 {@code user} 테이블을 제약 없이 읽는 불변 엔티티를 둔다.
 * 쓰기·연관 없음(요청당 PK 1회 조회). 시각 컬럼은 {@link User}와 동일 매핑이라 Hibernate 시간대 변환도 동일하다.
 */
@Entity
@Immutable
@Table(name = "user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAuthState {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "credentials_changed_at")
    private LocalDateTime credentialsChangedAt;
}
