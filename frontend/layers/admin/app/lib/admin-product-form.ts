import type { AdminProductDetail } from '#layers/admin/app/types/admin-product'
import type {
  ProductForm,
  ProductFormErrors,
  ProductFormImage,
  ProductFormOptionGroup,
  ProductFormOptionValue,
  ProductFormVariant,
} from '#layers/admin/app/types/admin-product-form'

/**
 * 상품 폼 순수 함수 모음(FE-26): 빈 폼·상세→폼·조합 생성/diff·검증·폼→API 요청·재고 delta. 컴포넌트는 상태만 들고 여기 함수를 호출한다.
 * 시각은 BE가 KST(+09:00) ISO offset을 주고받으므로 datetime-local 값(앞 16자)에 ":00+09:00"을 붙여 되돌린다.
 */

export const MAX_OPTION_GROUPS = 3
export const MAX_VARIANTS = 100
export const MAX_IMAGES = 20
export const KST_OFFSET_SUFFIX = ':00+09:00'

let sequence = 0
/** 화면 전용 로컬 키(테스트 결정성을 위해 단순 증가). */
export function nextLocalId(prefix = 'l'): string {
  sequence += 1
  return `${prefix}${sequence}`
}

export function emptyForm(): ProductForm {
  return {
    productPublicId: null,
    sellerPublicId: null,
    categoryId: null,
    name: '',
    description: '',
    basePrice: null,
    supplyPrice: null,
    saleStartAt: '',
    saleEndAt: '',
    noEndDate: true,
    images: [],
    hasOptions: false,
    optionGroups: [],
    variants: [singleVariant()],
    status: null,
    saleStopSource: null,
    soldOutManual: false,
  }
}

/** 단일 상품용 variant 1행(옵션 없음). */
export function singleVariant(): ProductFormVariant {
  return {
    localId: nextLocalId('v'),
    variantPublicId: null,
    optionValueLocalIds: [],
    variantCode: 'DEFAULT',
    additionalPrice: 0,
    stock: 0,
    stockOnServer: null,
    soldOutManual: false,
    enabled: true,
    removed: false,
    excluded: false,
  }
}

// ---------- 시각 ----------

/** BE ISO(+09:00) → datetime-local 값. 없으면 ''. */
export function isoToLocalInput(iso: string | undefined): string {
  if (!iso) return ''
  const matched = iso.match(/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2})/)
  return matched?.[1] ?? ''
}

/** datetime-local 값 → BE ISO(+09:00). 빈 값은 null. */
export function localInputToIso(local: string): string | null {
  if (!local) return null
  return `${local}${KST_OFFSET_SUFFIX}`
}

// ---------- 상세 → 폼 ----------

export function detailToForm(detail: AdminProductDetail): ProductForm {
  const optionGroups: ProductFormOptionGroup[] = [...detail.optionGroups]
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

  const variants: ProductFormVariant[] = [...detail.variants]
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
      additionalPrice: variant.additionalPrice,
      stock: variant.quantityAvailable,
      stockOnServer: variant.quantityAvailable,
      soldOutManual: variant.soldOutManual,
      enabled: variant.status === 'SALE',
      removed: false,
      excluded: false,
    }))

  return {
    productPublicId: detail.productPublicId,
    sellerPublicId: detail.sellerPublicId ?? null,
    categoryId: detail.categoryId,
    name: detail.name,
    description: detail.description ?? '',
    basePrice: detail.basePrice,
    supplyPrice: detail.supplyPrice ?? null,
    saleStartAt: isoToLocalInput(detail.saleStartAt),
    saleEndAt: isoToLocalInput(detail.saleEndAt),
    noEndDate: !detail.saleEndAt,
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
    variants: variants.length > 0 ? variants : [singleVariant()],
    status: detail.status,
    saleStopSource: detail.saleStopSource ?? null,
    soldOutManual: detail.soldOutManual,
  }
}

// ---------- 이미지 ----------

/** 갤러리 이미지가 있는데 대표가 없으면 첫 갤러리를 대표로. 대표는 갤러리 1장만. */
export function ensureMainImage(images: ProductFormImage[]): ProductFormImage[] {
  const gallery = images.filter((image) => image.imageType === 'GALLERY')
  const mainId = gallery.find((image) => image.main)?.localId ?? gallery[0]?.localId ?? null
  return images.map((image) => ({ ...image, main: image.imageType === 'GALLERY' && image.localId === mainId }))
}

