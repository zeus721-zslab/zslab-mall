package com.zslab.mall.user.entity;

import com.zslab.mall.common.entity.AbstractSoftDeletableEntity;
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
import org.hibernate.annotations.SQLRestriction;

/**
 * 배송지(USR 종속·SOFT).
 *
 * <p>{@code @SQLRestriction}은 Hibernate 6.6에서 {@code @MappedSuperclass} 선언이 {@code @Entity}로
 * 전파되지 않는 버그로 인해 본 클래스에 직접 선언한다(AbstractSoftDeletableEntity 중복 선언 의도적).
 * user는 User Aggregate 내부 참조 — D-01에 따라 @ManyToOne LAZY 허용.
 */
@Entity
@Table(name = "user_address")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAddress extends AbstractSoftDeletableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "address_label", length = 50)
    private String addressLabel;

    @Column(name = "recipient_name", nullable = false, length = 50)
    private String recipientName;

    @Column(name = "recipient_phone", nullable = false, length = 20)
    private String recipientPhone;

    @Column(name = "zonecode", nullable = false, length = 10)
    private String zonecode;

    @Column(name = "address_road", nullable = false, length = 200)
    private String addressRoad;

    @Column(name = "address_jibun", length = 200)
    private String addressJibun;

    @Column(name = "address_detail", length = 200)
    private String addressDetail;

    /**
     * @throws IllegalArgumentException 필수값 누락 시(user·recipientName·recipientPhone·zonecode·addressRoad)
     */
    public static UserAddress create(
            User user,
            boolean isDefault,
            String addressLabel,
            String recipientName,
            String recipientPhone,
            String zonecode,
            String addressRoad,
            String addressJibun,
            String addressDetail) {
        if (user == null || recipientName == null || recipientPhone == null
                || zonecode == null || addressRoad == null) {
            throw new IllegalArgumentException(
                    "UserAddress 필수값 누락(user·recipientName·recipientPhone·zonecode·addressRoad).");
        }
        UserAddress address = new UserAddress();
        address.user = user;
        address.isDefault = isDefault;
        address.addressLabel = addressLabel;
        address.recipientName = recipientName;
        address.recipientPhone = recipientPhone;
        address.zonecode = zonecode;
        address.addressRoad = addressRoad;
        address.addressJibun = addressJibun;
        address.addressDetail = addressDetail;
        return address;
    }

    /**
     * 배송지 상세를 교체한다(Track 58 BL-4). user·isDefault는 불변(기본배송지 전환은 markDefault/unmarkDefault 별도 책임).
     *
     * @throws IllegalArgumentException 필수값 누락 시(recipientName·recipientPhone·zonecode·addressRoad)
     */
    public void updateDetails(
            String addressLabel,
            String recipientName,
            String recipientPhone,
            String zonecode,
            String addressRoad,
            String addressJibun,
            String addressDetail) {
        if (recipientName == null || recipientPhone == null || zonecode == null || addressRoad == null) {
            throw new IllegalArgumentException(
                    "UserAddress 필수값 누락(recipientName·recipientPhone·zonecode·addressRoad).");
        }
        this.addressLabel = addressLabel;
        this.recipientName = recipientName;
        this.recipientPhone = recipientPhone;
        this.zonecode = zonecode;
        this.addressRoad = addressRoad;
        this.addressJibun = addressJibun;
        this.addressDetail = addressDetail;
    }

    /** 기본 배송지로 지정한다(Track 58 BL-4·setDefault의 승격 단계·기존 기본 강등 후 호출). */
    public void markDefault() {
        this.isDefault = true;
    }

    /** 기본 배송지 지정을 해제한다(Track 58 BL-4·demote-then-set의 강등 단계). */
    public void unmarkDefault() {
        this.isDefault = false;
    }
}
