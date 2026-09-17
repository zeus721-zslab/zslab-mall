package com.zslab.mall.user.repository;

import com.zslab.mall.user.entity.BuyerProfile;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BuyerProfileRepository extends JpaRepository<BuyerProfile, Long> {

    /** 관리자 회원 목록 등급 배치 enrich(Track 84·N+1 회피). */
    List<BuyerProfile> findByUserIdIn(Collection<Long> userIds);
}
