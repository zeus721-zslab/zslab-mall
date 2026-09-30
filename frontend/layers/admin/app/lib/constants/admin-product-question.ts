import type { AdminSemantic } from '#layers/admin/app/lib/constants/semantic'
import {
  PRODUCT_QUESTION_STATUSES,
  PRODUCT_QUESTION_STATUS_LABELS,
  type ProductQuestionStatus,
} from '~/lib/constants/product-question'

/**
 * 관리자 상품 질문 상수(Track 106-2 · admin-review 복제). 상태 값·라벨의 실체는 공용 단일 소스(app/lib/constants/product-question.ts)이고,
 * 여기는 관리자 화면 전용 표시(배지 톤·필터 선택지·전이 버튼 이름)와 페이지 크기만 둔다. 사유 한도는 공용 PRODUCT_QUESTION_REASON_MAX.
 */

/** 배지 톤(admin-vuetify.css .adm-chip--*): 공개 = 긍정 · 숨김 = 부정. */
export const ADMIN_PRODUCT_QUESTION_STATUS_TONE: Record<ProductQuestionStatus, AdminSemantic> = {
  VISIBLE: 'success',
  HIDDEN: 'danger',
}

export const ADMIN_PRODUCT_QUESTION_STATUS_OPTIONS: { value: ProductQuestionStatus | null; title: string }[] = [
  { value: null, title: '전체 상태' },
  ...PRODUCT_QUESTION_STATUSES.map((value) => ({ value, title: PRODUCT_QUESTION_STATUS_LABELS[value] })),
]

/** 목표 상태 → 동작 이름(숨김 · 숨김 해제). */
export const ADMIN_PRODUCT_QUESTION_TRANSITION_LABEL: Record<ProductQuestionStatus, string> = {
  HIDDEN: '숨김',
  VISIBLE: '숨김 해제',
}

/** 페이지 크기 선택지(BE 최대 50). */
export const ADMIN_PRODUCT_QUESTION_PAGE_SIZES: number[] = [10, 20, 50]
export const DEFAULT_ADMIN_PRODUCT_QUESTION_PAGE_SIZE = 20

/** 목록 질문 발췌 길이. */
export const ADMIN_PRODUCT_QUESTION_EXCERPT_LENGTH = 60
