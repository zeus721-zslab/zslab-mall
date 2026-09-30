import { describe, it, expect, vi, afterEach } from 'vitest'
import { defineComponent, h, reactive } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import ProductGlanceChips from '~/skins/renew/components/ProductGlanceChips.vue'
import ProductQuestionSection from '~/skins/renew/components/ProductQuestionSection.vue'
import ProductSectionNav from '~/skins/renew/components/ProductSectionNav.vue'
import type { ProductQuestionsVm } from '~/skins/contracts/product-questions'
import type { ProductReviewsVm } from '~/skins/contracts/product-reviews'
import type { ProductSectionNavItem, ProductSectionNavVm } from '~/skins/contracts/product-section-nav'
import { PRODUCT_SECTION_IDS } from '~/lib/constants/product-sections'

/**
 * Track 106-2 상품 상세 내비게이션: 한눈에 칩(0건 대체 문구 · 이동) · 진행형 섹션 바(aria-current · 진행률 · 물어보기 펼침/접힘) ·
 * 바와 묻기 섹션의 입력 초안 공유(같은 vm 인스턴스).
 */
let mounted: VueWrapper | null = null
afterEach(() => {
  mounted?.unmount()
  mounted = null
})

function navVm(overrides: Partial<ProductSectionNavVm> = {}): ProductSectionNavVm {
  return reactive({ activeId: null, progress: {}, stickyTop: 69, go: vi.fn(), ask: vi.fn(), ...overrides }) as ProductSectionNavVm
}

/** setDraft가 실제로 draft를 바꾸는 질문 vm(초안 공유 확인용). */
function questionsVm(overrides: Partial<ProductQuestionsVm> = {}): ProductQuestionsVm {
  const vm: ProductQuestionsVm = reactive({
    draft: '',
    setDraft: (text: string) => {
      vm.draft = text
    },
    suggestions: [],
    submitting: false,
    submit: vi.fn(),
    notice: null,
    items: [],
    totalCount: 0,
    pending: false,
    failed: false,
    retry: vi.fn(),
    hasNext: false,
    loadingMore: false,
    loadMore: vi.fn(),
    actionPendingId: null,
    saveEdit: vi.fn(),
    remove: vi.fn(),
    ...overrides,
  }) as ProductQuestionsVm
  return vm
}

/** 칩이 읽는 리뷰 필드(요약·총수)만. */
function reviewsVm(reviewCount: number, averageRating?: number): Pick<ProductReviewsVm, 'summary' | 'totalCount'> {
  return {
    summary: {
      reviewCount,
      averageRating,
      ratingDistribution: [],
      keywords: reviewCount > 0 ? [{ code: 'QUALITY_GOOD', label: '품질이 좋아요', count: 3 }] : [],
      recentPhotos: [],
    },
    totalCount: reviewCount,
  }
}

const ITEMS: ProductSectionNavItem[] = [
  { id: PRODUCT_SECTION_IDS.description, label: '상품설명' },
  { id: PRODUCT_SECTION_IDS.reviews, label: '리뷰 3' },
  { id: PRODUCT_SECTION_IDS.questions, label: 'Q&A 0' },
]

describe('ProductGlanceChips', () => {
  it('0건이면 "첫 리뷰를 기다려요" · 키워드 칩 없음 · "궁금한 점 물어보기"(누르면 ask)', async () => {
    const nav = navVm()
    mounted = await mountSuspended(ProductGlanceChips, { props: { reviews: reviewsVm(0), questions: questionsVm(), nav } })
    expect(mounted.find('[data-testid="glance-review"]').text()).toBe('첫 리뷰를 기다려요')
    expect(mounted.find('[data-testid="glance-keyword"]').exists()).toBe(false)
    expect(mounted.find('[data-testid="glance-questions"]').text()).toBe('궁금한 점 물어보기')
    await mounted.find('[data-testid="glance-questions"]').trigger('click')
    expect(nav.ask).toHaveBeenCalledOnce()
  })

  it('건수가 있으면 "★평균 · 리뷰 n" · 최상위 키워드 · "Q&A n" · 리뷰 칩은 리뷰 섹션으로 이동', async () => {
    const nav = navVm()
    mounted = await mountSuspended(ProductGlanceChips, {
      props: { reviews: reviewsVm(3, 4.3), questions: questionsVm({ totalCount: 2 }), nav },
    })
    expect(mounted.find('[data-testid="glance-review"]').text().replace(/\s+/g, '')).toBe('4.3·리뷰3')
    expect(mounted.find('[data-testid="glance-keyword"]').text()).toContain('품질이 좋아요')
    expect(mounted.find('[data-testid="glance-questions"]').text().replace(/\s+/g, ' ')).toBe('Q&A 2')
    await mounted.find('[data-testid="glance-review"]').trigger('click')
    expect(nav.go).toHaveBeenCalledWith(PRODUCT_SECTION_IDS.reviews)
  })
})

