import type { FaqItem } from '~/types/faq'

export type FaqListStatus = 'idle' | 'loading' | 'ready' | 'error'

/**
 * 공개 FAQ 전체(Track 106-3 · GET /v1/faqs · 비로그인 가능). 도우미를 처음 열 때 1회 받아 앱 상태(useState)로 탭 동안 재사용한다 —
 * 페이지를 옮겨도 다시 부르지 않는다. 실패하면 status = error로 두고 호출부(다시 시도 칩)가 load()를 다시 부른다.
 */
export function useFaqList() {
  const api = useBuyerApi()
  const items = useState<FaqItem[]>('faq-list-items', () => [])
  const status = useState<FaqListStatus>('faq-list-status', () => 'idle')
  let inFlight: Promise<void> | null = null

  async function fetchList(): Promise<void> {
    status.value = 'loading'
    try {
      items.value = await api<FaqItem[]>('/v1/faqs')
      status.value = 'ready'
    } catch (error) {
      // 도우미 화면이 오류 말풍선 + 다시 시도 칩으로 알린다. 원인은 콘솔에 남긴다.
      console.warn('[faq] FAQ 목록 조회 실패', error)
      status.value = 'error'
    }
  }

  /** 이미 받았으면 그대로 두고, 진행 중이면 그 요청을 기다린다. */
  function load(): Promise<void> {
    if (status.value === 'ready') return Promise.resolve()
    inFlight ??= fetchList().finally(() => {
      inFlight = null
    })
    return inFlight
  }

  return { items, status, load }
}
