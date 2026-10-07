import { describe, it, expect, vi, afterEach } from 'vitest'
import { nextTick, reactive } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import type { VueWrapper } from '@vue/test-utils'
import RenewProductListing from '~/skins/renew/components/RenewProductListing.vue'
import ProductReviewSection from '~/skins/renew/components/ProductReviewSection.vue'
import ProductQuestionSection from '~/skins/renew/components/ProductQuestionSection.vue'
import ProductGlanceChips from '~/skins/renew/components/ProductGlanceChips.vue'
import type { ProductPageListVm } from '~/skins/contracts/product-page'
import type { ProductReviewsVm } from '~/skins/contracts/product-reviews'
import type { ProductQuestionsVm } from '~/skins/contracts/product-questions'
import type { ProductSectionNavVm } from '~/skins/contracts/product-section-nav'

/**
 * FE-111(퀄리티 9-3): 응답 대기 중에는 기본값 0이 "0개의 상품" · "리뷰 0" · "질문 0" · "첫 리뷰를 기다려요" · "궁금한 점 물어보기"로 보이지 않고,
 * 응답 후(pending false)에는 0건 표시가 그대로 나온다.
 */
let mounted: VueWrapper | null = null
afterEach(() => {
  mounted?.unmount()
  mounted = null
})

function listVm(pending: boolean): ProductPageListVm {
  return reactive({
    items: [],
    totalCount: 0,
    pending,
    hasError: false,
    page: 1,
    totalPages: 1,
    sort: 'LATEST',
    sortOptions: [],
    categories: [],
    activeCategoryName: null,
    setSort: vi.fn(),
    goToPage: vi.fn(),
    retry: vi.fn(),
  }) as ProductPageListVm
}

function reviewsVm(pending: boolean): ProductReviewsVm {
  return reactive({
    summary: null,
    ratingBars: [],
    keywordBars: [],
    recentPhotoThumbnails: [],
    recentPhotoUrls: [],
    items: [],
    totalCount: 0,
    pending,
    failed: false,
    retry: vi.fn(),
    hasNext: false,
    loadingMore: false,
    loadMore: vi.fn(),
    filter: { photoOnly: false, keyword: null, option: null, sort: 'HELPFUL' },
    isFiltered: false,
    optionChoices: [],
    setPhotoOnly: vi.fn(),
    setKeyword: vi.fn(),
    setOption: vi.fn(),
    setSort: vi.fn(),
    resetFilter: vi.fn(),
    toggleHelpful: vi.fn(),
    helpfulPendingId: null,
    ownReviewIds: [],
    helpfulNotice: null,
  }) as ProductReviewsVm
}

function questionsVm(pending: boolean): ProductQuestionsVm {
  return reactive({
    draft: '',
    setDraft: vi.fn(),
    suggestions: [],
    submitting: false,
    submit: vi.fn(),
    notice: null,
    items: [],
    totalCount: 0,
    pending,
    failed: false,
    retry: vi.fn(),
    hasNext: false,
    loadingMore: false,
    loadMore: vi.fn(),
    actionPendingId: null,
    saveEdit: vi.fn(),
    remove: vi.fn(),
  }) as ProductQuestionsVm
}

function navVm(): ProductSectionNavVm {
  return reactive({ activeId: null, progress: {}, stickyTop: 69, go: vi.fn(), ask: vi.fn() }) as ProductSectionNavVm
}

describe('RenewProductListing — 배너 상품 수(D1)', () => {
  it('대기 중 "0개의 상품" 대신 스켈레톤 → 응답 후 "0개의 상품"', async () => {
    const list = listVm(true)
    mounted = await mountSuspended(RenewProductListing, { props: { list, categoryId: null } })
    expect(mounted.text()).not.toContain('개의 상품')
    expect(mounted.find('[data-testid="listing-count-skeleton"]').exists()).toBe(true)

    list.pending = false
    await nextTick()
    expect(mounted.text()).toContain('0개의 상품')
    expect(mounted.find('[data-testid="listing-count-skeleton"]').exists()).toBe(false)
  })
})

describe('ProductReviewSection — 머리말 건수(D2)', () => {
  it('대기 중 건수 미표시 · 빈 상태 없음 → 응답 후 "0" · 빈 상태', async () => {
    const reviews = reviewsVm(true)
    mounted = await mountSuspended(ProductReviewSection, { props: { reviews } })
    expect(mounted.find('[data-testid="product-reviews-count"]').exists()).toBe(false)
    expect(mounted.find('#product-reviews-title').text()).toBe('리뷰')
    expect(mounted.find('[data-testid="product-reviews-empty"]').exists()).toBe(false)

    reviews.pending = false
    await nextTick()
    expect(mounted.get('[data-testid="product-reviews-count"]').text()).toBe('0')
    expect(mounted.find('[data-testid="product-reviews-empty"]').exists()).toBe(true)
  })
})

