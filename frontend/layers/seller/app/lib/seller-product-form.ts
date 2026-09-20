import type { SellerProductDetail } from '#layers/seller/app/types/seller-product'
import type {
  SellerFormImage,
  SellerFormOptionGroup,
  SellerFormVariant,
  SellerProductForm,
  SellerProductFormErrors,
  SellerProductFormMode,
} from '#layers/seller/app/types/seller-product-form'

/**
 * 셀러 상품 폼 순수 함수 모음(Track 90-C-4·관리자 admin-product-form 복제·축소): 빈 폼·상세→폼·조합 생성·검증·폼→API 요청·섹션 dirty.
 * 컴포넌트는 상태만 들고 여기 함수를 호출한다. 관리자와의 차이(BE 계약): 셀러 지정·공급가·판매기간·상태 없음 / 기존 variant 재고 읽기 전용(delta adjust
 * 없음) / 수정 모드 옵션 구조 잠금(신규 variant는 기존 값 조합만) / variant 삭제 없음(HIDDEN 토글).
 */

export const MAX_OPTION_GROUPS = 3
export const MAX_VARIANTS = 100
export const MAX_IMAGES = 20
export const PRODUCT_NAME_MAX = 200
export const VARIANT_CODE_MAX = 50
export const SELLER_SKU_MAX = 100

let sequence = 0
/** 화면 전용 로컬 키(테스트 결정성을 위해 단순 증가). */
export function nextLocalId(prefix = 'l'): string {
  sequence += 1
  return `${prefix}${sequence}`
}

export function createEmptySellerProductForm(): SellerProductForm {
  return {
    productPublicId: null,
    categoryId: null,
    name: '',
    description: '',
    basePrice: null,
    images: [],
    hasOptions: false,
    optionGroups: [],
    variants: [singleVariant()],
    status: null,
    soldoutManual: false,
  }
}

/** 단일 상품용 variant 1행(옵션 없음). */
export function singleVariant(): SellerFormVariant {
  return {
    localId: nextLocalId('v'),
    variantPublicId: null,
    optionValueLocalIds: [],
    variantCode: 'DEFAULT',
    sellerSku: '',
    barcode: '',
    additionalPrice: 0,
    initialStock: 0,
    stockOnServer: null,
    soldoutManual: false,
    enabled: true,
    excluded: false,
  }
}

function newVariant(combo: string[], excluded: boolean): SellerFormVariant {
  return {
    localId: nextLocalId('v'),
    variantPublicId: null,
    optionValueLocalIds: combo,
    variantCode: '',
    sellerSku: '',
    barcode: '',
    additionalPrice: 0,
    initialStock: 0,
    stockOnServer: null,
    soldoutManual: false,
    enabled: true,
    excluded,
  }
}

// ---------- 상세 → 폼 ----------

/**
 * 상세 → 폼(수정 모드). 옵션 그룹·값은 서버 구조 그대로(잠금), 기존 variant는 재고를 stockOnServer로만 싣는다. 아직 없는 옵션 조합은
 * "추가 가능" 신규 행(excluded=true)으로 뒤에 붙여 셀러가 체크로 추가할 수 있게 한다(옵션 구조 변경 없이 기존 값 조합만·계약 2).
 */
