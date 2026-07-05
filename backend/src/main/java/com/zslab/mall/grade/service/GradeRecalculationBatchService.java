package com.zslab.mall.grade.service;

import com.zslab.mall.user.entity.BuyerProfile;
import com.zslab.mall.user.repository.BuyerProfileRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 전체 buyer 등급 재산정 배치 Application Service(Track 51 Phase 3·운영자 주도). 전체 buyer를 순회하며 buyer별로 등급을 재산정한다.
 *
 * <p><b>트랜잭션 경계</b>: 본 서비스는 {@code @Transactional}을 두지 않아 순회 메서드는 논트랜잭션이다. {@link GradeService}는
 * 클래스 {@code @Transactional}이므로, 서로 다른 빈인 본 배치가 {@link GradeService#recalculate}를 호출할 때마다 Spring 프록시가
 * REQUIRED 전파로 <b>buyer별 독립 트랜잭션</b>을 연다(자기호출 프록시 함정 회피). 1건 실패(정책 미매칭·불변식 위반)는 해당
 * 트랜잭션만 롤백하고 나머지를 막지 않는다 — {@link RuntimeException}을 흡수·실패 카운트·log.warn 후 다음 buyer로 진행한다
 * (부분 성공·{@code SettlementCreationService} skip 관례 정합).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GradeRecalculationBatchService {

    private final BuyerProfileRepository buyerProfileRepository;
    private final GradeService gradeService;

    /**
     * 전체 buyer의 등급을 buyer별 독립 트랜잭션으로 재산정한다. lock 기간 buyer는 recalculate 내부에서 skip되며 success로 집계된다.
     *
     * @return 총 대상·성공·실패 카운트 요약
     */
    public GradeRecalculationResult recalculateAll() {
        List<Long> buyerIds = buyerProfileRepository.findAll().stream()
                .map(BuyerProfile::getUserId)
                .toList();

        int success = 0;
        int failure = 0;
        for (Long buyerId : buyerIds) {
            try {
                gradeService.recalculate(buyerId);
                success++;
            } catch (RuntimeException exception) {
                // 부분 성공: 1건 실패가 배치를 중단시키지 않도록 흡수·집계·기록 후 다음 buyer로 진행(묵살 아님).
                failure++;
                log.warn("[GradeBatch] buyer 등급 산정 실패·건너뜀: buyerId={} cause={}", buyerId, exception.toString());
            }
        }
        log.info("[GradeBatch] 등급 재산정 배치 완료: total={} success={} failure={}", buyerIds.size(), success, failure);
        return new GradeRecalculationResult(buyerIds.size(), success, failure);
    }
}