// ---------- 옵션 조합 ----------

/** 그룹별 값 localId 데카르트 곱(그룹 순서 유지). 값이 없는 그룹은 조합을 만들지 않는다. */
export function cartesian(groups: ProductFormOptionGroup[]): string[][] {
  const lists = groups.map((group) => group.values.map((value) => value.localId))
  if (lists.length === 0 || lists.some((list) => list.length === 0)) return []
  return lists.reduce<string[][]>((acc, list) => acc.flatMap((prefix) => list.map((id) => [...prefix, id])), [[]])
}

/**
 * 옵션 그룹/값 변경 후 조합표를 재생성한다. 같은 조합의 기존 행(입력값·variantPublicId)은 유지하고, 새 조합은 기본값 행을 만들며,
 * 사라진 조합의 기존(서버) variant는 removed=true로 남긴다(수정 시 soft-delete 대상 표시). 신규 행이 사라지면 그냥 버린다.
 */
export function regenerateVariants(groups: ProductFormOptionGroup[], current: ProductFormVariant[]): ProductFormVariant[] {
  const combos = cartesian(groups)
  const byKey = new Map(current.map((variant) => [variant.optionValueLocalIds.join('|'), variant]))
  const kept = new Set<string>()
  const next: ProductFormVariant[] = combos.map((combo) => {
    const key = combo.join('|')
    kept.add(key)
    const existing = byKey.get(key)
    if (existing) return { ...existing, removed: false }
    return {
      localId: nextLocalId('v'),
      variantPublicId: null,
      optionValueLocalIds: combo,
      variantCode: '',
      additionalPrice: 0,
      stock: 0,
      stockOnServer: null,
      soldOutManual: false,
      enabled: true,
      removed: false,
      excluded: false,
    }
  })
  const removed = current
    .filter((variant) => variant.variantPublicId !== null && !kept.has(variant.optionValueLocalIds.join('|')))
    .map((variant) => ({ ...variant, removed: true }))
  return [...next, ...removed]
}

/** 조합 행의 옵션값 라벨("블랙 / M"). */
export function variantLabel(variant: ProductFormVariant, groups: ProductFormOptionGroup[]): string {
  if (variant.optionValueLocalIds.length === 0) return '단일 상품'
  return variant.optionValueLocalIds
    .map((localId, index) => groups[index]?.values.find((value) => value.localId === localId)?.value ?? '?')
    .join(' / ')
}

/** 조합 행 variantCode 자동 제안(비어 있을 때): 옵션값을 '-'로 이어 붙인 코드. */
export function suggestVariantCode(variant: ProductFormVariant, groups: ProductFormOptionGroup[]): string {
  if (variant.optionValueLocalIds.length === 0) return 'DEFAULT'
  return variantLabel(variant, groups).replace(/\s*\/\s*/g, '-').slice(0, 50)
}

// ---------- 검증 ----------

