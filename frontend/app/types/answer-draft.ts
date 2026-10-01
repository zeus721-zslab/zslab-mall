import type { AnswerEvidenceKind } from '~/lib/constants/answer-draft'

/** 답안 초안 근거 1건(BE AnswerEvidence). */
export interface AnswerEvidence {
  kind: AnswerEvidenceKind
  title: string
  summary: string
}

/**
 * 답안 초안 응답(BE AnswerDraftResponse · 조회 시 계산). draft는 근거가 없으면 null이다(BE가 키를 남긴다). faqCandidate는 1:1 문의에서 FAQ 근거가
 * 0건일 때만 true다.
 */
export interface AnswerDraftResponse {
  draft: string | null
  evidence: AnswerEvidence[]
  faqCandidate: boolean
}
