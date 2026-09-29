import { describe, it, expect, vi } from 'vitest'
import { reactive } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ReviewFormView from '~/skins/renew/views/ReviewFormView.vue'
import type { ReviewFormPageVm } from '~/skins/contracts/review-form'

/**
 * Track 106-1 PR2 리뷰 작성·수정 화면(ReviewFormView). 점진형: 별점을 고르기 전에는 키워드·사진·본문이 없고, 고르면 나타난다.
 * 잘못된 접근 · 숨김 잠김 · 완료(상품 페이지 링크) · 키워드 최대 개수 잠금을 vm대로 보이는지 본다(폼 로직은 useReviewFormPage).
 */
function vmWith(overrides: Partial<ReviewFormPageVm>): ReviewFormPageVm {
  return reactive({
    mode: 'create',
    isValidQuery: true,
    loading: false,
    loadError: '',
    locked: false,
    hiddenReason: '',
    productName: '린넨 셔츠',
    optionLabel: '색상: 블랙',
    productPath: '/products/prd_1',
    rating: null,
    ratingLabel: '별점을 선택해 주세요',
    keywordSections: [
      {
        group: 'QUALITY',
        label: '품질',
        options: [
          { code: 'QUALITY_GOOD', label: '품질이 좋아요', groupCode: 'QUALITY', sortOrder: 3 },
          { code: 'SAME_AS_DESCRIPTION', label: '설명과 같아요', groupCode: 'QUALITY', sortOrder: 4 },
        ],
      },
    ],
    keywordsPending: false,
    keywordsFailed: false,
    retryKeywords: vi.fn(),
    selectedKeywords: [],
    toggleKeyword: vi.fn(),
    keywordLimitReached: false,
    photos: [],
    photosBusy: false,
    photosFailed: 0,
    uploadPhoto: vi.fn(),
    content: '',
    submitting: false,
    submitted: false,
    canSubmit: false,
    errorMessage: '',
    handleSubmit: vi.fn(),
    CONTENT_MAX: 1000,
    KEYWORD_MAX: 10,
    PHOTO_MAX: 5,
    ...overrides,
  }) as ReviewFormPageVm
}

describe('ReviewFormView', () => {
  it('별점 전 → 키워드·사진·본문 없음 · 등록 잠김 / 별점 선택 후 → 나타남 · 점수 문구', async () => {
    const before = await mountSuspended(ReviewFormView, { props: { vm: vmWith({}) } })
    expect(before.get('[data-testid="review-product-name"]').text()).toBe('린넨 셔츠')
    expect(before.find('[data-testid="review-keyword-section"]').exists()).toBe(false)
    expect(before.find('[data-testid="review-content"]').exists()).toBe(false)
    expect((before.get('[data-testid="review-submit"]').element as HTMLButtonElement).disabled).toBe(true)

    const after = await mountSuspended(ReviewFormView, { props: { vm: vmWith({ rating: 4, ratingLabel: '좋아요' }) } })
    expect(after.get('[data-testid="review-rating-label"]').text()).toBe('좋아요')
    expect(after.find('[data-testid="review-keyword-section"]').exists()).toBe(true)
    expect(after.find('[data-testid="photo-input"]').exists()).toBe(true)
    expect(after.find('[data-testid="review-content"]').exists()).toBe(true)
  })

  it('키워드 칩: 선택 = aria-pressed · 최대 개수면 안 고른 칩 잠김 · 누르면 toggleKeyword', async () => {
    const vm = vmWith({ rating: 5, selectedKeywords: ['QUALITY_GOOD'], keywordLimitReached: true })
    const wrapper = await mountSuspended(ReviewFormView, { props: { vm } })
    const selected = wrapper.get('[data-testid="review-keyword-QUALITY_GOOD"]')
    const other = wrapper.get('[data-testid="review-keyword-SAME_AS_DESCRIPTION"]')
    expect(selected.attributes('aria-pressed')).toBe('true')
    expect((other.element as HTMLButtonElement).disabled).toBe(true)
    await selected.trigger('click')
    expect(vm.toggleKeyword).toHaveBeenCalledWith('QUALITY_GOOD')
  })

  it('숨김 리뷰(수정) → 폼 없이 "비공개 처리됨" + 사유 / 잘못된 접근 / 완료 → 상품 페이지 링크', async () => {
    const locked = await mountSuspended(ReviewFormView, { props: { vm: vmWith({ mode: 'edit', locked: true, hiddenReason: '광고성 게시물' }) } })
    expect(locked.get('[data-testid="review-locked"]').text()).toContain('비공개 처리된 리뷰는 수정할 수 없습니다.')
    expect(locked.get('[data-testid="review-locked"]').text()).toContain('광고성 게시물')
    expect(locked.find('form').exists()).toBe(false)

    const invalid = await mountSuspended(ReviewFormView, { props: { vm: vmWith({ isValidQuery: false }) } })
    expect(invalid.find('[data-testid="review-invalid"]').exists()).toBe(true)

    const done = await mountSuspended(ReviewFormView, { props: { vm: vmWith({ submitted: true }) } })
    expect(done.get('[data-testid="review-submitted-product-link"]').attributes('href')).toBe('/products/prd_1')
  })
})
