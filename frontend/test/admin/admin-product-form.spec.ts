import { describe, it, expect, vi } from 'vitest'
import type { AdminProductDetail } from '#layers/admin/app/types/admin-product'
import type { ProductForm, ProductFormOptionGroup } from '#layers/admin/app/types/admin-product-form'
import {
  cartesian,
  detailToForm,
  emptyForm,
  ensureMainImage,
  formSnapshot,
  isoToLocalInput,
  localInputToIso,
  mapFieldErrors,
  regenerateVariants,
  stockAdjustments,
  toCreateRequest,
  toImagesRequest,
  toUpdateRequest,
  toVariantsRequest,
  validateForm,
  variantLabel,
} from '#layers/admin/app/lib/admin-product-form'
import { SaveStepError, saveProduct, type SaveApi } from '#layers/admin/app/lib/admin-product-save'
import { precheckFiles, uploadItemFailureMessage, uploadRequestFailureMessage } from '#layers/admin/app/lib/admin-image-upload'
import { resolveBackPath } from '#layers/admin/app/lib/admin-back-path'

// FE-26: 폼 변환·검증·조합·저장 오케스트레이션·업로드 메시지·복귀 경로 순수 함수.

const DETAIL: AdminProductDetail = {
  productPublicId: 'prd_1', name: '옵션 티셔츠', description: '설명', categoryId: 1, categoryName: '데모', sellerPublicId: 'slr_1', sellerName: '셀러',
  status: 'SALE', soldOutManual: false, basePrice: 25000, supplyPrice: 15000, thumbnailUrl: '/api/v1/files/a_thumb.jpg',
  saleStartAt: '2026-09-01T09:00:00+09:00',
  images: [
    { imageId: 11, imageUrl: '/api/v1/files/b.jpg', imageType: 'DETAIL', displayOrder: 1, main: false },
    { imageId: 10, imageUrl: '/api/v1/files/a.jpg', imageType: 'GALLERY', displayOrder: 0, main: true },
  ],
  optionGroups: [
    { optionGroupId: 2, name: '사이즈', displayOrder: 1, values: [{ optionValueId: 21, value: 'M', displayOrder: 0 }, { optionValueId: 22, value: 'L', displayOrder: 1 }] },
    { optionGroupId: 1, name: '색상', displayOrder: 0, values: [{ optionValueId: 12, value: '화이트', displayOrder: 1 }, { optionValueId: 11, value: '블랙', displayOrder: 0 }] },
  ],
  variants: [
    { variantPublicId: 'var_1', variantCode: 'BLK-M', additionalPrice: 0, status: 'SALE', soldOutManual: false, displayOrder: 0, quantityAvailable: 50, quantityOnHand: 50,
      options: [{ optionGroupId: 1, groupName: '색상', optionValueId: 11, value: '블랙' }, { optionGroupId: 2, groupName: '사이즈', optionValueId: 21, value: 'M' }] },
    { variantPublicId: 'var_2', variantCode: 'WHT-L', additionalPrice: 1000, status: 'STOPPED', soldOutManual: true, displayOrder: 1, quantityAvailable: 3, quantityOnHand: 3,
      options: [{ optionGroupId: 1, groupName: '색상', optionValueId: 12, value: '화이트' }, { optionGroupId: 2, groupName: '사이즈', optionValueId: 22, value: 'L' }] },
  ],
}

describe('시각 변환', () => {
  it('ISO(+09:00) ↔ datetime-local', () => {
    expect(isoToLocalInput('2026-09-01T09:00:00+09:00')).toBe('2026-09-01T09:00')
    expect(isoToLocalInput(undefined)).toBe('')
    expect(localInputToIso('2026-09-01T09:00')).toBe('2026-09-01T09:00:00+09:00')
    expect(localInputToIso('')).toBeNull()
  })
})

