package com.zslab.mall.seller.service;

import com.zslab.mall.seller.enums.SellerStatus;
import java.util.Arrays;
import java.util.List;

/**
 * 셀러 상태별 접근 정책 단일점(Track 90-A·D-187 §8 이월). 로그인(DbRoleAuthorization)과 셀러 API 액터 해소
 * (SellerActorResolver)가 같은 판정을 쓰도록 여기 한 곳에 둔다. 상태 전이 규칙({@link SellerStatus#canTransitionTo})은 건드리지 않는다.
 *
 * <p>판정은 default 없는 switch expression으로 두어 {@link SellerStatus}에 값이 추가되면 컴파일 에러로 정책 누락이 드러나게 한다
 * ({@code canTransitionTo}·{@code DbRoleAuthorization} 동형·상수 집합 금지 — 외부 검토 R1).
 * <ul>
 *   <li>세션(로그인·API 인증) 허용 = ACTIVE·SUSPENDED. PENDING(심사 중)·TERMINATED(종결)는 인증 자체 무효.</li>
 *   <li>쓰기(POST·PATCH·PUT·DELETE) 허용 = ACTIVE만. SUSPENDED는 조회만 허용해 정지 사유·정산 확인 경로를 남긴다.</li>
 * </ul>
 */
public final class SellerAccessPolicy {

    private SellerAccessPolicy() {}

    /** 로그인·API 인증이 가능한 상태인지(ACTIVE·SUSPENDED). */
    public static boolean isSessionAllowed(SellerStatus status) {
        return switch (status) {
            case ACTIVE, SUSPENDED -> true;
            case PENDING, TERMINATED -> false;
        };
    }

    /** 쓰기 요청이 가능한 상태인지(ACTIVE만). */
    public static boolean isWriteAllowed(SellerStatus status) {
        return switch (status) {
            case ACTIVE -> true;
            case PENDING, SUSPENDED, TERMINATED -> false;
        };
    }

    /**
     * 세션 허용 상태 목록(로그인 판정 쿼리의 {@code status IN (...)} 인자용). 별도 상수 집합을 두지 않고
     * {@link #isSessionAllowed}에서 파생시켜 판정 SoT를 switch 하나로 유지한다.
     */
    public static List<SellerStatus> sessionAllowedStatuses() {
        return Arrays.stream(SellerStatus.values()).filter(SellerAccessPolicy::isSessionAllowed).toList();
    }
}
