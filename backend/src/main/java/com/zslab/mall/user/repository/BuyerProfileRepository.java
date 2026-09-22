package com.zslab.mall.user.repository;

import com.zslab.mall.user.entity.BuyerProfile;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BuyerProfileRepository extends JpaRepository<BuyerProfile, Long> {

    /** 관리자 회원 목록 등급 배치 enrich(Track 84·N+1 회피). */
    List<BuyerProfile> findByUserIdIn(Collection<Long> userIds);

    /**
     * 등급 재산정 배치 대상 buyer id(Track 96-6). 탈퇴 회원({@code user.withdrawn_at IS NOT NULL})은 제외하며 프로필 전량 적재 대신 id만
     * 내린다. user는 LEFT JOIN — 탈퇴 표시가 있는 회원만 빼고 나머지는 모두 대상이다. 바인딩 변수 없음(SQL injection 위험 없음).
     */
    @Query("SELECT bp.userId FROM BuyerProfile bp LEFT JOIN bp.user u WHERE u.withdrawnAt IS NULL ORDER BY bp.userId")
    List<Long> findActiveBuyerIds();
}
