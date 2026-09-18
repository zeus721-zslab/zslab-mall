package com.zslab.mall.stats.repository;

import java.time.LocalDateTime;

/** 소요시간 계산용 시각 쌍(Track 88). 평균·중앙값은 서비스가 계산한다(네이티브·윈도우 함수 미사용). */
public interface TimePairProjection {

    LocalDateTime getStartAt();

    LocalDateTime getEndAt();
}
