import type { AdminSemantic } from '#layers/admin/app/lib/constants/semantic'
import { REVIEW_STATUSES, REVIEW_STATUS_LABELS, type ReviewStatus } from '~/lib/constants/review'

/**
 * 관리자 리뷰 관리 상수(Track 106-1 PR2). 상태 값·라벨의 실체는 공용 단일 소스(app/lib/constants/review.ts)이고, 여기는 관리자 화면 전용
 * 표시(배지 톤·필터 선택지·전이 버튼 이름)와 입력 한도만 둔다. 한도는 BE AdminReviewStatusChangeRequest·AdminReviewQueryService와 같다.
 */

/** 배지 톤(admin-vuetify.css .adm-chip--*): 공개 = 긍정 · 숨김 = 부정. */
export const ADMIN_REVIEW_STATUS_TONE: Record<ReviewStatus, AdminSemantic> = {
  VISIBLE: 'success',
  HIDDEN: 'danger',
}

export const ADMIN_REVIEW_STATUS_OPTIONS: { value: ReviewStatus | null; title: string }[] = [
  { value: null, title: '전체 상태' },
  ...REVIEW_STATUSES.map((value) => ({ value, title: REVIEW_STATUS_LABELS[value] })),
]

/** 목표 상태 → 동작 이름(숨김 · 숨김 해제). */
export const ADMIN_REVIEW_TRANSITION_LABEL: Record<ReviewStatus, string> = {
  HIDDEN: '숨김',
  VISIBLE: '숨김 해제',
}

/** 페이지 크기 선택지(BE 최대 50). */
export const ADMIN_REVIEW_PAGE_SIZES: number[] = [10, 20, 50]
export const DEFAULT_ADMIN_REVIEW_PAGE_SIZE = 20

/** 사유 최대 길이(BE @Size(max = 200)). */
export const ADMIN_REVIEW_REASON_MAX = 200

/** 목록 본문 발췌 길이. */
export const ADMIN_REVIEW_EXCERPT_LENGTH = 60
