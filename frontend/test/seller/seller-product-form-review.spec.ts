import { describe, it, expect, vi } from 'vitest'
import type { SellerProductDetail } from '#layers/seller/app/types/seller-product'
import type { SellerFormOptionGroup, SellerProductForm } from '#layers/seller/app/types/seller-product-form'
import {
  createEmptySellerProductForm,
  formSnapshot,
  isNonNegativeInteger,
  regenerateVariants,
  suggestVariantCode,
  toSellerCreateRequest,
  toSellerProductForm,
  toSellerVariantsRequest,
  validateSellerProductForm,
} from '#layers/seller/app/lib/seller-product-form'
import { SellerSaveStepError, saveSellerProduct, type SellerSaveApi } from '#layers/seller/app/lib/seller-product-save'

/**
 * 외부 검토 반영(Track 90-C): ⑦ 정수 경계(판매가·추가금·초기재고 소수·음수 → 오류) · 옵션 조합 중복 검증 직접 트리거 · 기존 variant의
 * initialStock·options를 오염시켜도 PUT variants 요청에서 0·[]로 나가는지 · ⑧ formSnapshot이 저장 payload 기준(공백·제외 행 무시) ·
 * save: basic 실패 / variants 실패 / changed 조합 3종 / failure 원본 보존.
 */
const DETAIL: SellerProductDetail = {
  productPublicId: 'prd_1', name: '반찬통', categoryId: 7, status: 'SALE', basePrice: 32000, soldoutManual: false,
  createdAt: '2026-09-17T17:29:23+09:00', updatedAt: '2026-09-17T17:29:23+09:00', images: [],
  optionGroups: [{ optionGroupId: 1, name: '색상', displayOrder: 0, values: [{ optionValueId: 11, value: '블랙', displayOrder: 0 }, { optionValueId: 12, value: '화이트', displayOrder: 1 }] }],
  variants: [{ variantPublicId: 'var_1', variantCode: 'BLK', additionalPrice: 500, status: 'SALE', soldoutManual: false, displayOrder: 0,
    options: [{ optionGroupId: 1, optionValueId: 11, value: '블랙' }], quantityOnHand: 10, quantityReserved: 2, quantityAvailable: 8 }],
}

function optionForm(): SellerProductForm {
  const form = createEmptySellerProductForm()
  form.categoryId = 7
  form.name = '주전자'
  form.basePrice = 45000
  form.hasOptions = true
  const groups: SellerFormOptionGroup[] = [
    { localId: 'g1', optionGroupId: null, name: '색상', values: [{ localId: 'o1', optionValueId: null, value: '블랙' }, { localId: 'o2', optionValueId: null, value: '실버' }] },
  ]
  form.optionGroups = groups
  form.variants = regenerateVariants(groups, []).map((variant) => ({ ...variant, variantCode: suggestVariantCode(variant, groups) }))
  return form
}

describe('⑦ 정수 검증', () => {
  it('isNonNegativeInteger: 0·정수 통과 · 소수·음수·NaN·Infinity 거부', () => {
    expect(isNonNegativeInteger(0)).toBe(true)
    expect(isNonNegativeInteger(12000)).toBe(true)
    for (const bad of [1.5, -1, Number.NaN, Number.POSITIVE_INFINITY, 0.1]) expect(isNonNegativeInteger(bad), String(bad)).toBe(false)
  })

  it('판매가·추가금·초기재고 각각 소수 입력 → 해당 필드 오류(정수 문구)', () => {
    const form = optionForm()
    form.basePrice = 45000.5
    form.variants[0]!.additionalPrice = 100.25
    form.variants[1]!.initialStock = 2.5
    const errors = validateSellerProductForm(form, 'create')
    expect(errors.basePrice).toBe('판매가는 0 이상의 정수여야 합니다.')
    expect(errors['variants.0.additionalPrice']).toBe('추가금은 0 이상의 정수.')
    expect(errors['variants.1.initialStock']).toBe('초기 재고는 0 이상의 정수.')
    form.basePrice = 45000
    form.variants[0]!.additionalPrice = 100
    form.variants[1]!.initialStock = 2
    expect(validateSellerProductForm(form, 'create')).toEqual({})
  })
})

describe('옵션 조합 중복·기존 variant 오염', () => {
  it('등록 모드에서 같은 조합 행을 직접 만들면 variants.N.combo 오류(두 번째 행)', () => {
    const form = optionForm()
    form.variants = [form.variants[0]!, { ...form.variants[0]!, localId: 'dup', variantCode: 'DUP' }]
    const errors = validateSellerProductForm(form, 'create')
    expect(errors['variants.1.combo']).toBe('같은 옵션 조합이 중복됩니다.')
    expect(errors['variants.0.combo']).toBeUndefined()
  })

  it('기존 variant의 initialStock·optionValueLocalIds를 오염시켜도 PUT variants 요청은 initialStock 0·options []', () => {
    const form = toSellerProductForm(DETAIL)
    const existing = form.variants.find((variant) => variant.variantPublicId === 'var_1')!
    existing.initialStock = 99
    existing.optionValueLocalIds = ['bogus']
    const row = toSellerVariantsRequest(form).variants.find((variant) => variant.variantPublicId === 'var_1')!
    expect(row.initialStock).toBe(0)
    expect(row.options).toEqual([])
  })
})