export function toSellerProductForm(detail: SellerProductDetail): SellerProductForm {
  const optionGroups: SellerFormOptionGroup[] = [...detail.optionGroups]
    .sort((a, b) => a.displayOrder - b.displayOrder)
    .map((group) => ({
      localId: nextLocalId('g'),
      optionGroupId: group.optionGroupId,
      name: group.name,
      values: [...group.values]
        .sort((a, b) => a.displayOrder - b.displayOrder)
        .map((value) => ({ localId: nextLocalId('o'), optionValueId: value.optionValueId, value: value.value })),
    }))
  const valueLocalIdByServerId = new Map<number, string>()
  optionGroups.forEach((group) => group.values.forEach((value) => {
    if (value.optionValueId !== null) valueLocalIdByServerId.set(value.optionValueId, value.localId)
  }))
  const hasOptions = optionGroups.length > 0

  const existing: SellerFormVariant[] = [...detail.variants]
    .sort((a, b) => a.displayOrder - b.displayOrder)
    .map((variant) => ({
      localId: nextLocalId('v'),
      variantPublicId: variant.variantPublicId,
      optionValueLocalIds: hasOptions
        ? optionGroups.map((group) => {
          const option = variant.options.find((candidate) => candidate.optionGroupId === group.optionGroupId)
          return (option && valueLocalIdByServerId.get(option.optionValueId)) ?? ''
        })
        : [],
      variantCode: variant.variantCode,
      sellerSku: variant.sellerSku ?? '',
      barcode: variant.barcode ?? '',
      additionalPrice: variant.additionalPrice,
      initialStock: 0,
      stockOnServer: { onHand: variant.quantityOnHand, reserved: variant.quantityReserved, available: variant.quantityAvailable },
      soldoutManual: variant.soldoutManual,
      enabled: variant.status === 'SALE',
      excluded: false,
    }))
  const existingKeys = new Set(existing.map((variant) => variant.optionValueLocalIds.join('|')))
  const addable = hasOptions
    ? cartesian(optionGroups).filter((combo) => !existingKeys.has(combo.join('|'))).map((combo) => newVariant(combo, true))
    : []

  return {
    productPublicId: detail.productPublicId,
    categoryId: detail.categoryId,
    name: detail.name,
    description: detail.description ?? '',
    basePrice: detail.basePrice,
    images: [...detail.images]
      .sort((a, b) => a.displayOrder - b.displayOrder)
      .map((image) => ({
        localId: nextLocalId('i'),
        imageId: image.imageId,
        imageUrl: image.imageUrl,
        thumbnailUrl: image.imageUrl,
        imageType: image.imageType,
        main: image.main,
      })),
    hasOptions,
    optionGroups,
    variants: existing.length > 0 || addable.length > 0 ? [...existing, ...addable] : [singleVariant()],
    status: detail.status,
    soldoutManual: detail.soldoutManual,
  }
}

// ---------- 이미지 ----------

/** 갤러리 이미지가 있는데 대표가 없으면 첫 갤러리를 대표로. 대표는 갤러리 1장만. */
export function ensureMainImage(images: SellerFormImage[]): SellerFormImage[] {
  const gallery = images.filter((image) => image.imageType === 'GALLERY')
  const mainId = gallery.find((image) => image.main)?.localId ?? gallery[0]?.localId ?? null
  return images.map((image) => ({ ...image, main: image.imageType === 'GALLERY' && image.localId === mainId }))
}

// ---------- 옵션 조합 ----------

/** 그룹별 값 localId 데카르트 곱(그룹 순서 유지). 값이 없는 그룹은 조합을 만들지 않는다. */
export function cartesian(groups: SellerFormOptionGroup[]): string[][] {
  const lists = groups.map((group) => group.values.map((value) => value.localId))
  if (lists.length === 0 || lists.some((list) => list.length === 0)) return []
  return lists.reduce<string[][]>((acc, list) => acc.flatMap((prefix) => list.map((id) => [...prefix, id])), [[]])
}

/**
 * 등록 모드: 옵션 그룹/값 변경 후 조합표를 재생성한다. 같은 조합의 행(입력값)은 유지하고 새 조합은 기본값 행을 만들며 사라진 조합은 버린다
 * (등록 전이라 서버 variant가 없다). 수정 모드에서는 옵션 구조가 잠겨 호출하지 않는다.
 */
export function regenerateVariants(groups: SellerFormOptionGroup[], current: SellerFormVariant[]): SellerFormVariant[] {
  const byKey = new Map(current.map((variant) => [variant.optionValueLocalIds.join('|'), variant]))
  return cartesian(groups).map((combo) => byKey.get(combo.join('|')) ?? newVariant(combo, false))
}

/** 조합 행의 옵션값 라벨("블랙 / M"). */
export function variantLabel(variant: SellerFormVariant, groups: SellerFormOptionGroup[]): string {
  if (variant.optionValueLocalIds.length === 0) return '단일 상품'
  return variant.optionValueLocalIds
    .map((localId, index) => groups[index]?.values.find((value) => value.localId === localId)?.value ?? '?')
    .join(' / ')
}