describe('detailToForm', () => {
  it('그룹/값/이미지를 displayOrder로 정렬하고 variant 옵션을 그룹 순서의 localId로 매핑·재고는 서버값 보존·상태→enabled', () => {
    const form = detailToForm(DETAIL)
    expect(form.optionGroups.map((g) => g.name)).toEqual(['색상', '사이즈'])
    expect(form.optionGroups[0]?.values.map((v) => v.value)).toEqual(['블랙', '화이트'])
    expect(form.images.map((i) => i.imageType)).toEqual(['GALLERY', 'DETAIL'])
    expect(form.images[0]?.main).toBe(true)
    expect(form.hasOptions).toBe(true)
    expect(form.saleStartAt).toBe('2026-09-01T09:00')
    expect(form.noEndDate).toBe(true)
    const second = form.variants[1]
    expect(second?.variantPublicId).toBe('var_2')
    expect(second?.enabled).toBe(false)
    expect(second?.soldOutManual).toBe(true)
    expect(second?.stockOnServer).toBe(3)
    expect(variantLabel(second as NonNullable<typeof second>, form.optionGroups)).toBe('화이트 / L')
    // D-206: SALE 상세는 saleStopSource 부재(NON_NULL) → null, STOPPED 상세는 주체 보존.
    expect(form.saleStopSource).toBeNull()
    expect(detailToForm({ ...DETAIL, status: 'STOPPED', saleStopSource: 'SELLER' }).saleStopSource).toBe('SELLER')
  })

  it('옵션 없는 상세는 hasOptions=false·단일 variant', () => {
    const form = detailToForm({ ...DETAIL, optionGroups: [], variants: [{ ...DETAIL.variants[0]!, options: [] }] })
    expect(form.hasOptions).toBe(false)
    expect(form.variants[0]?.optionValueLocalIds).toEqual([])
    expect(variantLabel(form.variants[0]!, [])).toBe('단일 상품')
  })
})

describe('조합 생성·diff', () => {
  const groups: ProductFormOptionGroup[] = [
    { localId: 'g1', optionGroupId: null, name: '색상', values: [{ localId: 'c1', optionValueId: null, value: '블랙' }, { localId: 'c2', optionValueId: null, value: '화이트' }] },
    { localId: 'g2', optionGroupId: null, name: '사이즈', values: [{ localId: 's1', optionValueId: null, value: 'M' }] },
  ]

  it('데카르트 곱은 그룹 순서·값이 없는 그룹이 있으면 빈 배열', () => {
    expect(cartesian(groups)).toEqual([['c1', 's1'], ['c2', 's1']])
    expect(cartesian([groups[0]!, { ...groups[1]!, values: [] }])).toEqual([])
  })

  it('재생성 시 같은 조합의 기존 행은 입력값 유지·새 조합은 기본 행·사라진 서버 variant는 removed', () => {
    const current = regenerateVariants(groups, [])
    expect(current).toHaveLength(2)
    current[0]!.stock = 7
    current[0]!.variantPublicId = 'var_keep'
    current[1]!.variantPublicId = 'var_gone'
    const narrowed: ProductFormOptionGroup[] = [{ ...groups[0]!, values: [groups[0]!.values[0]!, { localId: 'c3', optionValueId: null, value: '레드' }] }, groups[1]!]
    const next = regenerateVariants(narrowed, current)
    expect(next.filter((v) => !v.removed)).toHaveLength(2)
    expect(next.find((v) => v.optionValueLocalIds.join('|') === 'c1|s1')?.stock).toBe(7)
    expect(next.find((v) => v.optionValueLocalIds.join('|') === 'c3|s1')?.variantPublicId).toBeNull()
    expect(next.find((v) => v.variantPublicId === 'var_gone')?.removed).toBe(true)
  })
})

describe('validateForm', () => {
  it('필수·음수·판매기간·옵션 중복·조합 수 한도', () => {
    const form = emptyForm()
    const errors = validateForm(form, 'create')
    expect(Object.keys(errors)).toEqual(expect.arrayContaining(['sellerPublicId', 'categoryId', 'name', 'basePrice']))

    const filled: ProductForm = { ...emptyForm(), sellerPublicId: 's', categoryId: 1, name: 'x', basePrice: -1, supplyPrice: -5,
      noEndDate: false, saleStartAt: '2026-09-02T00:00', saleEndAt: '2026-09-01T00:00' }
    const errors2 = validateForm(filled, 'create')
    expect(errors2.basePrice).toContain('0 이상')
    expect(errors2.supplyPrice).toContain('0 이상')
    expect(errors2.saleEndAt).toContain('뒤여야')

    const dup: ProductForm = { ...filled, basePrice: 1000, supplyPrice: null, noEndDate: true, hasOptions: true,
      optionGroups: [
        { localId: 'g1', optionGroupId: null, name: '색상', values: [{ localId: 'a', optionValueId: null, value: '블랙' }, { localId: 'b', optionValueId: null, value: '블랙' }] },
        { localId: 'g2', optionGroupId: null, name: '색상', values: [] },
      ], variants: [] }
    const errors3 = validateForm(dup, 'create')
    expect(errors3['optionGroups.0.values']).toContain('중복')
    expect(errors3['optionGroups.1.name']).toContain('중복')
    expect(errors3['optionGroups.1.values']).toContain('1개 이상')

    const edit = validateForm({ ...emptyForm(), categoryId: 1, name: 'ok', basePrice: 0 }, 'edit')
    expect(edit).toEqual({})
  })
})

