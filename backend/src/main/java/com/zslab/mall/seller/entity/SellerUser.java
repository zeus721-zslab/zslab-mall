package com.zslab.mall.seller.entity;

import com.zslab.mall.common.entity.AbstractFullAuditableEntity;
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
 * 판매자 구성원(Seller 귀속·full audit·HARD).
 *
 * <p>seller는 Seller Aggregate 내부 Root — @ManyToOne LAZY 허용.
 * userId(User Aggregate)·roleId(Auth Aggregate)는 외부 — D-01에 따라 Long 필드만(@ManyToOne 금지).
 * DDL COMMENT "SOFT 상속"은 Seller Root soft-delete 종속 의미·seller_user 자체 deleted_at 없음 — WARN-1 확인.
 * roleId updatable 명시 생략(JPA default true)·역할 변경 정책은 Track 8+ Application Service에서 결정(D-83 §Q3).
 */
@Entity
@Table(name = "seller_user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerUser extends AbstractFullAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false, updatable = false)
    private Seller seller;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    /**
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static SellerUser create(Seller seller, Long userId, Long roleId) {
        if (seller == null || userId == null || roleId == null) {
            throw new IllegalArgumentException("SellerUser 필수값 누락(seller·userId·roleId).");
        }
        SellerUser sellerUser = new SellerUser();
        sellerUser.seller = seller;
        sellerUser.userId = userId;
        sellerUser.roleId = roleId;
        return sellerUser;
    }
}
