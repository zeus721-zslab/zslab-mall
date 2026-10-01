import type { InboxResponse, InboxSnoozeBody } from '~/types/inbox'
import type { InboxQueryState } from '~/lib/inbox-query'
import { toInboxApiParams } from '~/lib/inbox-query'

/**
 * 셀러 운영 인박스 API(D-248 · 관리자 useAdminInbox 복제). useSellerApi(셀러 쿠키·CSRF·401·403 SELLER_SUSPENDED) 경유 · 자기 셀러 항목만 온다.
 */
export function useSellerInbox() {
  const api = useSellerApi()

  function list(state: InboxQueryState): Promise<InboxResponse> {
    return api<InboxResponse>('/v1/seller/inbox', { query: toInboxApiParams(state) })
  }

  /** 보류 생성·갱신(204). 자기 셀러 대기 항목이 아니면 404 INBOX_ITEM_NOT_FOUND · 정지 셀러 403 SELLER_SUSPENDED. */
  function snooze(body: InboxSnoozeBody): Promise<void> {
    return api<void>('/v1/seller/inbox/snoozes', { method: 'PUT', body })
  }

  return { list, snooze }
}
