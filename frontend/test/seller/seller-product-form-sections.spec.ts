import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import { flushPromises } from '@vue/test-utils'
import type { SellerProductDetail } from '#layers/seller/app/types/seller-product'
import SellerProductBasicSection from '#layers/seller/app/components/seller/SellerProductBasicSection.vue'
import SellerProductOptionSection from '#layers/seller/app/components/seller/SellerProductOptionSection.vue'
import { createEmptySellerProductForm, toSellerProductForm } from '#layers/seller/app/lib/seller-product-form'

/**
 * 셀러 상품 폼 섹션(Track 90-C-4): 기본정보 섹션에 공급가·판매기간·상태 변경 컨트롤이 없음(BE 계약 6) / 옵션 섹션은 수정 모드에서 그룹·값 편집 UI가
 * 잠기고(계약 2) 기존 variant 재고 입력 필드가 없으며(계약 1·읽기 전용 + 재고 화면 링크) 삭제 없이 "사용" 토글로 HIDDEN 전환(계약 3), 등록 모드는 전체 편집.
 */
const DETAIL: SellerProductDetail = {
  productPublicId: 'prd_1', name: '반찬통', categoryId: 7, status: 'SALE', basePrice: 32000, soldoutManual: false,
  createdAt: '2026-09-17T17:29:23+09:00', updatedAt: '2026-09-17T17:29:23+09:00', images: [],
  optionGroups: [{ optionGroupId: 1, name: '색상', displayOrder: 0, values: [{ optionValueId: 11, value: '블랙', displayOrder: 0 }, { optionValueId: 12, value: '화이트', displayOrder: 1 }] }],
  variants: [{ variantPublicId: 'var_1', variantCode: 'BLK', additionalPrice: 0, status: 'SALE', soldoutManual: false, displayOrder: 0,
    options: [{ optionGroupId: 1, optionValueId: 11, value: '블랙' }], quantityOnHand: 10, quantityReserved: 2, quantityAvailable: 8 }],
}
const CATEGORIES = [{ categoryId: 7, displayName: '주방', sortOrder: 0 }]

function body() {
  return document.body
}

async function mountSection(component: typeof SellerProductBasicSection | typeof SellerProductOptionSection, props: Record<string, unknown>) {
  const wrapper = await mountSuspended(component, { props, global: { plugins: [createVuetify()] }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

describe('SellerProductBasicSection', () => {
  beforeEach(() => { document.body.innerHTML = '' })

  it('등록 모드: 카테고리·기본가·상품명·설명만 — 셀러 선택·공급가·판매기간·상태 없음', async () => {
    await mountSection(SellerProductBasicSection, { modelValue: createEmptySellerProductForm(), mode: 'create', categories: CATEGORIES, errors: {} })
    for (const id of ['field-category', 'field-base-price', 'field-name', 'field-description']) {
      expect(body().querySelector(`[data-testid="${id}"]`), id).not.toBeNull()
    }
    for (const id of ['field-seller', 'field-seller-readonly', 'field-supply-price', 'field-sale-start', 'field-sale-end', 'field-no-end', 'status-chip', 'status-menu', 'product-soldout-toggle']) {
      expect(body().querySelector(`[data-testid="${id}"]`), id).toBeNull()
    }
  })

  it('수정 모드: 상태는 칩(읽기 전용 안내)으로만 표시·전환 메뉴 없음', async () => {
    await mountSection(SellerProductBasicSection, { modelValue: toSellerProductForm(DETAIL), mode: 'edit', categories: CATEGORIES, errors: { name: '상품명을 입력하세요.' } })
    expect(body().querySelector('[data-testid="status-chip"]')?.textContent?.trim()).toBe('판매중')
    expect(body().querySelector('[data-testid="status-readonly-note"]')?.textContent).toContain('관리자가 처리')
    expect(body().querySelector('[data-testid="status-menu"]')).toBeNull()
    expect(body().textContent).toContain('상품명을 입력하세요.')
  })
})

describe('SellerProductOptionSection', () => {
  beforeEach(() => { document.body.innerHTML = '' })

  it('등록 모드: 단일/옵션 토글·그룹 추가 버튼·초기 재고 입력·제외 체크', async () => {
    const form = createEmptySellerProductForm()
    await mountSection(SellerProductOptionSection, { modelValue: form, mode: 'create', errors: {} })
    expect(body().querySelector('[data-testid="option-mode-options"]')).not.toBeNull()
    expect(body().querySelector('[data-testid="option-locked-notice"]')).toBeNull()
    expect(body().querySelectorAll('[data-testid="variant-row"]')).toHaveLength(1)
    expect(body().querySelector('[data-testid="variant-initial-stock"]')).not.toBeNull()
    expect(body().querySelector('[data-testid="variant-exclude"]')).not.toBeNull()
    expect(body().querySelector('[data-testid="variant-enabled"]')).toBeNull()
  })

  it('수정 모드: 그룹·값 편집 UI 잠금(readonly·콤보박스/추가/삭제 없음) · 기존 행 재고 입력 없음(읽기 전용 + 재고 링크) · 사용 토글 → enabled false(HIDDEN) · 추가 가능 조합은 "추가" 체크', async () => {
    const form = toSellerProductForm(DETAIL)
    await mountSection(SellerProductOptionSection, { modelValue: form, mode: 'edit', errors: {} })
    expect(body().querySelector('[data-testid="option-locked-notice"]')).not.toBeNull()
    expect(body().querySelector('[data-testid="option-mode-options"]')).toBeNull()
    expect(body().querySelector('[data-testid="option-group-name-locked"]')).not.toBeNull()
    expect(body().querySelector('[data-testid="option-group-values-locked"]')?.textContent).toContain('블랙')
    for (const id of ['option-group-name', 'option-group-values', 'option-group-add', 'option-group-remove']) {
      expect(body().querySelector(`[data-testid="${id}"]`), id).toBeNull()
    }

    const rows = body().querySelectorAll<HTMLElement>('[data-testid="variant-row"]')
    expect(rows).toHaveLength(2)
    const existing = rows[0]!
    expect(existing.dataset.kind).toBe('existing')
    expect(existing.querySelector('[data-testid="variant-initial-stock"]')).toBeNull()
    expect(existing.querySelector('[data-testid="variant-stock-readonly"]')?.textContent).toContain('가용 8')
    expect(existing.querySelector('[data-testid="variant-stock-link"]')?.getAttribute('href')).toBe('/seller/products/inventory?productPublicId=prd_1')
    expect(existing.querySelector('[data-testid="variant-exclude"]')).toBeNull()

    const enabledInput = existing.querySelector<HTMLInputElement>('[data-testid="variant-enabled"] input')
    if (!enabledInput) throw new Error('사용 토글 없음')
    enabledInput.click()
    await flushPromises()
    expect(form.variants[0]?.enabled).toBe(false)

    const addable = rows[1]!
    expect(addable.dataset.kind).toBe('new')
    expect(addable.textContent).toContain('화이트')
    expect(addable.querySelector('[data-testid="variant-initial-stock"]')).not.toBeNull()
    const addInput = addable.querySelector<HTMLInputElement>('[data-testid="variant-add"] input')
    if (!addInput) throw new Error('추가 체크 없음')
    expect(form.variants[1]?.excluded).toBe(true)
    addInput.click()
    await flushPromises()
    expect(form.variants[1]?.excluded).toBe(false)
    expect(form.variants[1]?.variantCode).toBe('화이트')
  })
})