describe('ProductQuestionSection — 목록 머리말 건수(D3)', () => {
  it('대기 중 건수 미표시 · 빈 상태 없음 → 응답 후 "0" · 빈 상태', async () => {
    const questions = questionsVm(true)
    mounted = await mountSuspended(ProductQuestionSection, { props: { questions } })
    expect(mounted.find('[data-testid="product-questions-count"]').exists()).toBe(false)
    expect(mounted.find('h3').text()).toBe('질문')
    expect(mounted.find('[data-testid="product-questions-empty"]').exists()).toBe(false)

    questions.pending = false
    await nextTick()
    expect(mounted.get('[data-testid="product-questions-count"]').text()).toBe('0')
    expect(mounted.find('[data-testid="product-questions-empty"]').exists()).toBe(true)
  })
})

describe('ProductGlanceChips — 리뷰·Q&A 칩(D4)', () => {
  it('대기 중 빈 상태 문구 칩 없음 → 응답 후 "첫 리뷰를 기다려요" · "궁금한 점 물어보기"', async () => {
    const reviews = reviewsVm(true)
    const questions = questionsVm(true)
    mounted = await mountSuspended(ProductGlanceChips, { props: { reviews, questions, nav: navVm() } })
    expect(mounted.find('[data-testid="glance-review"]').exists()).toBe(false)
    expect(mounted.find('[data-testid="glance-questions"]').exists()).toBe(false)
    expect(mounted.text()).not.toContain('첫 리뷰를 기다려요')
    expect(mounted.text()).not.toContain('궁금한 점 물어보기')

    reviews.pending = false
    questions.pending = false
    await nextTick()
    expect(mounted.get('[data-testid="glance-review"]').text()).toBe('첫 리뷰를 기다려요')
    expect(mounted.get('[data-testid="glance-questions"]').text()).toBe('궁금한 점 물어보기')
  })
})

// UX-02: 조회 실패 시에도 기본값 0이 "0개의 상품" · 건수 0 · 빈 상태 칩으로 보이지 않는다(오류 상태만 보인다).
describe('조회 실패 시 0건·빈 상태 미표시(UX-02)', () => {
  it('RenewProductListing: 실패 시 "0개의 상품" · 빈 목록 없이 오류 상태만', async () => {
    const list = listVm(false)
    list.hasError = true
    mounted = await mountSuspended(RenewProductListing, { props: { list, categoryId: null } })
    expect(mounted.text()).not.toContain('개의 상품')
    expect(mounted.find('[data-testid="listing-count"]').exists()).toBe(false)
    expect(mounted.find('[data-testid="listing-empty"]').exists()).toBe(false)
  })

  it('ProductReviewSection: 실패 시 머리말 건수 없음', async () => {
    const reviews = reviewsVm(false)
    reviews.failed = true
    mounted = await mountSuspended(ProductReviewSection, { props: { reviews } })
    expect(mounted.find('[data-testid="product-reviews-count"]').exists()).toBe(false)
    expect(mounted.find('#product-reviews-title').text()).toBe('리뷰')
    expect(mounted.find('[data-testid="product-reviews-empty"]').exists()).toBe(false)
  })

  it('ProductQuestionSection: 실패 시 목록 머리말 건수 없음', async () => {
    const questions = questionsVm(false)
    questions.failed = true
    mounted = await mountSuspended(ProductQuestionSection, { props: { questions } })
    expect(mounted.find('[data-testid="product-questions-count"]').exists()).toBe(false)
    expect(mounted.find('h3').text()).toBe('질문')
    expect(mounted.find('[data-testid="product-questions-empty"]').exists()).toBe(false)
  })

  it('ProductGlanceChips: 실패 시 "첫 리뷰를 기다려요" · "궁금한 점 물어보기" 칩 없음', async () => {
    const reviews = reviewsVm(false)
    const questions = questionsVm(false)
    reviews.failed = true
    questions.failed = true
    mounted = await mountSuspended(ProductGlanceChips, { props: { reviews, questions, nav: navVm() } })
    expect(mounted.find('[data-testid="glance-review"]').exists()).toBe(false)
    expect(mounted.find('[data-testid="glance-questions"]').exists()).toBe(false)
  })
})
