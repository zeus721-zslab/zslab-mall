import type {
  AdminProductBulkResponse,
  AdminProductCreateResponse,
  AdminProductDetail,
  AdminProductListResponse,
  AdminProductListQuery,
  AdminProductStatusResponse,
  AdminSellerSummary,
  ImageUploadResponse,
} from '#layers/admin/app/types/admin-product'
import type { CategorySummary } from '~/types/category'
import type { AdminProductBulkStatusTarget, AdminProductStatusTarget } from '#layers/admin/app/lib/constants/product'
import { toAdminProductApiParams } from '#layers/admin/app/lib/admin-product-query'
import type { CreateRequestBody, ImagesRequestBody, UpdateRequestBody, VariantsRequestBody } from '#layers/admin/app/lib/admin-product-form'
import { useAdminApi } from '#layers/admin/app/composables/useAdminApi'

/**
 * 관리자 상품 관리 API 호출 모음(FE-25·Track 76 BE). 전부 useAdminApi(admin_token Bearer·401 처리) 경유이며 상태(로딩·에러)는
 * 호출부(페이지)가 소유한다 — 목록·행 갱신·원복 흐름이 화면 로직이기 때문.
 *
 * <p>상태 전환은 목표 상태로 BE 경로를 분기한다: PENDING→SALE=approve·PENDING→REJECTED=reject·그 외 SALE|STOPPED=sale-status.
 */
export function useAdminProducts() {
  const api = useAdminApi()

  // 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해(TS2321 excessive stack depth) string으로 고정한다.
  function productPath(productPublicId: string, suffix = ''): string {
    return `/v1/admin/products/${productPublicId}${suffix}`
  }

  function list(query: AdminProductListQuery): Promise<AdminProductListResponse> {
    return api<AdminProductListResponse>('/v1/admin/products', { query: toAdminProductApiParams(query) })
  }

  function setSoldOut(productPublicId: string, soldOut: boolean): Promise<unknown> {
    return api<unknown>(productPath(productPublicId, '/soldout'), { method: 'PATCH', body: { soldOut } })
  }

  function changeStatus(
    productPublicId: string,
    currentStatus: string,
    target: AdminProductStatusTarget,
  ): Promise<AdminProductStatusResponse> {
    if (currentStatus === 'PENDING' && target === 'SALE') {
      return api<AdminProductStatusResponse>(productPath(productPublicId, '/approve'), { method: 'POST' })
    }
    if (target === 'REJECTED') {
      return api<AdminProductStatusResponse>(productPath(productPublicId, '/reject'), { method: 'POST' })
    }
    return api<AdminProductStatusResponse>(productPath(productPublicId, '/sale-status'), {
      method: 'POST',
      body: { status: target },
    })
  }

  /** 거부 철회(REJECTED → PENDING·Track 101-A). 사유 필수(감사 기록). REJECTED 아님 422·사유 누락 400은 throw. */
  function withdrawRejection(productPublicId: string, reason: string): Promise<AdminProductStatusResponse> {
    return api<AdminProductStatusResponse>(productPath(productPublicId, '/withdraw-rejection'), {
      method: 'POST',
      body: { reason },
    })
  }

  function bulkStatus(productPublicIds: string[], status: AdminProductBulkStatusTarget): Promise<AdminProductBulkResponse> {
    return api<AdminProductBulkResponse>('/v1/admin/products/bulk/status', { method: 'POST', body: { productPublicIds, status } })
  }

  function bulkSoldOut(productPublicIds: string[], soldOut: boolean): Promise<AdminProductBulkResponse> {
    return api<AdminProductBulkResponse>('/v1/admin/products/bulk/soldout', { method: 'POST', body: { productPublicIds, soldOut } })
  }

  function remove(productPublicId: string): Promise<unknown> {
    return api<unknown>(productPath(productPublicId), { method: 'DELETE' })
  }

  // ---------- FE-26 등록·수정 ----------

  function detail(productPublicId: string): Promise<AdminProductDetail> {
    return api<AdminProductDetail>(productPath(productPublicId))
  }

  function create(body: CreateRequestBody): Promise<AdminProductCreateResponse> {
    return api<AdminProductCreateResponse>('/v1/admin/products', { method: 'POST', body })
  }

  function update(productPublicId: string, body: UpdateRequestBody): Promise<AdminProductDetail> {
    return api<AdminProductDetail>(productPath(productPublicId), { method: 'PUT', body })
  }

  function replaceImages(productPublicId: string, body: ImagesRequestBody): Promise<AdminProductDetail> {
    return api<AdminProductDetail>(productPath(productPublicId, '/images'), { method: 'PUT', body })
  }

  function replaceVariants(productPublicId: string, body: VariantsRequestBody): Promise<AdminProductDetail> {
    return api<AdminProductDetail>(productPath(productPublicId, '/variants'), { method: 'PUT', body })
  }

  /** 기존 variant 재고 증감(Track 76 유지 경로·PUT variants는 기존 행 재고를 바꾸지 않는다). */
  function adjustStock(variantPublicId: string, quantityDelta: number, reason: string): Promise<unknown> {
    const path: string = `/v1/admin/inventories/${variantPublicId}/adjust`
    return api<unknown>(path, { method: 'POST', body: { quantityDelta, reason } })
  }

  /** 이미지 업로드(Track 77·multipart files[]). 항상 200·파일별 결과. 413·400은 throw. */
  function uploadImages(files: File[]): Promise<ImageUploadResponse> {
    const formData = new FormData()
    files.forEach((file) => formData.append('files', file))
    return api<ImageUploadResponse>('/v1/admin/files/images', { method: 'POST', body: formData })
  }

  function sellers(): Promise<AdminSellerSummary[]> {
    return api<AdminSellerSummary[]>('/v1/admin/sellers')
  }

  function categories(): Promise<CategorySummary[]> {
    return api<CategorySummary[]>('/v1/categories')
  }

  return {
    list, setSoldOut, changeStatus, withdrawRejection, bulkStatus, bulkSoldOut, remove, sellers, categories,
    detail, create, update, replaceImages, replaceVariants, adjustStock, uploadImages,
  }
}
