import { describe, it, expect } from 'vitest'
import type { SellerProductDetail } from '#layers/seller/app/types/seller-product'
import type { SellerFormOptionGroup, SellerProductForm } from '#layers/seller/app/types/seller-product-form'
import {
  cartesian,
  changedSections,
  conflictErrors,
  createEmptySellerProductForm,
  ensureMainImage,
  includedVariants,
  mapFormFieldErrors,
  regenerateVariants,
  sectionSnapshots,
  suggestVariantCode,
  toSellerCreateRequest,
  toSellerImagesRequest,
  toSellerProductForm,
  toSellerUpdateRequest,
  toSellerVariantsRequest,
  validateSellerProductForm,
  variantLabel,
} from '#layers/seller/app/lib/seller-product-form'

/**
 * 셀러 상품 폼 순수 함수(Track 90-C-4): 상세→폼(옵션 잠금·재고 읽기 전용·추가 가능 조합)·조합 생성·검증 규칙·폼→요청(등록/기본정보/이미지/variants)·
 * 섹션 dirty·409 행 매핑. BE 계약(셀러 지정·공급가·판매기간·상태 없음 / 기존 재고 delta 없음 / 삭제 없음)이 요청 본문에 드러나는지 고정한다.
 */
const DETAIL: SellerProductDetail = {
  productPublicId: 'prd_1',
  name: '반찬통',
  description: '설명',
  categoryId: 7,
  categoryName: '주방',
  status: 'SALE',
  basePrice: 32000,
  thumbnailUrl: '/api/v1/files/products/a.jpg',
  soldoutManual: false,
  createdAt: '2026-09-17T17:29:23+09:00',
  updatedAt: '2026-09-17T17:29:23+09:00',
  images: [
    { imageId: 12, imageUrl: '/api/v1/files/products/b.jpg', imageType: 'DETAIL', displayOrder: 1, main: false },
    { imageId: 11, imageUrl: '/api/v1/files/products/a.jpg', imageType: 'GALLERY', displayOrder: 0, main: true },
  ],
  optionGroups: [
    { optionGroupId: 2, name: '사이즈', displayOrder: 1, values: [{ optionValueId: 21, value: 'M', displayOrder: 0 }] },
    { optionGroupId: 1, name: '색상', displayOrder: 0, values: [{ optionValueId: 12, value: '화이트', displayOrder: 1 }, { optionValueId: 11, value: '블랙', displayOrder: 0 }] },
  ],
  variants: [
    { variantPublicId: 'var_1', variantCode: 'BLK-M', sellerSku: 'SKU-1', additionalPrice: 500, status: 'SALE', soldoutManual: false, displayOrder: 0,
      options: [{ optionGroupId: 1, optionValueId: 11, value: '블랙' }, { optionGroupId: 2, optionValueId: 21, value: 'M' }], quantityOnHand: 10, quantityReserved: 2, quantityAvailable: 8 },
  ],
}

function optionForm(): SellerProductForm {
  const form = createEmptySellerProductForm()
  form.categoryId = 7
  form.name = ' 주전자 '
  form.basePrice = 45000
  form.hasOptions = true
  const groups: SellerFormOptionGroup[] = [
    { localId: 'g1', optionGroupId: null, name: '색상', values: [{ localId: 'o1', optionValueId: null, value: '블랙' }, { localId: 'o2', optionValueId: null, value: '실버' }] },
    { localId: 'g2', optionGroupId: null, name: '용량', values: [{ localId: 'o3', optionValueId: null, value: '1.7L' }] },
  ]
  form.optionGroups = groups
  form.variants = regenerateVariants(groups, []).map((variant) => ({ ...variant, variantCode: suggestVariantCode(variant, groups), initialStock: 3 }))
  return form
}

describe('createEmptySellerProductForm · 상세→폼', () => {
  it('빈 폼: 단일 variant 1행(DEFAULT·초기재고 0)·이미지 없음·셀러/공급가/판매기간 필드 자체가 없다', () => {
    const form = createEmptySellerProductForm()
    expect(form.variants).toHaveLength(1)
    expect(form.variants[0]?.variantCode).toBe('DEFAULT')
    expect(form.variants[0]?.stockOnServer).toBeNull()
    expect(form).not.toHaveProperty('sellerPublicId')
    expect(form).not.toHaveProperty('supplyPrice')
    expect(form).not.toHaveProperty('saleStartAt')
  })

  it('상세→폼: 그룹·값 displayOrder 정렬·서버 id 보존 / 기존 variant 재고는 stockOnServer(읽기 전용)·enabled=SALE / 없는 조합(화이트·M)은 추가 가능 신규 행(excluded)', () => {
    const form = toSellerProductForm(DETAIL)
    expect(form.optionGroups.map((group) => group.name)).toEqual(['색상', '사이즈'])
    expect(form.optionGroups[0]?.values.map((value) => value.value)).toEqual(['블랙', '화이트'])
    expect(form.optionGroups[0]?.optionGroupId).toBe(1)
    expect(form.images.map((image) => image.imageId)).toEqual([11, 12])
    expect(form.status).toBe('SALE')

    const existing = form.variants.filter((variant) => variant.variantPublicId !== null)
    const addable = form.variants.filter((variant) => variant.variantPublicId === null)
    expect(existing).toHaveLength(1)
    expect(existing[0]?.stockOnServer).toEqual({ onHand: 10, reserved: 2, available: 8 })
    expect(existing[0]?.enabled).toBe(true)
    expect(existing[0]?.sellerSku).toBe('SKU-1')
    expect(variantLabel(existing[0]!, form.optionGroups)).toBe('블랙 / M')
    expect(addable).toHaveLength(1)
    expect(addable[0]?.excluded).toBe(true)
    expect(variantLabel(addable[0]!, form.optionGroups)).toBe('화이트 / M')
    expect(includedVariants(form)).toHaveLength(1)
  })
})

