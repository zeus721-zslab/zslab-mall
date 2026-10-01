/**
 * 답안 초안 상수 단일 소스(D-253 · FE-107). 근거 종류는 BE AnswerEvidenceKind(응답 전용 enum)와 1:1 · 라벨 함수는 모르는 값이 와도 code를 그대로
 * 보여 준다(BE가 값을 먼저 늘린 배포 순서 대비).
 */

export type AnswerEvidenceKind = 'FAQ' | 'ANSWERED_QUESTION' | 'ORDER'
export const ANSWER_EVIDENCE_KINDS: AnswerEvidenceKind[] = ['FAQ', 'ANSWERED_QUESTION', 'ORDER']
export const ANSWER_EVIDENCE_KIND_LABELS: Record<AnswerEvidenceKind, string> = {
  FAQ: 'FAQ',
  ANSWERED_QUESTION: '이전 답변',
  ORDER: '주문',
}
export function answerEvidenceKindLabel(kind: string): string {
  return ANSWER_EVIDENCE_KIND_LABELS[kind as AnswerEvidenceKind] ?? kind
}
