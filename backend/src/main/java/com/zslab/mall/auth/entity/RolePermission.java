package com.zslab.mall.auth.entity;

import com.zslab.mall.common.entity.AbstractMappingEntity;
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
 * 역할-권한 매핑(Auth 종속·HARD·N:M).
 *
 * <p>role·permission 모두 Auth Aggregate 내부 — @ManyToOne LAZY 양쪽 허용.
 * 삭제는 HARD delete·회수 이력은 AuditLog 보존(AUTH-4·deletion-policy.md §4).
 */
@Entity
@Table(name = "role_permission")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RolePermission extends AbstractMappingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false, updatable = false)
    private Role role;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "permission_id", nullable = false, updatable = false)
    private Permission permission;

    /**
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static RolePermission create(Role role, Permission permission) {
        if (role == null || permission == null) {
            throw new IllegalArgumentException("RolePermission 필수값 누락(role·permission).");
        }
        RolePermission rp = new RolePermission();
        rp.role = role;
        rp.permission = permission;
        return rp;
    }
}