describe('폼 → 요청', () => {
  it('등록 요청: tempKey=localId·optionKeys·initialStock·대표 썸네일·판매기간 ISO', () => {
    const form = emptyForm()
    form.sellerPublicId = 'slr_1'; form.categoryId = 3; form.name = ' 새 상품 '; form.basePrice = 1000; form.supplyPrice = null
    form.saleStartAt = '2026-09-01T09:00'; form.noEndDate = false; form.saleEndAt = '2026-12-31T00:00'
    form.hasOptions = true
    form.optionGroups = [{ localId: 'g1', optionGroupId: null, name: '색상', values: [{ localId: 'c1', optionValueId: null, value: '블랙' }] }]
    form.variants = regenerateVariants(form.optionGroups, [])
    form.variants[0]!.variantCode = 'BLK'; form.variants[0]!.stock = 5
    form.images = ensureMainImage([
      { localId: 'i1', imageId: null, imageUrl: '/api/v1/files/x.jpg', thumbnailUrl: '/api/v1/files/x_thumb.jpg', imageType: 'GALLERY', main: false },
    ])
    const body = toCreateRequest(form)
    expect(body.name).toBe('새 상품')
    expect(body.thumbnailUrl).toBe('/api/v1/files/x_thumb.jpg')
    expect(body.saleStartAt).toBe('2026-09-01T09:00:00+09:00')
    expect(body.saleEndAt).toBe('2026-12-31T00:00:00+09:00')
    expect(body.optionGroups[0]?.values[0]).toEqual({ key: 'c1', value: '블랙', displayOrder: 0 })
    expect(body.variants[0]).toMatchObject({ variantCode: 'BLK', initialStock: 5, optionKeys: ['c1'], displayOrder: 0 })
  })

  it('images 요청은 갤러리 먼저·상세 다음 순서·대표는 갤러리만 / variants 요청은 기존 메타·신규 options·removed 제외 / 재고 delta', () => {
    const form = detailToForm(DETAIL)
    form.images = [...form.images].reverse() // DETAIL, GALLERY 순으로 섞여도
    const images = toImagesRequest(form)
    expect(images.images.map((i) => i.imageType)).toEqual(['GALLERY', 'DETAIL'])
    expect(images.images[0]).toMatchObject({ imageId: 10, main: true })

    // 값 '레드' 추가 → 재생성: 기존 2개(블랙/M·화이트/L) 유지 + 누락 조합(블랙/L·화이트/M) + 신규 값 조합(레드/M·레드/L) = 신규 4개
    form.optionGroups[0]!.values.push({ localId: 'new-red', optionValueId: null, value: '레드' })
    form.variants = regenerateVariants(form.optionGroups, form.variants)
    form.variants.filter((v) => v.variantPublicId === null).forEach((v) => { v.variantCode = 'NEW'; v.stock = 2 })
    form.variants.find((v) => v.variantPublicId === 'var_1')!.stock = 60
    const variants = toVariantsRequest(form)
    const existing = variants.variants.find((v) => v.variantPublicId === 'var_1')
    expect(existing).toMatchObject({ status: 'SALE', soldoutManual: false, initialStock: 0, options: [] })
    const created = variants.variants.filter((v) => v.variantPublicId === null)
    expect(created).toHaveLength(4)
    const red = created.find((v) => v.options[0]?.value === '레드' && v.options[1]?.value === 'M')
    expect(red?.options).toEqual([{ optionGroupId: 1, value: '레드' }, { optionGroupId: 2, value: 'M' }])
    expect(red?.initialStock).toBe(2)
    expect(stockAdjustments(form)).toEqual([{ variantPublicId: 'var_1', delta: 10 }])
    expect(toUpdateRequest(form).thumbnailUrl).toBe('/api/v1/files/a.jpg')
  })

  it('대표 자동 지정: 갤러리 대표 없음 → 첫 갤러리·상세는 대표 불가', () => {
    const images = ensureMainImage([
      { localId: 'd', imageId: null, imageUrl: 'd', thumbnailUrl: 'd', imageType: 'DETAIL', main: true },
      { localId: 'g', imageId: null, imageUrl: 'g', thumbnailUrl: 'g', imageType: 'GALLERY', main: false },
    ])
    expect(images.find((i) => i.localId === 'g')?.main).toBe(true)
    expect(images.find((i) => i.localId === 'd')?.main).toBe(false)
  })

  it('fieldErrors 매핑·스냅샷은 localId를 무시', () => {
    expect(mapFieldErrors([{ field: 'variants[0].variantCode', message: 'x' }])).toEqual({ 'variants.0.variantCode': 'x' })
    const a = emptyForm(); const b = emptyForm()
    expect(formSnapshot(a)).toBe(formSnapshot(b))
  })
})

