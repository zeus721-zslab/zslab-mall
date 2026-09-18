import type {
  AdminCategoryCreateRequest,
  AdminCategoryListResponse,
  AdminCategoryReorderRequest,
  AdminCategoryUpdateRequest,
} from '#layers/admin/app/types/admin-category'
import { useAdminApi } from '#layers/admin/app/composables/useAdminApi'

/**
 * 관리자 카테고리 관리 API 호출 모음(FE-38·Track 89-C BE). 전부 useAdminApi(admin_token Bearer·401 처리) 경유이며 상태(로딩·에러)는
 * 호출부(페이지·다이얼로그)가 소유한다. 수정·삭제·정렬은 204(본문 없음) → 호출부가 목록을 재조회한다.
 */
export function useAdminCategories() {
  const api = useAdminApi()

  // 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해(TS2321) string으로 고정한다(useAdminOrders 선례).
  function categoryPath(categoryId: number): string {
    return `/v1/admin/categories/${categoryId}`
  }

  function list(): Promise<AdminCategoryListResponse> {
    return api<AdminCategoryListResponse>('/v1/admin/categories')
  }

  /** 기존 생성 API(Track 46) 재사용. 중복 displayName 409(CATEGORY_DUPLICATE)는 throw. */
  function create(body: AdminCategoryCreateRequest): Promise<void> {
    return api<void>('/v1/admin/categories', { method: 'POST', body })
  }

  /** PUT 전체 치환. 율 실변경에 사유 공백 400(MALFORMED_REQUEST)·중복 409·범위 밖 400(VALIDATION_FAILED)은 throw. */
  function update(categoryId: number, body: AdminCategoryUpdateRequest): Promise<void> {
    return api<void>(categoryPath(categoryId), { method: 'PUT', body })
  }

  /** soft-delete. 활성 상품이 있으면 409(CATEGORY_HAS_PRODUCTS) throw. */
  function remove(categoryId: number): Promise<void> {
    return api<void>(categoryPath(categoryId), { method: 'DELETE' })
  }

  /** 전체 id 배열로 일괄 정렬. 누락·중복·미존재 400(MALFORMED_REQUEST) throw. */
  function reorder(body: AdminCategoryReorderRequest): Promise<void> {
    return api<void>('/v1/admin/categories/order', { method: 'PATCH', body })
  }

  return { list, create, update, remove, reorder }
}