describe('조합·이미지', () => {
  it('cartesian: 그룹 순서 유지·빈 그룹이면 조합 없음 / regenerateVariants: 같은 조합 입력 유지·사라진 조합 폐기', () => {
    const groups = optionForm().optionGroups
    expect(cartesian(groups).map((combo) => combo.join('|'))).toEqual(['o1|o3', 'o2|o3'])
    expect(cartesian([{ localId: 'g', optionGroupId: null, name: 'x', values: [] }])).toEqual([])
    const current = regenerateVariants(groups, []).map((variant, index) => ({ ...variant, variantCode: `C${index}` }))
    const smaller: SellerFormOptionGroup[] = [{ ...groups[0]!, values: [groups[0]!.values[0]!] }, groups[1]!]
    const next = regenerateVariants(smaller, current)
    expect(next).toHaveLength(1)
    expect(next[0]?.variantCode).toBe('C0')
  })

  it('ensureMainImage: 갤러리 대표 없으면 첫 갤러리·DETAIL은 대표 불가·대표는 1장', () => {
    const images = [
      { localId: 'a', imageId: null, imageUrl: '/d', thumbnailUrl: '/d', imageType: 'DETAIL' as const, main: true },
      { localId: 'b', imageId: null, imageUrl: '/g1', thumbnailUrl: '/g1', imageType: 'GALLERY' as const, main: false },
      { localId: 'c', imageId: null, imageUrl: '/g2', thumbnailUrl: '/g2', imageType: 'GALLERY' as const, main: false },
    ]
    expect(ensureMainImage(images).map((image) => image.main)).toEqual([false, true, false])
  })
})

describe('validateSellerProductForm', () => {
  it('필수: 카테고리·상품명·기본가 / 옵션 상품: 그룹 1~3·그룹명 중복·값 없음·값 중복 / 조합: 코드 필수·추가금·초기재고 0 이상', () => {
    const empty = createEmptySellerProductForm()
    const errors = validateSellerProductForm(empty, 'create')
    expect(errors).toMatchObject({ categoryId: expect.any(String), name: expect.any(String), basePrice: expect.any(String) })

    const form = optionForm()
    expect(validateSellerProductForm(form, 'create')).toEqual({})
    form.optionGroups[1]!.name = '색상'
    form.optionGroups[0]!.values.push({ localId: 'o9', optionValueId: null, value: '블랙' })
    form.variants[0]!.variantCode = ''
    form.variants[0]!.initialStock = -1
    form.variants[1]!.additionalPrice = -5
    const invalid = validateSellerProductForm(form, 'create')
    expect(invalid['optionGroups.1.name']).toBe('그룹명이 중복됩니다.')
    expect(invalid['optionGroups.0.values']).toContain('중복')
    expect(invalid['variants.0.variantCode']).toBe('코드를 입력하세요.')
    expect(invalid['variants.0.initialStock']).toBe('초기 재고는 0 이상.')
    expect(invalid['variants.1.additionalPrice']).toBe('추가금은 0 이상.')

    const four = optionForm()
    four.optionGroups = [...four.optionGroups, { localId: 'g3', optionGroupId: null, name: 'a', values: [{ localId: 'x', optionValueId: null, value: '1' }] },
      { localId: 'g4', optionGroupId: null, name: 'b', values: [{ localId: 'y', optionValueId: null, value: '2' }] }]
    expect(validateSellerProductForm(four, 'create').optionGroups).toContain('최대 3개')
  })

  it('전부 제외되면 variants 에러 · 수정 모드는 기존 행이 있으면 통과·기존 행 초기재고는 검증하지 않는다', () => {
    const form = optionForm()
    form.variants.forEach((variant) => { variant.excluded = true })
    expect(validateSellerProductForm(form, 'create').variants).toContain('전부 제외')
    const edit = toSellerProductForm(DETAIL)
    expect(validateSellerProductForm(edit, 'edit')).toEqual({})
  })
})

