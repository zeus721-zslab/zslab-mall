import { toRoleClaimAttachmentPath } from '~/lib/claim-attachment-path'

/**
 * 클레임 첨부 이미지 blob 로더(Track 90-D-1). BE가 준 첨부 경로(/api/v1/files/claims/**)를 셀러 접두사 별칭(/api/v1/seller/files/claims/**)으로
 * 바꿔 fetch한다(D-235 · 셀러 쿠키는 셀러 접두사 요청에만 실린다). 받은 blob으로 object URL을 만들어 표시한다.
 * 상태는 loading → ready | error(404/403 등 HTTP 상태 보존)이며, URL이 바뀌거나 스코프가 사라지면 object URL을 해제한다.
 */
export type SellerAttachmentImageStatus = 'loading' | 'ready' | 'error'

export class SellerAttachmentFetchError extends Error {
  readonly status: number

  constructor(status: number) {
    super(`attachment fetch failed: ${status}`)
    this.name = 'SellerAttachmentFetchError'
    this.status = status
  }
}

/** 첨부 URL을 셀러 별칭 경로로 받아 object URL로 바꾼다(순수·테스트 가능). 비-2xx는 SellerAttachmentFetchError(status). */
export async function fetchAttachmentObjectUrl(url: string, fetcher: typeof fetch = fetch): Promise<string> {
  const response = await fetcher(toRoleClaimAttachmentPath(url, 'seller'), { cache: 'no-store' })
  if (!response.ok) throw new SellerAttachmentFetchError(response.status)
  return URL.createObjectURL(await response.blob())
}

export function useSellerAttachmentImage(url: MaybeRefOrGetter<string>) {
  const src = ref<string | null>(null)
  const status = ref<SellerAttachmentImageStatus>('loading')
  const errorStatus = ref<number | null>(null)
  let requestSequence = 0

  function release(): void {
    if (src.value) URL.revokeObjectURL(src.value)
    src.value = null
  }

  async function load(): Promise<void> {
    const sequence = ++requestSequence
    release()
    status.value = 'loading'
    errorStatus.value = null
    try {
      const objectUrl = await fetchAttachmentObjectUrl(toValue(url))
      if (sequence !== requestSequence) {
        URL.revokeObjectURL(objectUrl) // 늦게 도착한 이전 요청의 blob은 바로 해제
        return
      }
      src.value = objectUrl
      status.value = 'ready'
    } catch (error) {
      if (sequence !== requestSequence) return
      errorStatus.value = error instanceof SellerAttachmentFetchError ? error.status : null
      status.value = 'error'
    }
  }

  watch(() => toValue(url), () => { void load() }, { immediate: true })
  onScopeDispose(() => { requestSequence++; release() })

  return { src, status, errorStatus, reload: load }
}
