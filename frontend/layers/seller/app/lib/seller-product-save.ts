import type { SellerProductDetail, SellerProductRegistrationResponse } from '#layers/seller/app/types/seller-product'
import type { SellerProductForm, SellerProductFormMode } from '#layers/seller/app/types/seller-product-form'
import {
  type ChangedSections,
  type SellerCreateRequestBody,
  type SellerImagesRequestBody,
  type SellerUpdateRequestBody,
  type SellerVariantsRequestBody,
  toSellerCreateRequest,
  toSellerImagesRequest,
  toSellerUpdateRequest,
  toSellerVariantsRequest,
} from '#layers/seller/app/lib/seller-product-form'

/**
 * 셀러 상품 저장 오케스트레이션(Track 90-C-4·관리자 admin-product-save 복제·순수 로직·API는 주입). 단계를 순차 호출하고 한 단계가 실패하면
 * 즉시 중단해 {@link SellerSaveStepError}로 어느 단계·어느 상품에서 멈췄는지 알린다.
 *
 * 등록: create(옵션+variant+초기재고·PENDING) → images(있을 때·BE 등록 API는 이미지를 받지 않는다·계약 4). 등록 후 이미지 단계 실패 시 호출부가
 * 수정 화면(?partial=1)으로 전환해 재시도한다(중복 등록 방지). / 수정: basic → images → variants — 바뀐 섹션만 호출(changed·dirty 비교).
 * 관리자와 달리 재고 delta 단계가 없다(기존 variant 재고는 mark-inbound/outbound 소관·계약 1).
 */
export type SellerSaveStep = 'create' | 'basic' | 'images' | 'variants'

export const SELLER_SAVE_STEP_LABEL: Record<SellerSaveStep, string> = {
  create: '상품 등록',
  basic: '기본정보 저장',
  images: '이미지 저장',
  variants: '옵션 저장',
}

export interface SellerSaveApi {
  create: (body: SellerCreateRequestBody) => Promise<SellerProductRegistrationResponse>
  update: (productPublicId: string, body: SellerUpdateRequestBody) => Promise<SellerProductDetail>
  replaceImages: (productPublicId: string, body: SellerImagesRequestBody) => Promise<SellerProductDetail>
  replaceVariants: (productPublicId: string, body: SellerVariantsRequestBody) => Promise<SellerProductDetail>
}

export class SellerSaveStepError extends Error {
  readonly step: SellerSaveStep
  readonly failure: unknown
  /** 실패 시점까지 확보된 상품 id(등록 1단계 실패면 null). */
  readonly productPublicId: string | null
  /** 실패 전까지 성공한 단계(어디까지 저장됐는지 안내용). */
  readonly completedSteps: SellerSaveStep[]

  constructor(step: SellerSaveStep, failure: unknown, productPublicId: string | null, completedSteps: SellerSaveStep[]) {
    super(`${SELLER_SAVE_STEP_LABEL[step]} 실패`)
    this.step = step
    this.failure = failure
    this.productPublicId = productPublicId
    this.completedSteps = completedSteps
  }
}

export interface SellerSaveResult {
  productPublicId: string
  steps: SellerSaveStep[]
}

const ALL_CHANGED: ChangedSections = { basic: true, images: true, variants: true }

async function run<T>(step: SellerSaveStep, productPublicId: string | null, completed: SellerSaveStep[], action: () => Promise<T>): Promise<T> {
  try {
    return await action()
  } catch (error) {
    throw new SellerSaveStepError(step, error, productPublicId, [...completed])
  }
}

export async function saveSellerProduct(
  api: SellerSaveApi,
  form: SellerProductForm,
  mode: SellerProductFormMode,
  changed: ChangedSections = ALL_CHANGED,
): Promise<SellerSaveResult> {
  const steps: SellerSaveStep[] = []

  if (mode === 'create') {
    const created = await run('create', null, steps, () => api.create(toSellerCreateRequest(form)))
    const productPublicId = created.productPublicId
    steps.push('create')
    if (form.images.length > 0) {
      await run('images', productPublicId, steps, () => api.replaceImages(productPublicId, toSellerImagesRequest(form)))
      steps.push('images')
    }
    return { productPublicId, steps }
  }

  const id = form.productPublicId as string
  if (changed.basic) {
    await run('basic', id, steps, () => api.update(id, toSellerUpdateRequest(form)))
    steps.push('basic')
  }
  if (changed.images) {
    await run('images', id, steps, () => api.replaceImages(id, toSellerImagesRequest(form)))
    steps.push('images')
  }
  if (changed.variants) {
    await run('variants', id, steps, () => api.replaceVariants(id, toSellerVariantsRequest(form)))
    steps.push('variants')
  }
  return { productPublicId: id, steps }
}