describe('폼 → 요청', () => {
  it('등록 요청: 셀러/공급가/판매기간 없음·trim·옵션 key=localId·variants 순서=displayOrder·optionKeys·initialStock·제외 행 빠짐', () => {
    const form = optionForm()
    form.variants[1]!.excluded = true
    const body = toSellerCreateRequest(form)
    expect(body).not.toHaveProperty('sellerPublicId')
    expect(body).not.toHaveProperty('supplyPrice')
    expect(body).not.toHaveProperty('saleStartAt')
    expect(body.name).toBe('주전자')
    expect(body.description).toBeNull()
    expect(body.optionGroups).toEqual([
      { name: '색상', displayOrder: 0, values: [{ key: 'o1', value: '블랙', displayOrder: 0 }, { key: 'o2', value: '실버', displayOrder: 1 }] },
      { name: '용량', displayOrder: 1, values: [{ key: 'o3', value: '1.7L', displayOrder: 0 }] },
    ])
    expect(body.variants).toEqual([
      { variantCode: '블랙-1.7L', sellerSku: null, barcode: null, additionalPrice: 0, displayOrder: 0, initialStock: 3, optionKeys: ['o1', 'o3'] },
    ])
    // 단일 상품은 optionGroups 빈 배열·optionKeys 빈 배열·variant 1개(BE 단순상품 규약)
    const single = createEmptySellerProductForm()
    single.categoryId = 1; single.name = 'x'; single.basePrice = 1
    expect(toSellerCreateRequest(single).optionGroups).toEqual([])
    expect(toSellerCreateRequest(single).variants).toEqual([{ variantCode: 'DEFAULT', sellerSku: null, barcode: null, additionalPrice: 0, displayOrder: 0, initialStock: 0, optionKeys: [] }])
  })

  it('기본정보 요청은 4필드만 · 이미지 요청은 갤러리 먼저·DETAIL 대표 불가 · variants 요청은 기존=메타(옵션 빈·initialStock 0)·신규=options(그룹 id·값)·HIDDEN', () => {
    const form = toSellerProductForm(DETAIL)
    form.name = '반찬통 v2'
    expect(toSellerUpdateRequest(form)).toEqual({ categoryId: 7, name: '반찬통 v2', description: '설명', basePrice: 32000 })
    expect(toSellerImagesRequest(form).images.map((image) => [image.imageId, image.imageType, image.main])).toEqual([[11, 'GALLERY', true], [12, 'DETAIL', false]])

    const existing = form.variants.find((variant) => variant.variantPublicId === 'var_1')!
    existing.enabled = false
    const addable = form.variants.find((variant) => variant.variantPublicId === null)!
    addable.excluded = false
    addable.variantCode = 'WHT-M'
    addable.initialStock = 4
    const body = toSellerVariantsRequest(form)
    expect(body.variants).toEqual([
      { variantPublicId: 'var_1', variantCode: 'BLK-M', sellerSku: 'SKU-1', barcode: null, additionalPrice: 500, status: 'HIDDEN', soldoutManual: false, displayOrder: 0, initialStock: 0, options: [] },
      { variantPublicId: null, variantCode: 'WHT-M', sellerSku: null, barcode: null, additionalPrice: 0, status: 'SALE', soldoutManual: false, displayOrder: 1, initialStock: 4,
        options: [{ optionGroupId: 1, value: '화이트' }, { optionGroupId: 2, value: 'M' }] },
    ])
    // 기존 variant 재고 delta 개념이 없다(관리자 stockAdjustments 부재)
    expect(body.variants[0]).not.toHaveProperty('quantityAvailable')
  })
})

describe('섹션 dirty · 에러 매핑', () => {
  it('changedSections: 바뀐 섹션만 true(기본정보/이미지/variants 독립)', () => {
    const form = toSellerProductForm(DETAIL)
    const initial = sectionSnapshots(form)
    expect(changedSections(initial, form)).toEqual({ basic: false, images: false, variants: false })
    form.basePrice = 33000
    expect(changedSections(initial, form)).toEqual({ basic: true, images: false, variants: false })
    form.basePrice = 32000
    form.variants[0]!.enabled = false
    expect(changedSections(initial, form)).toEqual({ basic: false, images: false, variants: true })
    form.images = []
    expect(changedSections(initial, form).images).toBe(true)
  })

  it('mapFormFieldErrors: variants[0].variantCode → variants.0.variantCode / conflictErrors: detail의 variantCode로 행 특정·없으면 공통', () => {
    expect(mapFormFieldErrors([{ field: 'variants[0].variantCode', message: 'x' }, { field: 'name', message: 'y' }])).toEqual({ 'variants.0.variantCode': 'x', name: 'y' })
    const form = optionForm()
    expect(conflictErrors('동일 옵션 조합의 상품 변형이 이미 존재합니다: variantCode=실버-1.7L', form)).toEqual({ 'variants.1.combo': expect.stringContaining('같은 옵션 조합') })
    expect(conflictErrors('uk_product_variant_options', form)).toEqual({ variants: expect.stringContaining('같은 옵션 조합') })
  })
})
