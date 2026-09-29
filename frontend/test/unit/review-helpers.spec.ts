import { describe, it, expect } from 'vitest'
import { canWriteReview, reviewEditPath, reviewWritePath } from '~/lib/utils/review-links'
import { reviewPhotoItemErrorMessage, reviewPhotoRequestErrorMessage, reviewWriteFailure } from '~/lib/utils/review-error'

/** Track 106-1 PR2 리뷰 순수 함수: 주문 품목 → 작성·수정 경로 · API 실패 → 문구. */

const ITEM = {
  orderItemId: 'oit_01ABCDEFGHJKMNPQRSTVWXYZ00',
  productId: 'prd_01ABCDEFGHJKMNPQRSTVWXYZ00',
  productName: '린넨 셔츠',
  optionLabel: '색상: 블랙 / 사이즈: M',
}

describe('review-links', () => {
  it('WRITABLE + 상품 있음 → 작성 가능 · 누른 별점을 query로', () => {
    const item = { ...ITEM, review: { status: 'WRITABLE' as const } }
    expect(canWriteReview(item)).toBe(true)
    expect(reviewWritePath(item, 4)).toBe(
      `/reviews/new?orderItem=${ITEM.orderItemId}&product=${ITEM.productId}&name=${encodeURIComponent('린넨 셔츠')}`
      + `&option=${encodeURIComponent(ITEM.optionLabel)}&rating=4`,
    )
    expect(reviewEditPath(item)).toBeNull()
  })

  it('삭제 상품(productId 없음)·NOT_ELIGIBLE·review 없음(옛 응답) → 작성 불가', () => {
    expect(canWriteReview({ ...ITEM, productId: undefined, review: { status: 'WRITABLE' } })).toBe(false)
    expect(canWriteReview({ ...ITEM, review: { status: 'NOT_ELIGIBLE' } })).toBe(false)
    expect(canWriteReview(ITEM)).toBe(false)
  })

  it('WRITTEN + reviewId → 수정 경로 · reviewId 없음(삭제한 리뷰) → null', () => {
    expect(reviewEditPath({ ...ITEM, review: { status: 'WRITTEN', reviewId: 'rvw_1' } })).toBe(
      `/reviews/rvw_1/edit?product=${ITEM.productId}&name=${encodeURIComponent('린넨 셔츠')}&option=${encodeURIComponent(ITEM.optionLabel)}`,
    )
    expect(reviewEditPath({ ...ITEM, review: { status: 'WRITTEN' } })).toBeNull()
  })
})

describe('review-error', () => {
  it('작성·수정 실패 코드별 문구 · 401만 로그인', () => {
    expect(reviewWriteFailure({ statusCode: 401 }).login).toBe(true)
    expect(reviewWriteFailure({ statusCode: 409, data: { code: 'REVIEW_ALREADY_EXISTS' } }).message).toBe('이미 리뷰를 작성한 상품입니다.')
    expect(reviewWriteFailure({ statusCode: 422, data: { code: 'REVIEW_INVALID_STATE' } }).message).toBe('비공개 처리된 리뷰는 수정할 수 없습니다.')
    expect(reviewWriteFailure({ statusCode: 422, data: { code: 'REVIEW_NOT_ELIGIBLE' } }).message).toContain('구매확정 후')
    expect(reviewWriteFailure({ statusCode: 404, data: { code: 'REVIEW_NOT_FOUND' } }).message).toBe('리뷰를 찾을 수 없습니다.')
    expect(reviewWriteFailure({ statusCode: 404, data: { code: 'ORDER_NOT_FOUND' } }).message).toBe('주문 품목을 찾을 수 없습니다.')
    expect(reviewWriteFailure({ statusCode: 400, data: { detail: '이 상품에 쓸 수 없는 키워드입니다: [X]' } }).message).toBe('이 상품에 쓸 수 없는 키워드입니다: [X]')
    expect(reviewWriteFailure(new Error('network')).login).toBe(false)
  })

  it('사진: 해상도 초과는 서버 문구(리뷰 한도) · 그 밖 코드는 클레임 문구 재사용 · 요청 실패 413·400·503', () => {
    expect(reviewPhotoItemErrorMessage({ success: false, code: 'IMAGE_TOO_LARGE', message: '해상도가 너무 큽니다(최대 5천만 화소).' }))
      .toBe('해상도가 너무 큽니다(최대 5천만 화소).')
    expect(reviewPhotoItemErrorMessage({ success: false, code: 'EMPTY_FILE' })).toBe('빈 파일입니다.')
    expect(reviewPhotoRequestErrorMessage({ statusCode: 413 })).toBe('파일이 너무 큽니다(파일당 5MB).')
    expect(reviewPhotoRequestErrorMessage({ statusCode: 400, data: { detail: '연결되지 않은 사진이 너무 많습니다.' } }))
      .toBe('연결되지 않은 사진이 너무 많습니다.')
    expect(reviewPhotoRequestErrorMessage({ statusCode: 503, data: { code: 'UPLOAD_BUSY' } })).toContain('잠시 후')
  })
})
