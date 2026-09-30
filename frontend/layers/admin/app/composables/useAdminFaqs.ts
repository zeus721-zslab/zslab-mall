import type {
  AdminFaqCreatedResponse,
  AdminFaqItem,
  AdminFaqReorderRequest,
  AdminFaqWriteRequest,
} from '#layers/admin/app/types/admin-faq'
import { useAdminApi } from '#layers/admin/app/composables/useAdminApi'

/**
 * 관리자 FAQ 관리 API 호출 모음(Track 106-3 · useAdminCategories 선례). 전부 useAdminApi 경유이며 상태는 호출부가 소유한다. 수정·삭제·정렬은 204 →
 * 호출부가 목록을 재조회한다.
 */
export function useAdminFaqs() {
  const api = useAdminApi()

  // 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해(TS2321) string으로 고정한다(useAdminCategories 선례).
  function faqPath(faqId: number): string {
    return `/v1/admin/faqs/${faqId}`
  }

  /** 숨김 포함 전체(카테고리 선언 순서 → 카테고리 안 순서). */
  function list(): Promise<AdminFaqItem[]> {
    return api<AdminFaqItem[]>('/v1/admin/faqs')
  }

  /** 해당 카테고리 끝에 등록. 형식 위반 400(VALIDATION_FAILED)은 throw. */
  function create(body: AdminFaqWriteRequest): Promise<AdminFaqCreatedResponse> {
    return api<AdminFaqCreatedResponse>('/v1/admin/faqs', { method: 'POST', body })
  }

  /** PUT 전체 치환(카테고리를 바꾸면 새 카테고리 끝). 미존재 404(FAQ_NOT_FOUND)는 throw. */
  function update(faqId: number, body: AdminFaqWriteRequest): Promise<void> {
    return api<void>(faqPath(faqId), { method: 'PUT', body })
  }

  /** soft-delete. 미존재 404(FAQ_NOT_FOUND)는 throw. */
  function remove(faqId: number): Promise<void> {
    return api<void>(faqPath(faqId), { method: 'DELETE' })
  }

  /** 카테고리 안 일괄 정렬. 누락·중복·다른 카테고리 400(MALFORMED_REQUEST)은 throw. */
  function reorder(body: AdminFaqReorderRequest): Promise<void> {
    return api<void>('/v1/admin/faqs/order', { method: 'PATCH', body })
  }

  return { list, create, update, remove, reorder }
}
