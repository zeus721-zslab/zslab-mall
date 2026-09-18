import type { AdminBuyerGradeCode } from '#layers/admin/app/lib/constants/admin-member'

/**
 * 관리자 회원 통계 API 타입(FE-35·D-182 `GET /api/v1/admin/stats/members` 응답 1:1·backend stats/controller/response 실측).
 * BE는 전역 NON_NULL 직렬화라 compareSummary·compareSignupTrend·topBuyers의 userPublicId·name·email(soft-delete·비식별화)은 값이 null이면
 * <b>키 자체가 생략</b>된다 → optional로 선언하고 lib/admin-member-stats-view.ts가 `?? null`로 정규화한다. 비율은 % 소수 2자리, 금액은 원 단위 정수.
 */

export interface AdminMemberSummary {
  newCount: number
  withdrawnCount: number
  /** 기간 종료 시점 활성 누적(가입 누계 − 탈퇴 누계). */
  activeTotal: number
  repurchaseRate: number
  buyerCount: number
  repeatBuyerCount: number
}

export interface AdminSignupTrendBucket {
  bucketKey: string
  bucketLabel: string
  newCount: number
  /** 구간 종료 시점 활성 누적. */
  activeCumulative: number
}

/** 3등급 전부(0건 포함·enum 순서). 등급은 회원의 현재 등급 경유(주문 시점 스냅샷 없음·D-182). */
export interface AdminGradeDistribution {
  gradeCode: AdminBuyerGradeCode
  memberCount: number
  share: number
  revenue: number
  revenueShare: number
}

export interface AdminBuyerSplit {
  firstTimeBuyerCount: number
  firstTimeRevenue: number
  repeatBuyerCount: number
  repeatRevenue: number
}

export interface AdminTopBuyer {
  userPublicId?: string | null
  name?: string | null
  email?: string | null
  orderCount: number
  revenue: number
}

export interface AdminMemberStatsResponse {
  summary: AdminMemberSummary
  compareSummary?: AdminMemberSummary | null
  signupTrend: AdminSignupTrendBucket[]
  compareSignupTrend?: AdminSignupTrendBucket[] | null
  gradeDistribution: AdminGradeDistribution[]
  buyerSplit: AdminBuyerSplit
  /** 매출 내림차순 최대 20. */
  topBuyers: AdminTopBuyer[]
}
