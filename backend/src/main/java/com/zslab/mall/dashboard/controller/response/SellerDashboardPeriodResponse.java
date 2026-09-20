package com.zslab.mall.dashboard.controller.response;

import java.time.LocalDate;

/** 적용된 집계 기간(KST 일·양끝 포함). 기본값이 서버에 있으므로 FE가 실제 기간을 알 수 있게 에코한다. */
public record SellerDashboardPeriodResponse(
        LocalDate from,
        LocalDate to) {
}