/** 조합 행 variantCode 자동 제안(비어 있을 때): 옵션값을 '-'로 이어 붙인 코드. */
export function suggestVariantCode(variant: SellerFormVariant, groups: SellerFormOptionGroup[]): string {
  if (variant.optionValueLocalIds.length === 0) return 'DEFAULT'
  return variantLabel(variant, groups).replace(/\s*\/\s*/g, '-').slice(0, VARIANT_CODE_MAX)
}

/** 저장 대상 행 = excluded 제외(등록 응답 variantPublicIds·PUT variants 순서와 1:1·검증 에러 키 variants.N.* 기준). */
export function includedVariants(form: SellerProductForm): SellerFormVariant[] {
  return form.variants.filter((variant) => !variant.excluded)
}

// ---------- 검증 ----------

export function validateSellerProductForm(form: SellerProductForm, mode: SellerProductFormMode): SellerProductFormErrors {
  const errors: SellerProductFormErrors = {}
  if (form.categoryId === null) errors.categoryId = '카테고리를 선택하세요.'
  if (form.name.trim() === '') errors.name = '상품명을 입력하세요.'
  else if (form.name.trim().length > PRODUCT_NAME_MAX) errors.name = `상품명은 ${PRODUCT_NAME_MAX}자 이내입니다.`
  if (form.basePrice === null || Number.isNaN(form.basePrice)) errors.basePrice = '기본가를 입력하세요.'
  else if (form.basePrice < 0) errors.basePrice = '기본가는 0 이상이어야 합니다.'

  if (form.hasOptions) {
    if (form.optionGroups.length === 0) errors.optionGroups = '옵션 그룹을 1개 이상 추가하세요.'
    if (form.optionGroups.length > MAX_OPTION_GROUPS) errors.optionGroups = `옵션 그룹은 최대 ${MAX_OPTION_GROUPS}개입니다.`
    const groupNames = new Set<string>()
    form.optionGroups.forEach((group, groupIndex) => {
      const name = group.name.trim()
      if (name === '') errors[`optionGroups.${groupIndex}.name`] = '그룹명을 입력하세요.'
      else if (groupNames.has(name)) errors[`optionGroups.${groupIndex}.name`] = '그룹명이 중복됩니다.'
      groupNames.add(name)
      if (group.values.length === 0) errors[`optionGroups.${groupIndex}.values`] = '옵션값을 1개 이상 입력하세요.'
      const values = new Set<string>()
      group.values.forEach((value) => {
        const text = value.value.trim()
        if (text !== '' && values.has(text)) errors[`optionGroups.${groupIndex}.values`] = `옵션값 "${text}"이(가) 중복됩니다.`
        values.add(text)
      })
    })
    if (form.variants.length > MAX_VARIANTS) errors.variants = `조합은 최대 ${MAX_VARIANTS}개입니다(현재 ${form.variants.length}).`
    if (mode === 'create' && form.variants.length === 0 && form.optionGroups.length > 0) errors.variants = '옵션값을 입력하면 조합이 생성됩니다.'
  }
  const included = includedVariants(form)
  if (included.length === 0 && form.variants.length > 0) {
    errors.variants = mode === 'create' ? '최소 1개 조합은 포함되어야 합니다(전부 제외됨).' : '저장할 조합이 없습니다.'
  }
  // 조합 중복(등록 모드 방어·수정 모드는 서버 구조 기준이라 발생하지 않음)
  const comboKeys = new Set<string>()
  included.forEach((variant, index) => {
    const key = variant.optionValueLocalIds.join('|')
    if (form.hasOptions && comboKeys.has(key)) errors[`variants.${index}.combo`] = '같은 옵션 조합이 중복됩니다.'
    comboKeys.add(key)
    if (variant.variantCode.trim() === '') errors[`variants.${index}.variantCode`] = '코드를 입력하세요.'
    else if (variant.variantCode.trim().length > VARIANT_CODE_MAX) errors[`variants.${index}.variantCode`] = `코드는 ${VARIANT_CODE_MAX}자 이내입니다.`
    if (variant.sellerSku.trim().length > SELLER_SKU_MAX) errors[`variants.${index}.sellerSku`] = `SKU는 ${SELLER_SKU_MAX}자 이내입니다.`
    if (variant.additionalPrice < 0 || Number.isNaN(variant.additionalPrice)) errors[`variants.${index}.additionalPrice`] = '추가금은 0 이상.'
    if (variant.variantPublicId === null && (variant.initialStock < 0 || Number.isNaN(variant.initialStock))) {
      errors[`variants.${index}.initialStock`] = '초기 재고는 0 이상.'
    }
  })
  return errors
}