describe('saveProduct 오케스트레이션', () => {
  function api(overrides: Partial<SaveApi> = {}): SaveApi & { calls: string[] } {
    const calls: string[] = []
    return {
      calls,
      create: vi.fn(async () => { calls.push('create'); return { productPublicId: 'prd_new', variantPublicIds: ['var_a'] } }),
      update: vi.fn(async () => { calls.push('basic'); return DETAIL }),
      replaceImages: vi.fn(async () => { calls.push('images'); return DETAIL }),
      replaceVariants: vi.fn(async () => { calls.push('variants'); return DETAIL }),
      adjustStock: vi.fn(async () => { calls.push('stock') }),
      ...overrides,
    }
  }

  it('등록: create → images(있을 때) → variants(상태/품절이 기본과 다를 때만)', async () => {
    const form = emptyForm()
    form.sellerPublicId = 's'; form.categoryId = 1; form.name = 'n'; form.basePrice = 1
    const plain = api()
    expect((await saveProduct(plain, form, 'create')).steps).toEqual(['create'])

    form.images = [{ localId: 'i', imageId: null, imageUrl: 'u', thumbnailUrl: 'u', imageType: 'GALLERY', main: true }]
    form.variants[0]!.soldOutManual = true
    const withExtras = api()
    const result = await saveProduct(withExtras, form, 'create')
    expect(withExtras.calls).toEqual(['create', 'images', 'variants'])
    expect(result.productPublicId).toBe('prd_new')
    const variantsBody = (withExtras.replaceVariants as ReturnType<typeof vi.fn>).mock.calls[0]?.[1] as { variants: { variantPublicId: string | null }[] }
    expect(variantsBody.variants[0]?.variantPublicId).toBe('var_a')
  })

  it('등록 2단계(images) 실패 → SaveStepError(step=images·productPublicId 확보)·이후 단계 미호출', async () => {
    const form = emptyForm()
    form.images = [{ localId: 'i', imageId: null, imageUrl: 'u', thumbnailUrl: 'u', imageType: 'GALLERY', main: true }]
    form.variants[0]!.enabled = false
    const failing = api({ replaceImages: vi.fn(async () => { throw { data: { code: 'INTERNAL_ERROR' } } }) })
    await expect(saveProduct(failing, form, 'create')).rejects.toSatisfy((error: unknown) =>
      error instanceof SaveStepError && error.step === 'images' && error.productPublicId === 'prd_new')
    expect(failing.replaceVariants).not.toHaveBeenCalled()
  })

  it('수정: basic → images → variants → stock(delta별) / 1단계 실패 시 즉시 중단', async () => {
    const form = detailToForm(DETAIL)
    form.variants[0]!.stock = 55
    const ok = api()
    expect((await saveProduct(ok, form, 'edit')).steps).toEqual(['basic', 'images', 'variants', 'stock'])
    expect(ok.adjustStock).toHaveBeenCalledWith('var_1', 5, expect.any(String))

    const failing = api({ update: vi.fn(async () => { throw new Error('boom') }) })
    await expect(saveProduct(failing, form, 'edit')).rejects.toMatchObject({ step: 'basic', productPublicId: 'prd_1' })
    expect(failing.replaceImages).not.toHaveBeenCalled()
  })
})

