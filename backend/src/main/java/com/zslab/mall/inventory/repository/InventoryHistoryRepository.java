package com.zslab.mall.inventory.repository;

import com.zslab.mall.inventory.entity.InventoryHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryHistoryRepository extends JpaRepository<InventoryHistory, Long> {

    /**
     * 특정 참조(referenceType·referenceId)로 기록된 이력 존재 여부를 판정한다(Track 17 PR-B·D-101 §6 갱신).
     * {@code InventoryClaimCompletedHandler}가 claimId 기반 멱등 가드로 사용하며, 형제 AFTER_COMMIT 핸들러
     * ({@code ClaimCompletedHandler})의 실행 순서와 무관하게 이미 복구/교환 이력이 있으면 재처리를 skip한다.
     * Spring Data 명명 규약 자동 파생 쿼리(referenceType·referenceId 파라미터 바인딩·SQL injection 위험 없음).
     */
    boolean existsByReferenceTypeAndReferenceId(String referenceType, Long referenceId);
}
