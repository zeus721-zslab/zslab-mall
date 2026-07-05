package com.zslab.mall.user.entity;

import com.zslab.mall.common.entity.AbstractPublicIdSoftDeletableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * 회원(USR Aggregate Root·SOFT·public_id usr_).
 *
 * <p>{@code @SQLRestriction}은 Hibernate 6.6에서 {@code @MappedSuperclass} 선언이 {@code @Entity}로
 * 전파되지 않는 버그로 인해 본 클래스에 직접 선언한다(AbstractSoftDeletableEntity 중복 선언 의도적).
 * email·name·phone은 탈퇴 비식별화 시 NULL 허용(D-22). Service 가드는 Track 8+ 이연.
 */
@Entity
@Table(name = "user")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends AbstractPublicIdSoftDeletableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "name", length = 50)
    private String name;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "password_hash", length = 60)
    private String passwordHash;

    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    @Column(name = "anonymized_at")
    private LocalDateTime anonymizedAt;

    @Override
    protected String getPublicIdPrefix() {
        return "usr";
    }

    /**
     * @throws IllegalArgumentException email·name·phone 모두 누락 시 (신규 가입 최소 조건)
     */
    public static User create(String email, String name, String phone) {
        if (email == null && name == null && phone == null) {
            throw new IllegalArgumentException("User 필수값 누락(email·name·phone 중 최소 1개).");
        }
        User user = new User();
        user.email = email;
        user.name = name;
        user.phone = phone;
        return user;
    }

    /**
     * 비밀번호 해시를 설정한다(회원가입·비밀번호 설정 시). raw password가 아닌 인코딩된 해시를 저장하는 계약이다.
     *
     * @throws IllegalArgumentException passwordHash가 null·blank인 경우
     */
    public void assignPasswordHash(String passwordHash) {
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("passwordHash는 null·blank일 수 없습니다.");
        }
        this.passwordHash = passwordHash;
    }

    /**
     * 회원 프로필(name·phone)을 교체한다(Track 58 BL-3). email은 로그인 자격증명이라 본 경로에서 변경하지 않는다.
     *
     * @throws IllegalArgumentException name·phone 중 null·blank가 있는 경우
     */
    public void updateProfile(String name, String phone) {
        if (name == null || name.isBlank() || phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("name·phone은 null·blank일 수 없습니다.");
        }
        this.name = name;
        this.phone = phone;
    }
}
