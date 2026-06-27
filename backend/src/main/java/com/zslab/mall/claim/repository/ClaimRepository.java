package com.zslab.mall.claim.repository;

import com.zslab.mall.claim.entity.Claim;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 클레임 Repository(JpaRepository 단일·메서드 이름 쿼리만).
 */
public interface ClaimRepository extends JpaRepository<Claim, Long> {

    Optional<Claim> findByPublicId(String publicId);
}
