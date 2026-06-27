package com.zslab.mall.auth.entity;

import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.common.entity.AbstractSeedEntity;
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

/**
 * 역할(Auth Aggregate Root·HARD·시드성).
 *
 * <p>equals/hashCode는 id 기반(AbstractSeedEntity·public_id 없음·Q8=C).
 */
@Entity
@Table(name = "role")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Role extends AbstractSeedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "code", nullable = false)
    private RoleCode code;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /**
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static Role create(RoleCode code, String name) {
        if (code == null || name == null || name.isBlank()) {
            throw new IllegalArgumentException("Role 필수값 누락(code·name).");
        }
        Role role = new Role();
        role.code = code;
        role.name = name;
        return role;
    }
}
