package com.zslab.mall.inbox.controller.response;

import com.zslab.mall.common.serialization.KstOffsetSerializer;
import java.time.LocalDateTime;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * 셀러 지연 패널 정보(D-252). 건수는 조회 시점에 다시 센다(인박스 행 이후 변화 반영).
 *
 * @param lastNudgedAt 마지막 독촉 발송(SENT) 시각(없으면 null · 응답에서 생략)
 */
public record SellerDelayResponse(
        String sellerPublicId,
        String companyName,
        long deliveryReadyOverdueCount,
        long questionUnansweredOverdueCount,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime lastNudgedAt) {
}
