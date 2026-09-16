import type { ProductForm } from '#layers/admin/app/types/admin-product-form'
import type { AdminProductCreateResponse, AdminProductDetail } from '#layers/admin/app/types/admin-product'
import {
  includedVariants,
  type CreateRequestBody,
  type ImagesRequestBody,
  type UpdateRequestBody,
  type VariantsRequestBody,
  needsVariantMetaAfterCreate,
  stockAdjustments,
  toCreateRequest,
  toImagesRequest,
  toUpdateRequest,
  toVariantsRequest,
} from '#layers/admin/app/lib/admin-product-form'

/**
 * 저장 오케스트레이션(FE-26·순수 로직·API는 주입). 단계를 순차 호출하고 한 단계가 실패하면 즉시 중단해 {@link SaveStepError}로 어느
 * 단계·어느 상품에서 멈췄는지 알린다. 등록은 POST 직후 productPublicId가 생기므로 이후 단계 실패 시 호출부가 수정 화면으로 전환해
 * 재시도한다(중복 등록 방지).
 *
 * 등록: create → images(있을 때) → variants(상태/수동품절이 기본과 다를 때) / 수정: basic → images → variants → stock(delta별).
 */
export type SaveStep = 'create' | 'basic' | 'images' | 'variants' | 'stock'

export const SAVE_STEP_LABEL: Record<SaveStep, string> = {
  create: '상품 등록',
  basic: '기본정보 저장',
  images: '이미지 저장',
  variants: '옵션·재고 저장',
  stock: '재고 조정',
}

export interface SaveApi {
  create: (body: CreateRequestBody) => Promise<AdminProductCreateResponse>
  update: (productPublicId: string, body: UpdateRequestBody) => Promise<AdminProductDetail>
  replaceImages: (productPublicId: string, body: ImagesRequestBody) => Promise<AdminProductDetail>
  replaceVariants: (productPublicId: string, body: VariantsRequestBody) => Promise<AdminProductDetail>
  adjustStock: (variantPublicId: string, quantityDelta: number, reason: string) => Promise<unknown>
}

export class SaveStepError extends Error {
  readonly step: SaveStep
  readonly failure: unknown
  /** 실패 시점까지 확보된 상품 id(등록 1단계 실패면 null). */
  readonly productPublicId: string | null

  constructor(step: SaveStep, failure: unknown, productPublicId: string | null) {
    super(`${SAVE_STEP_LABEL[step]} 실패`)
    this.step = step
    this.failure = failure
    this.productPublicId = productPublicId
  }
}

export const STOCK_ADJUST_REASON = '관리자 상품 수정 화면 재고 변경'

export interface SaveResult {
  productPublicId: string
  steps: SaveStep[]
}

async function run<T>(step: SaveStep, productPublicId: string | null, action: () => Promise<T>): Promise<T> {
  try {
    return await action()
  } catch (error) {
    throw new SaveStepError(step, error, productPublicId)
  }
}

export async function saveProduct(api: SaveApi, form: ProductForm, mode: 'create' | 'edit'): Promise<SaveResult> {
  const steps: SaveStep[] = []
  let productPublicId = form.productPublicId

  if (mode === 'create') {
    const created = await run('create', null, () => api.create(toCreateRequest(form)))
    productPublicId = created.productPublicId
    steps.push('create')
    if (form.images.length > 0) {
      await run('images', productPublicId, () => api.replaceImages(productPublicId as string, toImagesRequest(form)))
      steps.push('images')
    }
    if (needsVariantMetaAfterCreate(form)) {
      // 등록 응답의 variantPublicIds는 요청 순서와 같다 → 폼 행에 매핑해 메타(상태·수동품절)만 갱신한다.
      const mapped: ProductForm = {
        ...form,
        productPublicId,
        variants: includedVariants(form).map((variant, index) => ({
          ...variant,
          variantPublicId: created.variantPublicIds[index] ?? null,
          stockOnServer: variant.stock,
        })),
      }
      await run('variants', productPublicId, () => api.replaceVariants(productPublicId as string, toVariantsRequest(mapped)))
      steps.push('variants')
    }
    return { productPublicId, steps }
  }

  const id = productPublicId as string
  await run('basic', id, () => api.update(id, toUpdateRequest(form)))
  steps.push('basic')
  await run('images', id, () => api.replaceImages(id, toImagesRequest(form)))
  steps.push('images')
  await run('variants', id, () => api.replaceVariants(id, toVariantsRequest(form)))
  steps.push('variants')
  for (const adjustment of stockAdjustments(form)) {
    await run('stock', id, () => api.adjustStock(adjustment.variantPublicId, adjustment.delta, STOCK_ADJUST_REASON))
  }
  if (stockAdjustments(form).length > 0) steps.push('stock')
  return { productPublicId: id, steps }
}
