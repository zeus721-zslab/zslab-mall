import type { AnswerDraftResponse } from '~/types/answer-draft'

/**
 * 답변 다이얼로그의 초안 로드 상태(D-253 · FE-107). 열 때마다 다시 계산한 초안을 받고, 늦게 도착한 이전 항목의 응답은 sequence로 버린다
 * (AdminInboxDetail 단건 로드 선례). 실패해도 답변은 그대로 쓸 수 있어 오류 여부만 남긴다.
 */
export function useAnswerDraft(fetchDraft: (id: string) => Promise<AnswerDraftResponse>) {
  const result = ref<AnswerDraftResponse | null>(null)
  const loading = ref(false)
  const failed = ref(false)
  let sequence = 0

  async function load(id: string): Promise<void> {
    const current = ++sequence
    result.value = null
    failed.value = false
    loading.value = true
    try {
      const response = await fetchDraft(id)
      if (current !== sequence) return
      result.value = response
    } catch (error) {
      if (current !== sequence) return
      // 초안은 보조 정보라 다이얼로그 안 안내 1줄로 대신한다(토스트 없음) — 원인 추적용으로만 남긴다.
      console.warn('[answer-draft] 초안 조회 실패', error)
      failed.value = true
    } finally {
      if (current === sequence) loading.value = false
    }
  }

  /** 닫을 때: 진행 중 응답을 버리고 상태를 비운다. */
  function reset(): void {
    sequence++
    result.value = null
    failed.value = false
    loading.value = false
  }

  return { result, loading, failed, load, reset }
}
