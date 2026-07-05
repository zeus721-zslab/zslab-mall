package com.zslab.mall.user.repository;

import com.zslab.mall.user.entity.UserAddress;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAddressRepository extends JpaRepository<UserAddress, Long> {

    /** 본인 배송지 목록. {@code @SQLRestriction("deleted_at IS NULL")}으로 삭제분 자동 제외. (Track 58 BL-4) */
    List<UserAddress> findByUserId(Long userId);

    /** 소유권 스코프 단건(BOLA 차단·타 user 소유는 empty→404 은닉). 수정·삭제·기본설정 진입 조회. (Track 58 BL-4) */
    Optional<UserAddress> findByIdAndUserId(Long id, Long userId);

    /** 기존 기본 배송지(demote-then-set의 강등 대상). user당 단일 불변은 앱 로직이 보장. (Track 58 BL-4) */
    Optional<UserAddress> findByUserIdAndIsDefaultTrue(Long userId);

    /** 본인 배송지 개수(첫 주소 자동 기본 판정용). (Track 58 BL-4) */
    long countByUserId(Long userId);
}
