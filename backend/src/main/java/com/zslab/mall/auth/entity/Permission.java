package com.zslab.mall.auth.entity;

import com.zslab.mall.common.entity.AbstractSeedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 권한(Auth 종속·HARD·시드성).
 *
 * <p>code는 VARCHAR(50) — role.code와 달리 DB ENUM 아님. equals/hashCode는 id 기반.
 */
@Entity
@Table(name = "permission")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Permission extends AbstractSeedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /**
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static Permission create(String code, String name) {
        if (code == null || code.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("Permission 필수값 누락(code·name).");
        }
        Permission permission = new Permission();
        permission.code = code;
        permission.name = name;
        return permission;
    }
}
