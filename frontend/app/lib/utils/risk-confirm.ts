/**
 * 위험 조작 확인 문구 형식 단일 소스(Track 102 FE-64·순수 함수·vitest 대상).
 *
 * 형식을 고정한다.
 *   앞 — 무엇이 일어나는지(대상 + 결과). 경고할 부수 효과가 여럿이면 줄을 더 쓸 수 있다.
 *   맨 끝 한 줄 — 되돌릴 수 있는지. 되돌릴 수 있으면 그 방법까지 적는다. 이 줄은 예외 없이 마지막에 붙는다.
 *
 * 앞을 1줄로 강제하지 않는 것은, 이미 있는 문구 중에 판단에 필요한 경고를 여러 줄로 적은 것(셀러 종료의 판매중 상품 수·
 * 주 정산계좌 없음)이 있어 1줄로 줄이면 정보가 사라지기 때문이다. 고정하는 것은 "가역성 줄이 항상 마지막에 있다"는 규칙이다.
 *
 * 형식으로 못박는 이유: 라운드 4 정찰에서 "무엇이 일어나는지"는 대부분 적혀 있었지만 가역성은 9곳에만 있었다.
 * 처음 쓰는 운영자가 버튼을 누르기 전에 확인해야 하는 것은 결과와 "되돌릴 수 있는가" 두 가지이고, 둘 중 하나가 빠지면
 * 문구가 있어도 판단이 서지 않는다. 호출부가 문자열을 직접 조립하면 다시 빠지므로 생성을 이 함수로만 한다.
 */

/** 되돌릴 수 있는지. reversible이면 how에 되돌리는 방법을 적는다(방법 없는 reversible은 허용하지 않는다). */
export type Reversal = { kind: 'irreversible' } | { kind: 'reversible'; how: string }

/** 되돌릴 수 없는 조작. */
export const IRREVERSIBLE: Reversal = { kind: 'irreversible' }

/** 되돌릴 수 있는 조작 — how는 "무엇을 하면 되돌아오는가"를 적는다(예: "거부 철회로 승인대기로 되돌릴 수 있습니다"). */
export function reversibleBy(how: string): Reversal {
  return { kind: 'reversible', how }
}

const IRREVERSIBLE_LINE = '되돌릴 수 없습니다.'

/** 가역성 1줄. 호출부가 직접 쓰지 않고 riskConfirmMessage를 통해 붙는다(형식 일탈 방지). */
export function reversalLine(reversal: Reversal): string {
  return reversal.kind === 'irreversible' ? IRREVERSIBLE_LINE : `되돌릴 수 있습니다: ${reversal.how}`
}

/**
 * 확인 다이얼로그 본문 2줄을 만든다. effect 끝에 마침표가 없으면 붙인다(호출부마다 문장 끝이 갈리지 않게).
 * 줄바꿈은 \n — AdminConfirmDialog가 white-space: pre-line으로 렌더한다.
 */
export function riskConfirmMessage(effect: string, reversal: Reversal): string {
  const trimmed = effect.trim()
  const head = trimmed.endsWith('.') || trimmed.endsWith('요') ? trimmed : `${trimmed}.`
  return `${head}\n${reversalLine(reversal)}`
}
