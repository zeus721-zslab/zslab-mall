import { DEFAULT_INBOX_QUERY } from '~/lib/inbox-query'
import { inboxTotal } from '~/lib/inbox-view'
import { useAdminInbox } from '#layers/admin/app/composables/useAdminInbox'

/**
 * 사이드바 "인박스" 메뉴 배지(오늘 탭 건수 · D-248). 사이드바와 인박스 페이지가 같은 값을 보도록 useState로 공유한다 — 인박스 페이지는 오늘 탭을
 * 읽을 때 set으로 바로 갱신하고, 사이드바는 처음 그릴 때와 창 포커스 때 refresh로 다시 읽는다. 실패하면 배지를 숨긴다(목록 화면이 오류를 보여 준다).
 */
export function useAdminInboxBadge() {
  const count = useState<number | null>('admin-inbox-today-count', () => null)
  const inboxApi = useAdminInbox()

  function set(value: number): void {
    count.value = value
  }

  async function refresh(): Promise<void> {
    try {
      const response = await inboxApi.list(DEFAULT_INBOX_QUERY)
      count.value = inboxTotal(response.counts)
    } catch (error) {
      // 배지는 보조 표시라 화면을 막지 않는다 — 원인은 인박스 화면 진입 시 오류 안내로 드러난다.
      console.warn('[inbox] 메뉴 배지 건수를 불러오지 못했습니다', error)
      count.value = null
    }
  }

  return { count, set, refresh }
}
