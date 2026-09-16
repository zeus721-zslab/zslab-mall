import type {
  AdminProductBulkResponse,
  AdminProductListResponse,
  AdminProductListQuery,
  AdminProductStatusResponse,
  AdminSellerSummary,
} from '#layers/admin/app/types/admin-product'
import type { CategorySummary } from '~/types/category'
import type { AdminProductBulkStatusTarget, AdminProductStatusTarget } from '#layers/admin/app/lib/constants/product'
import { toAdminProductApiParams } from '#layers/admin/app/lib/admin-product-query'
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

  function bulkStatus(productPublicIds: string[], status: AdminProductBulkStatusTarget): Promise<AdminProductBulkResponse> {
    return api<AdminProductBulkResponse>('/v1/admin/products/bulk/status', { method: 'POST', body: { productPublicIds, status } })
  }

  function bulkSoldOut(productPublicIds: string[], soldOut: boolean): Promise<AdminProductBulkResponse> {
    return api<AdminProductBulkResponse>('/v1/admin/products/bulk/soldout', { method: 'POST', body: { productPublicIds, soldOut } })
  }

  function remove(productPublicId: string): Promise<unknown> {
    return api<unknown>(productPath(productPublicId), { method: 'DELETE' })
  }

  function sellers(): Promise<AdminSellerSummary[]> {
    return api<AdminSellerSummary[]>('/v1/admin/sellers')
  }

  function categories(): Promise<CategorySummary[]> {
    return api<CategorySummary[]>('/v1/categories')
  }

  return { list, setSoldOut, changeStatus, bulkStatus, bulkSoldOut, remove, sellers, categories }
}