describe('ProductSectionNav', () => {
  it('현재 섹션에만 aria-current · 진행률만큼 배경 폭 · 알약을 누르면 그 섹션으로', async () => {
    const nav = navVm({ activeId: PRODUCT_SECTION_IDS.reviews, progress: { 'product-description': 1, 'product-reviews': 0.4 } })
    mounted = await mountSuspended(ProductSectionNav, { props: { nav, items: ITEMS, questions: questionsVm() } })

    expect(mounted.find('nav').attributes('aria-label')).toBe('상품 정보 바로가기')
    expect(mounted.find('[data-testid="section-nav-product-reviews"]').attributes('aria-current')).toBe('true')
    expect(mounted.find('[data-testid="section-nav-product-description"]').attributes('aria-current')).toBeUndefined()
    const fills = mounted.findAll('[data-testid="section-nav-progress"]').map((fill) => fill.attributes('style'))
    expect(fills).toEqual(['width: 100%;', 'width: 40%;', 'width: 0%;'])

    await mounted.find('[data-testid="section-nav-product-questions"]').trigger('click')
    expect(nav.go).toHaveBeenCalledWith(PRODUCT_SECTION_IDS.questions)
  })

  it('물어보기 → 입력창으로 펼침(포커스) · Esc → 접히고 물어보기 버튼으로 포커스 · 초안 유지 · 바깥 클릭도 접힘', async () => {
    const questions = questionsVm()
    mounted = await mountSuspended(ProductSectionNav, { props: { nav: navVm(), items: ITEMS, questions }, attachTo: document.body })

    await mounted.find('[data-testid="section-nav-ask"]').trigger('click')
    await flushPromises()
    const input = mounted.find('[data-testid="section-nav-ask-input"]')
    expect(input.exists()).toBe(true)
    expect(document.activeElement).toBe(input.element)
    await input.setValue('세탁 되나요')
    expect(questions.draft).toBe('세탁 되나요')

    await mounted.find('nav').trigger('keydown', { key: 'Escape' })
    await flushPromises()
    expect(mounted.find('[data-testid="section-nav-ask-input"]').exists()).toBe(false)
    expect(document.activeElement).toBe(mounted.find('[data-testid="section-nav-ask"]').element)
    expect(questions.draft).toBe('세탁 되나요')

    await mounted.find('[data-testid="section-nav-ask"]').trigger('click')
    await flushPromises()
    expect((mounted.find('[data-testid="section-nav-ask-input"]').element as HTMLTextAreaElement).value).toBe('세탁 되나요')
    document.body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }))
    await flushPromises()
    expect(mounted.find('[data-testid="section-nav-ask-input"]').exists()).toBe(false)
  })

  it('바와 묻기 섹션은 같은 vm을 써서 어느 쪽에서 입력해도 같은 초안이 보인다', async () => {
    const questions = questionsVm()
    mounted = await mountSuspended(defineComponent({
      setup: () => () => h('div', [
        h(ProductSectionNav, { nav: navVm(), items: ITEMS, questions }),
        h(ProductQuestionSection, { questions }),
      ]),
    }), { attachTo: document.body })

    await mounted.find('[data-testid="section-nav-ask"]').trigger('click')
    await flushPromises()
    await mounted.find('[data-testid="section-nav-ask-input"]').setValue('사이즈가 어떤가요')
    expect((mounted.find('[data-testid="product-question-input"]').element as HTMLTextAreaElement).value).toBe('사이즈가 어떤가요')

    await mounted.find('[data-testid="product-question-input"]').setValue('사이즈가 크게 나오나요')
    expect((mounted.find('[data-testid="section-nav-ask-input"]').element as HTMLTextAreaElement).value).toBe('사이즈가 크게 나오나요')
  })
})
