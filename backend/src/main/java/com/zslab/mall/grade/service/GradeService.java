package com.zslab.mall.grade.service;

import com.zslab.mall.grade.entity.GradePolicy;
import com.zslab.mall.grade.exception.GradePolicyUnavailableException;
import com.zslab.mall.grade.repository.GradePolicyRepository;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.user.entity.BuyerProfile;
import com.zslab.mall.user.enums.GradeSource;
import com.zslab.mall.user.repository.BuyerProfileRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구매자 등급 자동 산정 Application Service(Track 51 Phase 2). 단일 buyer의 생애 누적 구매액을 기준으로 활성 정책 구간을
 * 선택해 등급을 재산정한다. 운영자 배치가 buyer 목록을 순회하는 구조는 본 Service 밖(Phase 3)이며, 여기서는 buyer 1건 산정만 책임진다.
 *
 * <p><b>산정 절차</b>: lifetime 집계 → BuyerProfile 조회 → lock 가드 → 활성 정책 구간 선택 → 결과 반영.
 *
 * <p><b>lock 가드(D-136·R3-a)</b>: {@code grade_locked_until}이 현재 시각 이후면 AUTO 산정을 skip한다(등급 고정 보호).
 * lock이 실제 제어자이며 {@code grade_source}는 출처 메타다 — lock이 없으면 기존 source(MANUAL·EVENT)와 무관하게
 * 재산정 후 source=AUTO로 세팅한다(MANUAL 부여 시 운영자가 lock_until도 함께 걸어야 보호된다·D-136).
 *
 * <p><b>구간 선택(GRD-1·R2-b)</b>: 활성 정책 중 {@code min_amount <= lifetime < max_amount}(반개구간) 1건을 선택한다.
 * {@link GradePolicyRepository#findActivePolicies}가 version DESC로 정렬하므로 동일 구간 중복 시 최상위 version이 우선된다.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class GradeService {

    private final OrderItemRepository orderItemRepository;
    private final GradePolicyRepository gradePolicyRepository;
    private final BuyerProfileRepository buyerProfileRepository;

    /**
     * 단일 buyer의 등급을 자동 재산정한다(AUTO). lock 기간 중이면 아무 것도 변경하지 않고 반환한다.
     *
     * @param buyerId 산정 대상 buyer(User.id·BuyerProfile 공유 PK)
     * @throws GradePolicyUnavailableException 활성 정책 구간에 매칭되는 정책이 없는 경우(서버 설정 오류·500)
     * @throws IllegalStateException           BuyerProfile이 존재하지 않는 경우(모든 buyer는 가입 시 프로필 보유·불변식 위반 방어)
     */
    public void recalculate(Long buyerId) {
        LocalDateTime now = LocalDateTime.now();

        long lifetimeAmount = orderItemRepository.sumConfirmedTotalPriceByBuyerId(buyerId, OrderItemStatus.CONFIRMED);

        BuyerProfile buyerProfile = buyerProfileRepository.findById(buyerId)
                .orElseThrow(() -> new IllegalStateException(
                        "BuyerProfile이 존재하지 않습니다(buyer 불변식 위반): buyerId=" + buyerId));

        LocalDateTime lockedUntil = buyerProfile.getGradeLockedUntil();
        if (lockedUntil != null && lockedUntil.isAfter(now)) {
            log.info("[Grade] 등급 고정 기간·AUTO 산정 skip: buyerId={} lockedUntil={} lifetime={}",
                    buyerId, lockedUntil, lifetimeAmount);
            return;
        }

        GradePolicy selectedPolicy = selectPolicyByAmount(gradePolicyRepository.findActivePolicies(now), lifetimeAmount, buyerId);

        // 멱등: 동일 등급 재산정 시에도 grade_updated_at을 갱신한다(no-op 최적화는 과잉·단순 유지).
        buyerProfile.applyGrade(selectedPolicy.getGrade().getId(), GradeSource.AUTO, now);
        log.info("[Grade] AUTO 산정 반영: buyerId={} lifetime={} gradeId={}",
                buyerId, lifetimeAmount, selectedPolicy.getGrade().getId());
    }

    /**
     * lifetime이 속하는 반개구간 {@code [min_amount, max_amount)} 정책을 선택한다. version DESC 정렬이 유지되므로
     * 중복 구간에서는 최상위 version이 우선된다(GRD-1).
     */
    private GradePolicy selectPolicyByAmount(List<GradePolicy> activePolicies, long lifetimeAmount, Long buyerId) {
        return activePolicies.stream()
                .filter(policy -> lifetimeAmount >= policy.getMinAmount() && lifetimeAmount < policy.getMaxAmount())
                .findFirst()
                .orElseThrow(() -> new GradePolicyUnavailableException(
                        "활성 등급 정책 구간에 매칭되는 정책이 없습니다: buyerId=" + buyerId + " lifetime=" + lifetimeAmount));
    }
}