// ---------- 폼 → 요청 ----------

interface OptionGroupRequest { name: string; displayOrder: number; values: { key: string; value: string; displayOrder: number }[] }
interface VariantCreateRequest {
  variantCode: string; sellerSku: string | null; barcode: string | null; additionalPrice: number; displayOrder: number; initialStock: number; optionKeys: string[]
}

/** BE ProductRegistrationRequest(셀러 등록·이미지 없음·PENDING 생성). */
export interface SellerCreateRequestBody {
  categoryId: number
  name: string
  description: string | null
  basePrice: number
  thumbnailUrl: string | null
  optionGroups: OptionGroupRequest[]
  variants: VariantCreateRequest[]
}

function blankToNull(value: string): string | null {
  return value.trim() === '' ? null : value.trim()
}

function mainThumbnailUrl(form: SellerProductForm): string | null {
  const main = form.images.find((image) => image.imageType === 'GALLERY' && image.main)
  return main ? main.thumbnailUrl || main.imageUrl : null
}

export function toSellerCreateRequest(form: SellerProductForm): SellerCreateRequestBody {
  const groups = form.hasOptions ? form.optionGroups : []
  return {
    categoryId: form.categoryId ?? 0,
    name: form.name.trim(),
    description: form.description.trim() === '' ? null : form.description,
    basePrice: form.basePrice ?? 0,
    thumbnailUrl: mainThumbnailUrl(form),
    optionGroups: groups.map((group, groupIndex) => ({
      name: group.name.trim(),
      displayOrder: groupIndex,
      values: group.values.map((value, valueIndex) => ({ key: value.localId, value: value.value.trim(), displayOrder: valueIndex })),
    })),
    variants: includedVariants(form).map((variant, index) => ({
      variantCode: variant.variantCode.trim(),
      sellerSku: blankToNull(variant.sellerSku),
      barcode: blankToNull(variant.barcode),
      additionalPrice: variant.additionalPrice,
      displayOrder: index,
      initialStock: variant.initialStock,
      optionKeys: form.hasOptions ? variant.optionValueLocalIds : [],
    })),
  }
}

/** BE SellerProductUpdateRequest(4필드·공급가·판매기간·상태 없음). */
export interface SellerUpdateRequestBody {
  categoryId: number
  name: string
  description: string | null
  basePrice: number
}

export function toSellerUpdateRequest(form: SellerProductForm): SellerUpdateRequestBody {
  return {
    categoryId: form.categoryId ?? 0,
    name: form.name.trim(),
    description: form.description.trim() === '' ? null : form.description,
    basePrice: form.basePrice ?? 0,
  }
}

/** BE SellerProductImagesRequest(관리자와 동일 계약). */
export interface SellerImagesRequestBody {
  images: { imageId: number | null; imageUrl: string; imageType: 'GALLERY' | 'DETAIL'; main: boolean }[]
}

/** 목록 순서 = display_order. 갤러리 먼저·상세 다음(각 영역 내 드래그 순서). */
export function toSellerImagesRequest(form: SellerProductForm): SellerImagesRequestBody {
  const ordered = [...form.images.filter((i) => i.imageType === 'GALLERY'), ...form.images.filter((i) => i.imageType === 'DETAIL')]
  return {
    images: ordered.map((image) => ({
      imageId: image.imageId,
      imageUrl: image.imageUrl,
      imageType: image.imageType,
      main: image.imageType === 'GALLERY' && image.main,
    })),
  }
}

/** BE SellerProductVariantsRequest(메타 수정 + 신규·삭제 없음). */
export interface SellerVariantsRequestBody {
  variants: {
    variantPublicId: string | null
    variantCode: string
    sellerSku: string | null
    barcode: string | null
    additionalPrice: number
    status: 'SALE' | 'HIDDEN'
    soldoutManual: boolean
    displayOrder: number
    initialStock: number
    options: { optionGroupId: number; value: string }[]
  }[]
}

