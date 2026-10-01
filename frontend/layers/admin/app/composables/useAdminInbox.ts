import type { InboxResponse, InboxSnoozeBody } from '~/types/inbox'
import type { InboxQueryState } from '~/lib/inbox-query'
import type { AdminSellerDelay, AdminSellerNudgeResponse } from '#layers/admin/app/types/admin-seller-delay'
import { toInboxApiParams } from '~/lib/inbox-query'
import { useAdminApi } from '#layers/admin/app/composables/useAdminApi'

/**
 * 관리자 운영 인박스 API(D-248). useAdminApi(관리자 쿠키·CSRF·401 처리) 경유이며 로딩·에러 상태는 호출부가 가진다.
 * 보류 해제(DELETE)는 보류 항목이 목록에 나오지 않아 화면에서 쓰지 않는다(P1b-1).
 */
export function useAdminInbox() {
  const api = useAdminApi()

  function list(state: InboxQueryState): Promise<InboxResponse> {
    return api<InboxResponse>('/v1/admin/inbox', { query: toInboxApiParams(state) })
  }

  /** 보류 생성·갱신(204). 대기 항목이 아니면 404 INBOX_ITEM_NOT_FOUND. */
  function snooze(body: InboxSnoozeBody): Promise<void> {
    return api<void>('/v1/admin/inbox/snoozes', { method: 'PUT', body })
  }

  /** 셀러 지연 패널 정보(D-252 · 건수는 조회 시점 재계산). 없는 셀러 404 SELLER_NOT_FOUND. */
  function sellerDelay(sellerPublicId: string): Promise<AdminSellerDelay> {
    return api<AdminSellerDelay>(`/v1/admin/inbox/seller-delays/${encodeURIComponent(sellerPublicId)}`)
  }

  /** 셀러 지연 독촉(D-252 · 1~20곳 · 셀러별 결과와 무관하게 200). */
  function nudgeSellers(sellerPublicIds: string[]): Promise<AdminSellerNudgeResponse> {
    return api<AdminSellerNudgeResponse>('/v1/admin/inbox/seller-delays/nudge', { method: 'POST', body: { sellerPublicIds } })
  }

  return { list, snooze, sellerDelay, nudgeSellers }
}
