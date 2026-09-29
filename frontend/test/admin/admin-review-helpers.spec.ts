import { describe, it, expect } from 'vitest'
import {
  DEFAULT_ADMIN_REVIEW_QUERY,
  hasActiveReviewFilters,
  parseAdminReviewQuery,
  toAdminReviewApiParams,
  toAdminReviewRouteQuery,
} from '#layers/admin/app/lib/admin-review-query'
import { reviewExcerpt, reviewPhotoText, reviewTransitionTarget } from '#layers/admin/app/lib/admin-review-view'
import { resolveActiveMenuPath } from '#layers/admin/app/lib/constants/admin-menu'
import type { AdminReviewListItem } from '#layers/admin/app/types/admin-review'

/** Track 106-1 PR2 관리자 리뷰: URL query 매핑 · 표시 함수(숨김 리뷰 사진은 장수만) · 메뉴 위치. */

function row(overrides: Partial<AdminReviewListItem>): AdminReviewListItem {
  return {
    reviewId: 'rvw_1',
    rating: 1,
    content: '본문',
    status: 'VISIBLE',
    helpfulCount: 0,
    photoUrls: [],
    createdAt: '2026-09-20T12:00:00+09:00',
    ...overrides,
  }
}

describe('admin-review-query', () => {
  it('정상 값 파싱 · 모르는 status·음수 page·허용 밖 size → 기본값', () => {
    expect(parseAdminReviewQuery({ status: 'HIDDEN', page: '2', size: '50' })).toEqual({ status: 'HIDDEN', page: 2, size: 50 })
    expect(parseAdminReviewQuery({ status: 'DELETED', page: '-1', size: '7' })).toEqual(DEFAULT_ADMIN_REVIEW_QUERY)
  })

  it('기본값은 URL에서 빼고 · API 파라미터는 status 있을 때만 · 필터 활성 판정', () => {
    expect(toAdminReviewRouteQuery(DEFAULT_ADMIN_REVIEW_QUERY)).toEqual({})
    expect(toAdminReviewRouteQuery({ status: 'HIDDEN', page: 1, size: 50 })).toEqual({ status: 'HIDDEN', page: '1', size: '50' })
    expect(toAdminReviewApiParams(DEFAULT_ADMIN_REVIEW_QUERY)).toEqual({ page: 0, size: 20 })
    expect(toAdminReviewApiParams({ status: 'VISIBLE', page: 0, size: 20 })).toEqual({ status: 'VISIBLE', page: 0, size: 20 })
    expect(hasActiveReviewFilters(DEFAULT_ADMIN_REVIEW_QUERY)).toBe(false)
    expect(hasActiveReviewFilters({ ...DEFAULT_ADMIN_REVIEW_QUERY, status: 'HIDDEN' })).toBe(true)
  })
})

describe('admin-review-view', () => {
  it('사진: 숨김 → "사진 N장(비공개)"(서빙 404라 이미지 없음) · 공개 → "사진 N장" · 없음 → 대시', () => {
    expect(reviewPhotoText(row({ status: 'HIDDEN', photoUrls: ['/a', '/b'] }))).toBe('사진 2장(비공개)')
    expect(reviewPhotoText(row({ photoUrls: ['/a'] }))).toBe('사진 1장')
    expect(reviewPhotoText(row({}))).toBe('—')
  })

  it('본문 발췌(줄바꿈 → 공백 · 60자 넘으면 말줄임) · 목표 상태(공개 ↔ 숨김)', () => {
    expect(reviewExcerpt('첫 줄\n둘째 줄')).toBe('첫 줄 둘째 줄')
    expect(reviewExcerpt('가'.repeat(61))).toBe(`${'가'.repeat(60)}…`)
    expect(reviewTransitionTarget('VISIBLE')).toBe('HIDDEN')
    expect(reviewTransitionTarget('HIDDEN')).toBe('VISIBLE')
  })

  it('메뉴: 상품 관리 > 리뷰(/admin/products/reviews) 정확 일치', () => {
    expect(resolveActiveMenuPath('/admin/products/reviews')).toBe('/admin/products/reviews')
  })
})