/**
 * PUT variants 본문(수정 모드). 기존 행은 메타만(옵션 조합·재고 불변), 신규 행(추가 체크)은 options로 기존 (그룹 id·값)을 지정한다.
 * 목록에 없는 기존 행은 BE가 건드리지 않지만 셀러 폼은 기존 행 전부를 보내 displayOrder를 일관되게 유지한다.
 */
export function toSellerVariantsRequest(form: SellerProductForm): SellerVariantsRequestBody {
  const groups = form.hasOptions ? form.optionGroups : []
  return {
    variants: includedVariants(form).map((variant, index) => ({
      variantPublicId: variant.variantPublicId,
      variantCode: variant.variantCode.trim(),
      sellerSku: blankToNull(variant.sellerSku),
      barcode: blankToNull(variant.barcode),
      additionalPrice: variant.additionalPrice,
      status: variant.enabled ? 'SALE' : 'HIDDEN',
      soldoutManual: variant.soldoutManual,
      displayOrder: index,
      initialStock: variant.variantPublicId === null ? variant.initialStock : 0,
      options: variant.variantPublicId === null
        ? variant.optionValueLocalIds.map((localId, groupIndex) => ({
          optionGroupId: groups[groupIndex]?.optionGroupId ?? 0,
          value: groups[groupIndex]?.values.find((value) => value.localId === localId)?.value.trim() ?? '',
        }))
        : [],
    })),
  }
}

// ---------- 에러·dirty ----------

/** BE fieldErrors[{field,message}] → 폼 에러 키. BE 필드명(basePrice·name·variants[0].variantCode…)을 폼 키로 정규화한다. */
export function mapFormFieldErrors(fieldErrors: { field: string; message: string }[] | undefined): SellerProductFormErrors {
  const errors: SellerProductFormErrors = {}
  for (const item of fieldErrors ?? []) {
    errors[item.field.replace(/\[(\d+)\]/g, '.$1')] = item.message
  }
  return errors
}

/** 409 PRODUCT_VARIANT_OPTION_CONFLICT detail("… variantCode=XXX")에서 충돌 행의 코드를 꺼내 그 행에 에러를 매긴다. 못 찾으면 variants 공통 에러. */
export function conflictErrors(detail: string | undefined, form: SellerProductForm): SellerProductFormErrors {
  const matched = detail?.match(/variantCode=([^\s,)]+)/)
  const code = matched?.[1]
  const index = code ? includedVariants(form).findIndex((variant) => variant.variantCode.trim() === code) : -1
  if (index >= 0) return { [`variants.${index}.combo`]: '같은 옵션 조합의 변형이 이미 있습니다(삭제된 조합 포함).' }
  return { variants: '같은 옵션 조합의 변형이 이미 있습니다(삭제된 조합 포함).' }
}

function stripLocalIds(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(stripLocalIds)
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value as Record<string, unknown>).filter(([key]) => key !== 'localId').map(([k, v]) => [k, stripLocalIds(v)]))
  }
  return value
}

/** 폼 스냅샷(이탈 경고 dirty 비교용). 로컬 키를 제외한 값만 비교한다. */
export function formSnapshot(form: SellerProductForm): string {
  return JSON.stringify(stripLocalIds(form))
}

export interface SectionSnapshots {
  basic: string
  images: string
  variants: string
}

/** 섹션별 스냅샷 — 수정 저장 시 바뀐 섹션만 PUT한다(변경 없는 단계 호출 생략). */
export function sectionSnapshots(form: SellerProductForm): SectionSnapshots {
  return {
    basic: JSON.stringify(toSellerUpdateRequest(form)),
    images: JSON.stringify(toSellerImagesRequest(form)),
    variants: JSON.stringify(toSellerVariantsRequest(form)),
  }
}

export interface ChangedSections {
  basic: boolean
  images: boolean
  variants: boolean
}

export function changedSections(initial: SectionSnapshots, form: SellerProductForm): ChangedSections {
  const current = sectionSnapshots(form)
  return {
    basic: current.basic !== initial.basic,
    images: current.images !== initial.images,
    variants: current.variants !== initial.variants,
  }
}
