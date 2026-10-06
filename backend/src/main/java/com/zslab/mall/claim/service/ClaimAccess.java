package com.zslab.mall.claim.service;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.order.service.OrderService;
import org.springframework.stereotype.Component;

/**
 * 클레임 서비스들이 공유하는 잠금·조회 헬퍼(D-265). 주문 쓰기 락(P5)과 클레임 행 락 조회 규약을 한 곳에 둔다 — 클래스마다 복제하면
 * 잠금 순서가 갈라질 수 있다. 트랜잭션을 선언하지 않으며 호출자 트랜잭션에 참여한다.
 */
@Component
class ClaimAccess {

    private final ClaimRepository claimRepository;
    private final OrderService orderService;

    ClaimAccess(ClaimRepository claimRepository, OrderService orderService) {
        this.claimRepository = claimRepository;
        this.orderService = orderService;
    }

    /**
     * 구매자 소유 클레임인지 검증한다(Track 101-B). 기준은 클레임의 {@code requested_by}가 아니라 <b>주문의 구매자</b>다 —
     * 관리자 대행 취소는 requested_by가 관리자 user id라, requested_by로 판정하면 목록(주문 구매자 기준)에는 보이는데
     * 상세·쓰기는 404가 되는 불일치가 생긴다. 소유 위반·해소 실패는 모두 404로 은닉한다(Q8).
     *
     * @throws ClaimNotFoundException 주문 구매자가 아니거나 품목·주문이 해소되지 않는 경우
     */
    void verifyBuyerOwnership(Claim claim, Long buyerId, String claimPublicId) {
        Long orderBuyerId = claimRepository.findOrderBuyerIdByClaimId(claim.getId()).orElse(null);
        if (!buyerId.equals(orderBuyerId)) {
            throw new ClaimNotFoundException("클레임을 찾을 수 없습니다: " + claimPublicId);
        }
    }

    /**
     * publicId로 클레임을 <b>행 락과 함께</b> 읽는다(Track 101-A 외부 검토 반영). 상태를 읽고 그 판정으로 전이까지 가는
     * 구매자·관리자 진입점(취소·회수 송장 등록)이 쓴다. 이 트랜잭션에서 클레임 엔티티의 첫 읽기여야 한다(앞선 주문 쓰기 락·스칼라 조회는
     * 클레임을 적재하지 않는다) — 먼저 락 없이 읽으면 1차 캐시가 옛 인스턴스를 돌려준다({@code ClaimRepository.findWithLockByPublicId} 규약).
     */
    Claim findClaimByPublicIdForUpdate(String claimPublicId) {
        return claimRepository.findWithLockByPublicId(claimPublicId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: " + claimPublicId));
    }

    /**
     * 클레임이 속한 주문의 쓰기 락을 잡는다(Track 104-1 D-215·invariants P5). 쓰기 메서드의 첫 DB 접근으로 부른다 — 주문 id는 스칼라로
     * 구하므로 클레임이 1차 캐시에 먼저 올라가지 않는다. wrapper·primitive 양쪽에서 불려도 같은 트랜잭션의 재획득이라 무해하며, primitive를
     * 직접 부르는 경로(동기 핸들러·통합 테스트)도 같은 순서를 지킨다. 클레임이 없으면 잠그지 않고 기존 404 처리에 맡긴다.
     */
    void lockOrderOfClaim(Long claimId) {
        claimRepository.findOrderIdById(claimId).ifPresent(orderService::lockForWrite);
    }

    /** {@link #lockOrderOfClaim}의 public_id 판(구매자·관리자 clm_ 진입점). */
    void lockOrderOfClaimPublicId(String claimPublicId) {
        claimRepository.findOrderIdByPublicId(claimPublicId).ifPresent(orderService::lockForWrite);
    }

    Claim findClaim(Long claimId) {
        return claimRepository.findById(claimId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: claimId=" + claimId));
    }
}
