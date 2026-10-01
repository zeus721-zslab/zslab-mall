import type { InboxResponse, InboxSnoozeBody } from '~/types/inbox'
import type { InboxQueryState } from '~/lib/inbox-query'
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

  return { list, snooze }
}
