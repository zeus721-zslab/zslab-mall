package com.zslab.mall.auth.entity;

import com.zslab.mall.common.entity.AbstractMappingEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원-역할 매핑(Auth 종속·HARD·N:M).
 *
 * <p>user_id는 User Aggregate(외부) — D-01에 따라 Long 필드만 선언(@ManyToOne 금지).
 * role은 Auth Aggregate 내부 — @ManyToOne LAZY 허용.
 * 삭제는 HARD delete·회수 이력은 AuditLog 보존(AUTH-4·deletion-policy.md §4).
 */
@Entity
@Table(name = "user_role")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserRole extends AbstractMappingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false, updatable = false)
    private Role role;

    /**
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static UserRole create(Long userId, Role role) {
        if (userId == null || role == null) {
            throw new IllegalArgumentException("UserRole 필수값 누락(userId·role).");
        }
        UserRole userRole = new UserRole();
        userRole.userId = userId;
        userRole.role = role;
        return userRole;
    }
}