export function validateForm(form: ProductForm, mode: 'create' | 'edit'): ProductFormErrors {
  const errors: ProductFormErrors = {}
  if (mode === 'create' && !form.sellerPublicId) errors.sellerPublicId = '셀러를 선택하세요.'
  if (form.categoryId === null) errors.categoryId = '카테고리를 선택하세요.'
  if (form.name.trim() === '') errors.name = '상품명을 입력하세요.'
  else if (form.name.trim().length > 200) errors.name = '상품명은 200자 이내입니다.'
  if (form.basePrice === null || Number.isNaN(form.basePrice)) errors.basePrice = '판매가를 입력하세요.'
  else if (form.basePrice < 0) errors.basePrice = '판매가는 0 이상이어야 합니다.'
  if (form.supplyPrice !== null && form.supplyPrice < 0) errors.supplyPrice = '공급가는 0 이상이어야 합니다.'
  if (!form.noEndDate && form.saleEndAt !== '' && form.saleStartAt !== '' && form.saleStartAt >= form.saleEndAt) {
    errors.saleEndAt = '판매 종료는 시작보다 뒤여야 합니다.'
  }
  if (!form.noEndDate && form.saleEndAt === '') errors.saleEndAt = '종료 시각을 입력하거나 "종료일 없음"을 켜세요.'

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
    const active = form.variants.filter((variant) => !variant.removed)
    if (active.length > MAX_VARIANTS) errors.variants = `조합은 최대 ${MAX_VARIANTS}개입니다(현재 ${active.length}).`
    if (active.length === 0 && form.optionGroups.length > 0) errors.variants = '옵션값을 입력하면 조합이 생성됩니다.'
  }
  // 제외(신규 행 "제외" 체크)를 뺀 실제 저장 대상이 0이면 안 된다(등록: 최소 1개 조합·수정: 기존 행이 남아 있으면 통과).
  if (includedVariants(form).length === 0 && form.variants.some((variant) => !variant.removed)) {
    errors.variants = '최소 1개 조합은 포함되어야 합니다(전부 제외됨).'
  }
  form.variants.filter((variant) => !variant.removed && !variant.excluded).forEach((variant, index) => {
    if (variant.variantCode.trim() === '') errors[`variants.${index}.variantCode`] = '코드를 입력하세요.'
    if (variant.additionalPrice < 0) errors[`variants.${index}.additionalPrice`] = '추가금은 0 이상.'
    if (variant.stock < 0) errors[`variants.${index}.stock`] = '재고는 0 이상.'
  })
  return errors
}

// ---------- 폼 → 요청 ----------

interface OptionGroupRequest { name: string; displayOrder: number; values: { key: string; value: string; displayOrder: number }[] }
interface VariantCreateRequest {
  variantCode: string; sellerSku: null; barcode: null; additionalPrice: number; displayOrder: number; initialStock: number; optionKeys: string[]
}

export interface CreateRequestBody {
  sellerPublicId: string
  categoryId: number
  name: string
  description: string | null
  basePrice: number
  supplyPrice: number | null
  thumbnailUrl: string | null
  saleStartAt: string | null
  saleEndAt: string | null
  optionGroups: OptionGroupRequest[]
  variants: VariantCreateRequest[]
}

/** 저장 대상 행 = removed·excluded 제외(등록 응답 variantPublicIds 순서와 1:1). */
export function includedVariants(form: ProductForm): ProductFormVariant[] {
  return form.variants.filter((variant) => !variant.removed && !variant.excluded)
}

function activeVariants(form: ProductForm): ProductFormVariant[] {
  return includedVariants(form)
}

function mainThumbnailUrl(form: ProductForm): string | null {
  const main = form.images.find((image) => image.imageType === 'GALLERY' && image.main)
  return main ? main.thumbnailUrl || main.imageUrl : null
}

export function toCreateRequest(form: ProductForm): CreateRequestBody {
  const groups = form.hasOptions ? form.optionGroups : []
  return {
    sellerPublicId: form.sellerPublicId ?? '',
    categoryId: form.categoryId ?? 0,
    name: form.name.trim(),
    description: form.description.trim() === '' ? null : form.description,
    basePrice: form.basePrice ?? 0,
    supplyPrice: form.supplyPrice,
    thumbnailUrl: mainThumbnailUrl(form),
    saleStartAt: localInputToIso(form.saleStartAt),
    saleEndAt: form.noEndDate ? null : localInputToIso(form.saleEndAt),
    optionGroups: groups.map((group, groupIndex) => ({
      name: group.name.trim(),
      displayOrder: groupIndex,
      values: group.values.map((value, valueIndex) => ({ key: value.localId, value: value.value.trim(), displayOrder: valueIndex })),
    })),
    variants: activeVariants(form).map((variant, index) => ({
      variantCode: variant.variantCode.trim(),
      sellerSku: null,
      barcode: null,
      additionalPrice: variant.additionalPrice,
      displayOrder: index,
      initialStock: variant.stock,
      optionKeys: form.hasOptions ? variant.optionValueLocalIds : [],
    })),
  }
}

export interface UpdateRequestBody {
  categoryId: number
  name: string
  description: string | null
  basePrice: number
  supplyPrice: number | null
  thumbnailUrl: string | null
  saleStartAt: string | null
  saleEndAt: string | null
}

