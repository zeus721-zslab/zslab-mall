import type { AdminReviewListItem } from '#layers/admin/app/types/admin-review'
import { ADMIN_REVIEW_EXCERPT_LENGTH, ADMIN_REVIEW_STATUS_TONE } from '#layers/admin/app/lib/constants/admin-review'
import { semanticChipClass } from '#layers/admin/app/lib/constants/semantic'
import { reviewStatusLabel, type ReviewStatus } from '~/lib/constants/review'

/** 관리자 리뷰 목록 표시 함수(Track 106-1 · 순수 함수). */

export function reviewStatusChipClass(status: ReviewStatus): string {
  return semanticChipClass(ADMIN_REVIEW_STATUS_TONE[status])
}

export { reviewStatusLabel }

/** 본문 발췌(줄바꿈은 공백으로 · 길면 말줄임). */
export function reviewExcerpt(content: string): string {
  const flat = content.replace(/\s+/g, ' ').trim()
  return flat.length > ADMIN_REVIEW_EXCERPT_LENGTH ? `${flat.slice(0, ADMIN_REVIEW_EXCERPT_LENGTH)}…` : flat
}

/**
 * 사진 칸 문구. 숨김 리뷰의 사진은 관리자에게도 서빙이 404라(D-237 결정 4 · 관리자 전용 경로는 이월) 이미지를 그리지 않고 장수만 알린다.
 * 사진이 없으면 대시.
 */
export function reviewPhotoText(item: AdminReviewListItem): string {
  const count = item.photoUrls.length
  if (count === 0) return '—'
  return item.status === 'HIDDEN' ? `사진 ${count}장(비공개)` : `사진 ${count}장`
}

/** 현재 상태에서 바꿀 목표 상태(공개 ↔ 숨김). */
export function reviewTransitionTarget(status: ReviewStatus): ReviewStatus {
  return status === 'VISIBLE' ? 'HIDDEN' : 'VISIBLE'
}