describe('업로드 사전 검증·메시지', () => {
  it('형식·10MB·20장 한도', () => {
    const ok = new File([new Uint8Array(10)], 'a.png', { type: 'image/png' })
    const gif = new File([new Uint8Array(10)], 'a.gif', { type: 'image/gif' })
    const big = new File([new Uint8Array(10 * 1024 * 1024 + 1)], 'b.jpg', { type: 'image/jpeg' })
    const result = precheckFiles([ok, gif, big], 19)
    expect(result.accepted).toEqual([ok])
    expect(result.rejected.map((r) => r.reason)).toEqual(['jpg·png·webp만 업로드할 수 있습니다.', '파일당 10MB를 초과합니다.'])
    expect(precheckFiles([ok], 20).rejected[0]?.reason).toContain('최대 20장')
  })

  it('413은 본문 없이도 용량 초과 안내·항목 코드 매핑·409/기타 폴백', () => {
    expect(uploadRequestFailureMessage({ status: 413 })).toContain('용량 초과')
    expect(uploadRequestFailureMessage({ statusCode: 413, data: null })).toContain('용량 초과')
    expect(uploadRequestFailureMessage({ status: 400, data: { code: 'MALFORMED_REQUEST' } })).toContain('장수 초과')
    expect(uploadItemFailureMessage('UNSUPPORTED_FORMAT', 'x')).toContain('형식 불일치')
    expect(uploadItemFailureMessage('IMAGE_TOO_LARGE', 'x')).toBe('이미지 해상도가 너무 큽니다. 한 변 8,000px 이하로 줄여 주세요.')
    expect(uploadItemFailureMessage(undefined, undefined)).toContain('실패')
  })
})

describe('resolveBackPath', () => {
  it('/admin/products(쿼리 포함)만 허용·그 외 기본 목록', () => {
    expect(resolveBackPath('/admin/products?status=SALE&page=2')).toBe('/admin/products?status=SALE&page=2')
    expect(resolveBackPath('/admin/products')).toBe('/admin/products')
    expect(resolveBackPath('/admin/orders')).toBe('/admin/products')
    expect(resolveBackPath('https://evil.example/')).toBe('/admin/products')
    expect(resolveBackPath(undefined)).toBe('/admin/products')
  })
})

describe('신규 조합 제외(FE-26 보강)', () => {
  function optionForm(): ProductForm {
    const form = emptyForm()
    form.sellerPublicId = 's'; form.categoryId = 1; form.name = 'n'; form.basePrice = 100; form.hasOptions = true
    form.optionGroups = [{ localId: 'g1', optionGroupId: null, name: '색상', values: [
      { localId: 'c1', optionValueId: null, value: '블랙' }, { localId: 'c2', optionValueId: null, value: '화이트' }] }]
    form.variants = regenerateVariants(form.optionGroups, []).map((v) => ({ ...v, variantCode: 'X' }))
    return form
  }

  it('제외 행은 등록 요청·variants 요청·재고 delta·등록 후 variant 매핑에서 빠진다', async () => {
    const form = optionForm()
    form.variants[1]!.excluded = true
    expect(toCreateRequest(form).variants).toHaveLength(1)
    expect(toCreateRequest(form).variants[0]?.optionKeys).toEqual(['c1'])
    expect(toVariantsRequest(form).variants).toHaveLength(1)
    expect(validateForm(form, 'create')).toEqual({})
    // 등록 응답 variantPublicIds는 포함 행 순서와 1:1 → 제외 행을 건너뛰고 매핑된다.
    form.variants[0]!.soldOutManual = true
    const replaceVariants = vi.fn(async () => DETAIL)
    await saveProduct({
      create: vi.fn(async () => ({ productPublicId: 'p', variantPublicIds: ['var_only'] })),
      update: vi.fn(), replaceImages: vi.fn(), replaceVariants, adjustStock: vi.fn(),
    } as unknown as SaveApi, form, 'create')
    const body = replaceVariants.mock.calls[0]?.[1] as unknown as { variants: { variantPublicId: string | null }[] }
    expect(body.variants.map((v) => v.variantPublicId)).toEqual(['var_only'])
  })

  it('전 행 제외 → 검증 에러(최소 1개 조합) / 수정 시 기존 행이 남아 있으면 통과', () => {
    const form = optionForm()
    form.variants.forEach((v) => { v.excluded = true })
    expect(validateForm(form, 'create').variants).toContain('최소 1개')
    const edit = detailToForm(DETAIL)
    edit.optionGroups[0]!.values.push({ localId: 'new', optionValueId: null, value: '레드' })
    edit.variants = regenerateVariants(edit.optionGroups, edit.variants)
    edit.variants.filter((v) => v.variantPublicId === null).forEach((v) => { v.excluded = true })
    expect(validateForm(edit, 'edit').variants).toBeUndefined()
    expect(toVariantsRequest(edit).variants.every((v) => v.variantPublicId !== null)).toBe(true)
  })

  it('기존(서버) 행은 excluded=false 고정으로 변환된다(제외 체크 대상 아님)', () => {
    const form = detailToForm(DETAIL)
    expect(form.variants.every((v) => v.variantPublicId !== null && v.excluded === false)).toBe(true)
  })
})
