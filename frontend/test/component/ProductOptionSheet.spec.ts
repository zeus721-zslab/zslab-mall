import { describe, it, expect, vi, afterEach } from 'vitest'
import { defineComponent, h, nextTick, reactive } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import type { VueWrapper } from '@vue/test-utils'
import ProductOptionSheet from '~/skins/renew/components/ProductOptionSheet.vue'
import ProductPurchaseOptions from '~/skins/renew/components/ProductPurchaseOptions.vue'
import type { ProductDetailPageVm } from '~/skins/contracts/product-detail'
import type { ProductDetail, ProductVariant } from '~/types/product'

/**
 * FE-100 옵션 시트: 옵션 미선택이면 담기·바로구매 비활성 · 시트에서 고른 옵션이 본문 옵션 칩에도 그대로 보인다(같은 vm)
 * · 선택 뒤 담기·바로구매가 각각 add·buy를 알린다. 시트는 body로 텔레포트되므로 문서에서 찾는다.
 */
const VARIANTS: ProductVariant[] = [
  { variantPublicId: 'var_black', salePrice: 21000, soldOut: false, options: [{ groupName: '색상', value: '블랙' }] },
  { variantPublicId: 'var_white', salePrice: 23000, soldOut: false, options: [{ groupName: '색상', value: '화이트' }] },
]
const PRODUCT = {
  productPublicId: 'prd_test', name: '옵션 셔츠', description: null, categoryId: 1, categoryName: '의류', sellerName: '셀러',
  displayPrice: 21000, soldOut: false, saleStopped: false, images: [], sellerPublicId: 'slr_test',
  optionGroups: [{ name: '색상', displayOrder: 0, values: [{ value: '블랙', displayOrder: 0 }, { value: '화이트', displayOrder: 1 }] }],
  variants: VARIANTS,
} satisfies ProductDetail

let mounted: VueWrapper | null = null
afterEach(() => {
  mounted?.unmount()
  mounted = null
  document.body.innerHTML = ''
})

/** 페이지 vm과 같은 규칙(전 그룹 선택 → variant 확정 → 담기 가능)만 흉내 낸 vm. */
function pageVm(): ProductDetailPageVm {
  const vm = reactive({
    selectedOptions: {} as Record<string, string>,
    selectOption(groupName: string, value: string): void {
      vm.selectedOptions = { ...vm.selectedOptions, [groupName]: value }
    },
    get selectedVariant(): ProductVariant | null {
      return VARIANTS.find((variant) => variant.options.every((option) => vm.selectedOptions[option.groupName] === option.value)) ?? null
    },
    get canAddToCart(): boolean {
      return vm.selectedVariant !== null
    },
    get totalPrice(): number | null {
      return vm.selectedVariant ? vm.selectedVariant.salePrice * vm.quantity : null
    },
    quantity: 1,
    decrementQuantity: () => {},
    incrementQuantity: () => {},
    adding: false,
    unavailableLabel: null as string | null,
    isOptionValueSoldOut: (_groupName: string, _value: string): boolean => false,
    isOptionValueUnavailable: (_groupName: string, _value: string): boolean => false,
  })
  // 시트·옵션 컴포넌트가 읽는 필드만 둔 부분 vm이다.
  return vm as ProductDetailPageVm
}

async function mountSheetWithBody(vm: ProductDetailPageVm) {
  const onAdd = vi.fn()
  const onBuy = vi.fn()
  const Host = defineComponent({
    setup: () => () => [
      h('div', { 'data-testid': 'body-options' }, [h(ProductPurchaseOptions, { vm, product: PRODUCT })]),
      h(ProductOptionSheet, { open: true, vm, product: PRODUCT, onAdd, onBuy }),
    ],
  })
  mounted = await mountSuspended(Host, { attachTo: document.body })
  await nextTick()
  return { onAdd, onBuy }
}

function sheet(): HTMLElement {
  const element = document.querySelector<HTMLElement>('[data-testid="product-option-sheet"]')
  if (!element) throw new Error('옵션 시트가 열리지 않았습니다')
  return element
}

function buttonIn(container: ParentNode, name: string): HTMLButtonElement {
  const button = [...container.querySelectorAll<HTMLButtonElement>('button')].find((candidate) => candidate.textContent?.trim() === name)
  if (!button) throw new Error(`버튼 없음: ${name}`)
  return button
}

describe('ProductOptionSheet(FE-100)', () => {
  it('옵션 미선택이면 담기·바로구매가 비활성이고 요약은 선택 안내', async () => {
    await mountSheetWithBody(pageVm())
    expect(buttonIn(sheet(), '장바구니 담기').disabled).toBe(true)
    expect(buttonIn(sheet(), '바로구매').disabled).toBe(true)
    expect(sheet().querySelector('[data-testid="product-option-sheet-summary"]')?.textContent).toContain('옵션을 선택해 주세요')
  })

  it('시트에서 고른 옵션이 본문 칩에도 선택으로 보이고 요약·금액이 바뀐다', async () => {
    const vm = pageVm()
    await mountSheetWithBody(vm)
    buttonIn(sheet(), '화이트').click()
    await nextTick()

    expect(vm.selectedOptions).toEqual({ 색상: '화이트' })
    const bodyOptions = document.querySelector('[data-testid="body-options"]')
    if (!bodyOptions) throw new Error('본문 옵션 없음')
    expect(buttonIn(bodyOptions, '화이트').getAttribute('aria-pressed')).toBe('true')
    expect(buttonIn(bodyOptions, '블랙').getAttribute('aria-pressed')).toBe('false')
    const summary = sheet().querySelector('[data-testid="product-option-sheet-summary"]')?.textContent ?? ''
    expect(summary).toContain('화이트')
    expect(summary).toContain('23,000')
  })

  it('본문에서 먼저 고른 옵션은 시트에도 선택으로 보이고, 담기·바로구매가 각각 add·buy를 알린다', async () => {
    const vm = pageVm()
    vm.selectOption('색상', '블랙')
    const { onAdd, onBuy } = await mountSheetWithBody(vm)

    expect(buttonIn(sheet(), '블랙').getAttribute('aria-pressed')).toBe('true')
    buttonIn(sheet(), '장바구니 담기').click()
    buttonIn(sheet(), '바로구매').click()
    expect(onAdd).toHaveBeenCalledTimes(1)
    expect(onBuy).toHaveBeenCalledTimes(1)
  })
})
