import type { ReviewKeywordGroup } from '~/lib/constants/review'
import type { ReviewFormPhoto, ReviewKeywordOption, ReviewPhotoUploadResponse } from '~/types/review'

/** 작성 폼 키워드 묶음(표시 순서 = REVIEW_KEYWORD_GROUPS · 묶음 안은 sortOrder). 선택지가 없는 묶음은 넣지 않는다. */
export interface ReviewKeywordSection {
  group: ReviewKeywordGroup
  label: string
  options: ReviewKeywordOption[]
}

/**
 * pages/reviews/new.vue · pages/reviews/[reviewPublicId]/edit.vue → ReviewFormView(Track 106-1 PR2). 두 페이지가 같은 vm을 쓴다(mode로 구분).
 * isValidQuery가 false면 잘못된 접근 · submitted면 완료 화면 · locked(숨김 리뷰)면 수정 불가 안내만 보인다.
 * 별점·키워드·사진·본문은 뷰가 v-model(rating·photos·content)과 toggleKeyword로 바꾼다.
 */
export interface ReviewFormPageVm {
  mode: 'create' | 'edit'
  isValidQuery: boolean
  /** 수정: 작성자 리뷰 조회 중. */
  loading: boolean
  /** 수정: 작성자 리뷰를 불러오지 못한 이유(빈 문자열 = 없음). */
  loadError: string
  /** 수정: 숨김 리뷰라 수정할 수 없다(D-237 보고 결정 2). */
  locked: boolean
  hiddenReason: string
  productName: string
  optionLabel: string
  productPath: string
  rating: number | null
  ratingLabel: string
  keywordSections: ReviewKeywordSection[]
  keywordsPending: boolean
  keywordsFailed: boolean
  retryKeywords: () => Promise<void>
  selectedKeywords: string[]
  toggleKeyword: (code: string) => void
  keywordLimitReached: boolean
  photos: ReviewFormPhoto[]
  photosBusy: boolean
  /** 올리지 못한 사진 칸 수(0이 아니면 등록 잠금). */
  photosFailed: number
  uploadPhoto: (file: File) => Promise<ReviewPhotoUploadResponse>
  content: string
  submitting: boolean
  submitted: boolean
  canSubmit: boolean
  errorMessage: string
  handleSubmit: () => Promise<void>
  CONTENT_MAX: number
  KEYWORD_MAX: number
  PHOTO_MAX: number
}