describe('⑧ formSnapshot — 저장 payload 기준', () => {
  it('상품명 앞뒤 공백·제외된 신규 행 입력·표시 전용 필드(status)는 dirty가 아니고, 저장에 실리는 변경(판매가·사용 토글·추가 체크)은 dirty', () => {
    const form = toSellerProductForm(DETAIL)
    const before = formSnapshot(form)
    form.name = `  ${form.name}  `
    expect(formSnapshot(form)).toBe(before)
    const addable = form.variants.find((variant) => variant.variantPublicId === null)!
    addable.variantCode = 'WHT'
    addable.initialStock = 5
    expect(formSnapshot(form)).toBe(before) // excluded=true → 요청에 안 실림
    form.status = 'STOPPED'
    expect(formSnapshot(form)).toBe(before)

    form.basePrice = 33000
    expect(formSnapshot(form)).not.toBe(before)
    form.basePrice = 32000
    expect(formSnapshot(form)).toBe(before)
    form.variants[0]!.enabled = false
    expect(formSnapshot(form)).not.toBe(before)
    form.variants[0]!.enabled = true
    addable.excluded = false
    expect(formSnapshot(form)).not.toBe(before)
  })

  it('등록 모드: 옵션값 추가·초기재고 변경은 등록 payload 변화라 dirty', () => {
    const form = optionForm()
    const before = formSnapshot(form)
    form.variants[0]!.initialStock = 3
    expect(formSnapshot(form)).not.toBe(before)
    expect(toSellerCreateRequest(form).variants[0]?.initialStock).toBe(3)
  })
})

describe('saveSellerProduct — 실패 단계·changed 조합·failure 보존', () => {
  const FAILURE = { status: 403, data: { code: 'SELLER_SUSPENDED', detail: '정지' } }

  function api(overrides: Partial<SellerSaveApi> = {}): SellerSaveApi & { calls: string[] } {
    const calls: string[] = []
    const wrap = <T extends (...args: never[]) => Promise<unknown>>(name: string, fn: T) =>
      (async (...args: Parameters<T>) => { calls.push(name); return fn(...args) }) as T
    return {
      calls,
      create: wrap('create', overrides.create ?? vi.fn().mockResolvedValue({ productPublicId: 'prd_new', variantPublicIds: [] })),
      update: wrap('basic', overrides.update ?? vi.fn().mockResolvedValue({})),
      replaceImages: wrap('images', overrides.replaceImages ?? vi.fn().mockResolvedValue({})),
      replaceVariants: wrap('variants', overrides.replaceVariants ?? vi.fn().mockResolvedValue({})),
    }
  }
  const edit = { ...toSellerProductForm(DETAIL), productPublicId: 'prd_1' }

  it('basic 실패 → step basic·완료 []·images/variants 미호출·failure 원본 객체 그대로', async () => {
    const failing = api({ update: vi.fn().mockRejectedValue(FAILURE) })
    const error = await saveSellerProduct(failing, edit, 'edit').catch((caught: unknown) => caught) as SellerSaveStepError
    expect(error).toBeInstanceOf(SellerSaveStepError)
    expect(error.step).toBe('basic')
    expect(error.completedSteps).toEqual([])
    expect(error.failure).toBe(FAILURE)
    expect(failing.calls).toEqual(['basic'])
  })

  it('variants 실패 → step variants·완료 [basic, images]·failure 원본 보존', async () => {
    const failing = api({ replaceVariants: vi.fn().mockRejectedValue(FAILURE) })
    const error = await saveSellerProduct(failing, edit, 'edit').catch((caught: unknown) => caught) as SellerSaveStepError
    expect(error.step).toBe('variants')
    expect(error.completedSteps).toEqual(['basic', 'images'])
    expect(error.failure).toBe(FAILURE)
    expect(failing.calls).toEqual(['basic', 'images', 'variants'])
  })

  it('changed 조합 3종: basic만 / images만 / basic+variants(images 생략)', async () => {
    const a = api()
    expect((await saveSellerProduct(a, edit, 'edit', { basic: true, images: false, variants: false })).steps).toEqual(['basic'])
    expect(a.calls).toEqual(['basic'])
    const b = api()
    expect((await saveSellerProduct(b, edit, 'edit', { basic: false, images: true, variants: false })).steps).toEqual(['images'])
    expect(b.calls).toEqual(['images'])
    const c = api()
    expect((await saveSellerProduct(c, edit, 'edit', { basic: true, images: false, variants: true })).steps).toEqual(['basic', 'variants'])
    expect(c.calls).toEqual(['basic', 'variants'])
  })
})
