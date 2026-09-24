/**
 * 클레임 요청 실패 응답(RFC7807) → 사용자 문구(FE-29). BE는 반품 요청 조건 위반을 전부 422 CLAIM_STATE_INVALID로 내리고 detail 메시지로만
 * 구분하므로(ClaimService.validateReturnRequest) detail의 고정 문구를 부분 일치로 판별한다. 문구가 바뀌면 일반 422 안내로 폴백한다.
 */

export interface ClaimRequestErrorLike {
  statusCode?: number
  data?: { detail?: unknown; code?: unknown }
}

/** BE detail 부분 문자열 → 사용자 문구(순서 = 우선순위). */
const RETURN_422_MESSAGES: { match: string; message: string }[] = [
  { match: '반품 가능 기간', message: '반품 가능 기간(배송완료 후 7일)이 지나 요청할 수 없습니다.' },
  { match: '교환 가능 기간', message: '교환 가능 기간(배송완료 후 7일)이 지나 요청할 수 없습니다.' },
  { match: '반품 사유로 사용할 수 없는', message: '선택한 사유로는 반품을 요청할 수 없습니다.' },
  { match: '교환 사유로 사용할 수 없는', message: '선택한 사유로는 교환을 요청할 수 없습니다.' },
  // FAIL 이력 문구는 "…반품을/교환을 다시 요청할 수 없습니다"라 유형 문구(반품·교환)보다 먼저 매칭한다.
  { match: '검수 불합격 이력', message: '검수 불합격 이력이 있는 상품은 반품·교환을 다시 요청할 수 없습니다.' },
  { match: '다시 교환할 수 없습니다', message: '이미 교환한 상품은 다시 교환할 수 없습니다.' },
  { match: '같은 상품의 옵션', message: '같은 상품의 다른 옵션으로만 교환할 수 있습니다.' },
  { match: '같은 가격의 옵션', message: '같은 가격의 옵션으로만 교환할 수 있습니다.' },
  { match: '같은 옵션으로는', message: '현재와 같은 옵션으로는 교환할 수 없습니다.' },
  { match: '판매 중이 아닌 옵션', message: '판매 중이 아닌 옵션으로는 교환할 수 없습니다.' },
  { match: '배송완료 품목만', message: '배송완료된 상품만 요청할 수 있습니다.' },
  { match: '배송완료 기록이 없어', message: '배송완료 기록이 없어 요청할 수 없습니다.' },
  { match: '이미 진행 중인 클레임', message: '이미 진행 중인 취소·반품·교환 요청이 있습니다.' },
]

const DEFAULT_422 = '현재 상태에서는 요청할 수 없습니다.'
const DEFAULT_400 = '요청 정보를 확인하세요.'
const ATTACHMENT_400 = '사진 첨부 조건을 확인하세요(반품·교환·상품불량/오배송·최대 5장·본인이 올린 사진).'
const EXCHANGE_OPTION_400 = '교환 옵션을 확인하세요(교환 요청은 옵션 선택이 필요하며 반품·취소에는 지정할 수 없습니다).'

function detailOf(error: ClaimRequestErrorLike): string {
  const detail = error.data?.detail
  return typeof detail === 'string' ? detail : ''
}

/** 422/400 실패를 사용자 문구로 바꾼다. 401·404·기타는 호출부가 별도 처리한다(null 반환). */
export function claimRequestErrorMessage(error: ClaimRequestErrorLike): string | null {
  const detail = detailOf(error)
  if (error.statusCode === 422) {
    const matched = RETURN_422_MESSAGES.find((entry) => detail.includes(entry.match))
    return matched ? matched.message : DEFAULT_422
  }
  if (error.statusCode === 400) {
    if (detail.includes('첨부')) return ATTACHMENT_400
    if (detail.includes('교환 옵션')) return EXCHANGE_OPTION_400
    return DEFAULT_400
  }
  return null
}