export function toUpdateRequest(form: ProductForm): UpdateRequestBody {
  return {
    categoryId: form.categoryId ?? 0,
    name: form.name.trim(),
    description: form.description.trim() === '' ? null : form.description,
    basePrice: form.basePrice ?? 0,
    supplyPrice: form.supplyPrice,
    thumbnailUrl: mainThumbnailUrl(form),
    saleStartAt: localInputToIso(form.saleStartAt),
    saleEndAt: form.noEndDate ? null : localInputToIso(form.saleEndAt),
  }
}

export interface ImagesRequestBody {
  images: { imageId: number | null; imageUrl: string; imageType: 'GALLERY' | 'DETAIL'; main: boolean }[]
}

/** 목록 순서 = display_order. 갤러리 먼저·상세 다음(각 영역 내 드래그 순서). */
export function toImagesRequest(form: ProductForm): ImagesRequestBody {
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

export interface VariantsRequestBody {
  variants: {
    variantPublicId: string | null
    variantCode: string
    sellerSku: null
    barcode: null
    additionalPrice: number
    status: 'SALE' | 'STOPPED'
    soldoutManual: boolean
    displayOrder: number
    initialStock: number
    options: { optionGroupId: number; value: string }[]
  }[]
}

/**
 * PUT variants 본문(수정 모드·D9 α). removed 행은 제외(=soft-delete). 신규 행은 options로 (그룹 id·값)을 지정하며 값이 서버에 없으면
 * BE가 새 옵션값을 만든다. 기존 행은 메타만(옵션 조합 불변).
 */
export function toVariantsRequest(form: ProductForm): VariantsRequestBody {
  const groups = form.hasOptions ? form.optionGroups : []
  return {
    variants: activeVariants(form).map((variant, index) => ({
      variantPublicId: variant.variantPublicId,
      variantCode: variant.variantCode.trim(),
      sellerSku: null,
      barcode: null,
      additionalPrice: variant.additionalPrice,
      status: variant.enabled ? 'SALE' : 'STOPPED',
      soldoutManual: variant.soldOutManual,
      displayOrder: index,
      initialStock: variant.variantPublicId === null ? variant.stock : 0,
      options: variant.variantPublicId === null
        ? variant.optionValueLocalIds.map((localId, groupIndex) => ({
          optionGroupId: groups[groupIndex]?.optionGroupId ?? 0,
          value: groups[groupIndex]?.values.find((value) => value.localId === localId)?.value.trim() ?? '',
        }))
        : [],
    })),
  }
}

/** 등록 직후 variant 상태·수동품절이 기본(SALE·false)과 다르면 PUT variants가 필요하다. */
export function needsVariantMetaAfterCreate(form: ProductForm): boolean {
  return activeVariants(form).some((variant) => !variant.enabled || variant.soldOutManual)
}

/** 기존 variant 재고 변경분(수정 모드): stock − stockOnServer ≠ 0인 행만. */
export function stockAdjustments(form: ProductForm): { variantPublicId: string; delta: number }[] {
  return activeVariants(form)
    .filter((variant) => variant.variantPublicId !== null && variant.stockOnServer !== null && variant.stock !== variant.stockOnServer)
    .map((variant) => ({ variantPublicId: variant.variantPublicId as string, delta: variant.stock - (variant.stockOnServer as number) }))
}

/** BE fieldErrors[{field,message}] → 폼 에러 키. BE 필드명(basePrice·name·variants[0].variantCode…)을 폼 키로 정규화한다. */
export function mapFieldErrors(fieldErrors: { field: string; message: string }[] | undefined): ProductFormErrors {
  const errors: ProductFormErrors = {}
  for (const item of fieldErrors ?? []) {
    errors[item.field.replace(/\[(\d+)\]/g, '.$1')] = item.message
  }
  return errors
}

/** 폼 스냅샷(dirty 비교용). 로컬 키를 제외한 값만 비교한다. */
export function formSnapshot(form: ProductForm): string {
  const strip = (value: unknown): unknown => {
    if (Array.isArray(value)) return value.map(strip)
    if (value && typeof value === 'object') {
      return Object.fromEntries(Object.entries(value as Record<string, unknown>).filter(([key]) => key !== 'localId').map(([k, v]) => [k, strip(v)]))
    }
    return value
  }
  return JSON.stringify(strip(form))
}
