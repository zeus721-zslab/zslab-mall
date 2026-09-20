import { describe, it, expect, vi } from 'vitest'
import type { SellerProductForm } from '#layers/seller/app/types/seller-product-form'
import { createEmptySellerProductForm } from '#layers/seller/app/lib/seller-product-form'
import { SELLER_SAVE_STEP_LABEL, SellerSaveStepError, saveSellerProduct, type SellerSaveApi } from '#layers/seller/app/lib/seller-product-save'

/**
 * 셀러 저장 오케스트레이션(Track 90-C-4): 등록 = create → images(있을 때만) / 수정 = basic → images → variants(바뀐 섹션만) / 단계 실패 시
 * SellerSaveStepError(단계·productPublicId·완료 단계) + 이후 단계 미호출. 재고 delta 단계가 없다(관리자와 차이).
 */
function api(overrides: Partial<SellerSaveApi> = {}): SellerSaveApi & { calls: string[] } {
  const calls: string[] = []
  const wrap = <T extends (...args: never[]) => Promise<unknown>>(name: string, fn: T) =>
    (async (...args: Parameters<T>) => { calls.push(name); return fn(...args) }) as T
  return {
    calls,
    create: wrap('create', overrides.create ?? vi.fn().mockResolvedValue({ productPublicId: 'prd_new', variantPublicIds: ['var_a'] })),
    update: wrap('update', overrides.update ?? vi.fn().mockResolvedValue({})),
    replaceImages: wrap('images', overrides.replaceImages ?? vi.fn().mockResolvedValue({})),
    replaceVariants: wrap('variants', overrides.replaceVariants ?? vi.fn().mockResolvedValue({})),
  }
}

function form(overrides: Partial<SellerProductForm> = {}): SellerProductForm {
  const base = createEmptySellerProductForm()
  base.categoryId = 1
  base.name = '반찬통'
  base.basePrice = 32000
  return { ...base, ...overrides }
}

const IMAGE = { localId: 'i1', imageId: null, imageUrl: '/api/v1/files/products/a.jpg', thumbnailUrl: '/api/v1/files/products/a_thumb.jpg', imageType: 'GALLERY' as const, main: true }

describe('saveSellerProduct — 등록', () => {
  it('이미지 없음: create만 호출 · 이미지 있음: create → images(생성된 id로)', async () => {
    const noImages = api()
    const result = await saveSellerProduct(noImages, form(), 'create')
    expect(noImages.calls).toEqual(['create'])
    expect(result).toEqual({ productPublicId: 'prd_new', steps: ['create'] })

    const replaceImages = vi.fn().mockResolvedValue({})
    const withImages = api({ replaceImages })
    await saveSellerProduct(withImages, form({ images: [IMAGE] }), 'create')
    expect(withImages.calls).toEqual(['create', 'images'])
    expect(replaceImages).toHaveBeenCalledWith('prd_new', { images: [{ imageId: null, imageUrl: IMAGE.imageUrl, imageType: 'GALLERY', main: true }] })
  })

  it('create 실패 → SellerSaveStepError(step create·productPublicId null·완료 없음)·images 미호출', async () => {
    const failing = api({ create: vi.fn().mockRejectedValue({ status: 403, data: { code: 'SELLER_SUSPENDED' } }) })
    const error = await saveSellerProduct(failing, form({ images: [IMAGE] }), 'create').catch((caught: unknown) => caught)
    expect(error).toBeInstanceOf(SellerSaveStepError)
    const stepError = error as SellerSaveStepError
    expect(stepError.step).toBe('create')
    expect(stepError.productPublicId).toBeNull()
    expect(stepError.completedSteps).toEqual([])
    expect(failing.calls).toEqual(['create'])
  })

  it('images 실패 → step images·productPublicId=생성된 id(수정 화면 partial 전환용)·완료 단계 [create]', async () => {
    const failing = api({ replaceImages: vi.fn().mockRejectedValue({ status: 400, data: { code: 'MALFORMED_REQUEST' } }) })
    const error = await saveSellerProduct(failing, form({ images: [IMAGE] }), 'create').catch((caught: unknown) => caught) as SellerSaveStepError
    expect(error.step).toBe('images')
    expect(error.productPublicId).toBe('prd_new')
    expect(error.completedSteps).toEqual(['create'])
    expect(error.message).toBe(`${SELLER_SAVE_STEP_LABEL.images} 실패`)
  })
})

describe('saveSellerProduct — 수정', () => {
  const edit = form({ productPublicId: 'prd_1' })

  it('changed 전부 true: basic → images → variants 순서', async () => {
    const all = api()
    const result = await saveSellerProduct(all, edit, 'edit')
    expect(all.calls).toEqual(['update', 'images', 'variants'])
    expect(result.steps).toEqual(['basic', 'images', 'variants'])
  })

  it('바뀐 섹션만 호출: variants만 → replaceVariants 1회 · 아무것도 없으면 호출 0', async () => {
    const only = api()
    const result = await saveSellerProduct(only, edit, 'edit', { basic: false, images: false, variants: true })
    expect(only.calls).toEqual(['variants'])
    expect(result.steps).toEqual(['variants'])
    const none = api()
    expect((await saveSellerProduct(none, edit, 'edit', { basic: false, images: false, variants: false })).steps).toEqual([])
    expect(none.calls).toEqual([])
  })

  it('중간(images) 실패 → step images·완료 [basic]·variants 미호출', async () => {
    const failing = api({ replaceImages: vi.fn().mockRejectedValue({ status: 404, data: { code: 'PRODUCT_IMAGE_NOT_FOUND' } }) })
    const error = await saveSellerProduct(failing, edit, 'edit').catch((caught: unknown) => caught) as SellerSaveStepError
    expect(error.step).toBe('images')
    expect(error.productPublicId).toBe('prd_1')
    expect(error.completedSteps).toEqual(['basic'])
    expect(failing.calls).toEqual(['update', 'images'])
  })
})
