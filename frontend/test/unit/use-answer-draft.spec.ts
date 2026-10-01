import { describe, it, expect, vi } from 'vitest'
import { useAnswerDraft } from '~/composables/useAnswerDraft'
import type { AnswerDraftResponse } from '~/types/answer-draft'
import { ANSWER_EVIDENCE_KINDS, answerEvidenceKindLabel } from '~/lib/constants/answer-draft'

/**
 * 답안 초안 로드 상태(D-253 · FE-107): 늦게 도착한 이전 항목 응답은 버린다(sequence) · 실패는 failed만 남긴다 · reset은 진행 중 응답을 버린다.
 */
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function response(draft: string | null): AnswerDraftResponse {
  return { draft, evidence: [], faqCandidate: false }
}

describe('useAnswerDraft', () => {
  it('앞선 요청이 늦게 끝나도 마지막 요청 결과만 남는다', async () => {
    const first = deferred<AnswerDraftResponse>()
    const second = deferred<AnswerDraftResponse>()
    const fetchDraft = vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const state = useAnswerDraft(fetchDraft)

    const firstLoad = state.load('a')
    const secondLoad = state.load('b')
    second.resolve(response('B 초안'))
    await secondLoad
    first.resolve(response('A 초안'))
    await firstLoad

    expect(fetchDraft.mock.calls).toEqual([['a'], ['b']])
    expect(state.result.value?.draft).toBe('B 초안')
    expect(state.loading.value).toBe(false)
  })

  it('실패 → failed · 결과 없음 / reset → 진행 중 응답 무시·상태 비움', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const state = useAnswerDraft(vi.fn().mockRejectedValueOnce({ status: 500 }))
    await state.load('a')
    expect(state.failed.value).toBe(true)
    expect(state.result.value).toBeNull()
    expect(warn).toHaveBeenCalled()

    const pending = deferred<AnswerDraftResponse>()
    const resetState = useAnswerDraft(vi.fn().mockReturnValueOnce(pending.promise))
    const load = resetState.load('a')
    resetState.reset()
    pending.resolve(response('늦은 초안'))
    await load
    expect(resetState.result.value).toBeNull()
    expect(resetState.loading.value).toBe(false)
    warn.mockRestore()
  })

  it('근거 종류 상수: BE AnswerEvidenceKind 3값 · 라벨 · 모르는 값은 code 그대로', () => {
    expect(ANSWER_EVIDENCE_KINDS).toEqual(['FAQ', 'ANSWERED_QUESTION', 'ORDER'])
    expect(answerEvidenceKindLabel('ANSWERED_QUESTION')).toBe('이전 답변')
    expect(answerEvidenceKindLabel('UNKNOWN')).toBe('UNKNOWN')
  })
})
