/**
 * 관리자 API 에러 코드(ProblemDetail.code) → 운영자 메시지(FE-25). 알 수 없는 코드는 detail 문자열 또는 일반 문구로 폴백한다.
 */
const ADMIN_ERROR_MESSAGES: Record<string, string> = {
  PRODUCT_NOT_FOUND: '상품을 찾을 수 없습니다(삭제되었거나 존재하지 않음).',
  PRODUCT_INVALID_STATE: '현재 상태에서 허용되지 않는 전환입니다.',
  PRODUCT_HAS_ORDER_HISTORY: '주문 이력이 있는 상품은 삭제할 수 없습니다. 판매중지로 전환하세요.',
  SELLER_NOT_FOUND: '셀러를 찾을 수 없습니다.',
  CATEGORY_NOT_FOUND: '카테고리를 찾을 수 없습니다.',
  // FE-38 관리자 카테고리 관리(Track 89-C)
  CATEGORY_DUPLICATE: '같은 이름의 카테고리가 이미 있습니다.',
  CATEGORY_HAS_PRODUCTS: '연결된 상품이 있는 카테고리는 삭제할 수 없습니다.',
  // FE-27 관리자 주문·클레임·배송
  ORDER_NOT_FOUND: '주문 또는 주문 품목을 찾을 수 없습니다.',
  CLAIM_NOT_FOUND: '클레임을 찾을 수 없습니다.',
  DELIVERY_NOT_FOUND: '배송 정보를 찾을 수 없습니다.',
  CLAIM_STATE_INVALID: '현재 상태에서 처리할 수 없는 클레임입니다(취소 불가 품목·진행 중 클레임 중복·클레임 진행 중 품목의 발송 처리 등).',
  DELIVERY_INVALID_STATE: '현재 배송 상태에서 허용되지 않는 처리입니다(발송 처리는 결제완료 품목, 배송완료·송장 수정은 배송중만).',
  // FE-37 관리자 배송 관리(Track 89-B)
  DELIVERY_TRACKING_NO_CONFLICT: '다른 배송이 이미 사용 중인 송장번호입니다.',
  ORDER_ITEM_INVALID_STATE: '현재 품목 상태에서 허용되지 않는 처리입니다.',
  OPTIMISTIC_LOCK_FAILURE: '이미 종료됐거나 결제가 완료된 주문입니다. 최신 상태를 다시 확인하세요.',
  VALIDATION_FAILED: '입력값을 확인해 주세요.',
  MALFORMED_REQUEST: '잘못된 요청입니다.',
  FORBIDDEN: '권한이 없습니다.',
  UNAUTHENTICATED: '로그인이 필요합니다.',
  INTERNAL_ERROR: '서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
  // Track 84 관리자 회원 관리
  USER_NOT_FOUND: '회원을 찾을 수 없습니다.',
  MEMBER_ACTIVITY_IN_PROGRESS: '진행 중인 주문 또는 클레임이 있어 탈퇴할 수 없습니다.',
  MEMBER_ALREADY_WITHDRAWN: '이미 탈퇴한 회원입니다.',
  MEMBER_PHONE_MISSING: '연락처가 없어 임시 비밀번호를 발송할 수 없습니다.',
  MEMBER_ADMIN_ROLE_ASSIGNED: '관리자 권한을 보유한 회원에게는 임시 비밀번호를 발급할 수 없습니다. 관리자 권한 해제 후 재발급하세요.',
  TEMPORARY_PASSWORD_DELIVERY_FAILED: 'SMS 발송에 실패했습니다. 비밀번호는 변경되지 않았습니다.',
  // FE-39 관리자 운영자 관리(Track 89-E). 도메인 403(SUPER_ADMIN 아님·자기 SUPER_ADMIN 회수)은 code=FORBIDDEN이라 서버 detail을 쓴다(admin-operator-view).
  ADMIN_OPERATOR_ALREADY_EXISTS: '이미 운영 관리자 역할을 보유한 회원입니다.',
  ROLE_ASSIGNMENT_NOT_FOUND: '회수할 역할이 없습니다(회원 미존재 또는 이미 회수됨).',
  LAST_SUPER_ADMIN: '마지막 슈퍼 관리자는 회수할 수 없습니다(시스템 잠금 방지).',
  // FE-40 관리자 셀러 관리(Track 89-D·D-187). SELLER_ACTIVITY_IN_PROGRESS는 응답 blocks로 건수를 조립한다(admin-seller-view.toSellerErrorMessage).
  SELLER_INVALID_STATE: '현재 셀러 상태에서 허용되지 않는 처리입니다.',
  SELLER_ACTIVITY_IN_PROGRESS: '미지급 정산·진행 중 주문·처리 중 클레임이 있어 종료할 수 없습니다.',
  SELLER_BUSINESS_NO_DUPLICATE: '이미 등록된 사업자번호입니다.',
  SELLER_USER_ALREADY_EXISTS: '이미 다른 셀러에 소속된 회원입니다.',
  // FE-41 셀러 정산계좌(Track 89-F·D-188).
  SELLER_BANK_ACCOUNT_NOT_FOUND: '정산계좌를 찾을 수 없습니다. 화면을 새로 고칩니다.',
  SELLER_BANK_ACCOUNT_REFERENCED: '정산 지급에 사용된 계좌는 수정할 수 없습니다. 새 계좌를 등록한 뒤 주 계좌로 전환하세요.',
  SELLER_BANK_ACCOUNT_INVALID_STATE: '이미 주 정산계좌입니다.',
  // FE-42 셀러 구성원(Track 89-G·D-189). 구성원 다이얼로그는 admin-seller-member-view.toSellerMemberErrorMessage가 맥락 문구로 덮는다.
  SELLER_MEMBER_NOT_FOUND: '셀러 구성원을 찾을 수 없습니다.',
  SELLER_LAST_OWNER: '마지막 활성 대표(OWNER)는 제거·강등할 수 없습니다.',
  SELLER_MEMBER_INVALID_STATE: '이미 같은 역할입니다.',
  EMAIL_ALREADY_EXISTS: '이미 사용 중인 이메일입니다.',
  // Track 85 관리자 정산(D-179). PERIOD_INVALID는 연·월 범위 위반과 미마감 월이 같은 코드라 둘을 함께 안내한다.
  SETTLEMENT_NOT_FOUND: '정산을 찾을 수 없습니다.',
  SETTLEMENT_PERIOD_INVALID: '정산 기간이 올바르지 않습니다. 연·월 범위를 확인하고, 아직 마감되지 않은 월은 생성할 수 없습니다.',
  SETTLEMENT_ALREADY_EXISTS: '같은 기간의 정산이 이미 생성되고 있습니다. 잠시 후 목록을 새로고침하세요.',
  SETTLEMENT_INVALID_STATE: '현재 정산 상태에서 허용되지 않는 처리입니다(확정·재생성은 확정 대기, 지급완료는 확정 상태만).',
  SETTLEMENT_NET_NEGATIVE: '지급액이 음수인 정산은 지급할 수 없습니다(차감 이월 필요).',
  SETTLEMENT_BANK_ACCOUNT_MISSING: '셀러의 주 정산계좌가 없어 지급할 수 없습니다.',
  // Track 104-2 관리자 불일치(D-216·FE-66).
  RECONCILIATION_ISSUE_NOT_FOUND: '불일치를 찾을 수 없습니다.',
  RECONCILIATION_ISSUE_INVALID_STATE: '이미 해결된 불일치입니다. 목록을 새로 불러옵니다.',
}

const FALLBACK_MESSAGE = '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'

/**
 * 원인이 여러 가지인데 코드가 하나뿐이라 코드 문구가 원인을 지워 버리는 코드(Track 101-A). BE가 detail에 실어 보낸 구체 사유를
 * 코드 문구보다 먼저 쓴다 — 예: 회수 송장 미등록 422는 CLAIM_STATE_INVALID로 내려오지만 detail에만 "회수 송장이 등록되지 않아…"가 있다.
 * detail이 비어 있으면 기존대로 코드 문구로 폴백한다.
 */
const DETAIL_FIRST_CODES: ReadonlySet<string> = new Set(['CLAIM_STATE_INVALID'])

/** ofetch 에러(FetchError)에서 ProblemDetail.code를 꺼낸다. 네트워크 오류·비JSON은 null. */
export function extractErrorCode(error: unknown): string | null {
  const code = (error as { data?: { code?: unknown } } | null)?.data?.code
  return typeof code === 'string' ? code : null
}

/** ofetch 에러에서 ProblemDetail.detail을 꺼낸다. 없거나 빈 문자열이면 null. */
function extractErrorDetail(error: unknown): string | null {
  const detail = (error as { data?: { detail?: unknown } } | null)?.data?.detail
  return typeof detail === 'string' && detail !== '' ? detail : null
}

/** DETAIL_FIRST_CODES는 서버 detail 우선 → 코드 우선 → 알 수 없으면 서버 detail → 일반 문구. */
export function toAdminErrorMessage(error: unknown): string {
  const code = extractErrorCode(error)
  const detail = extractErrorDetail(error)
  if (code && detail && DETAIL_FIRST_CODES.has(code)) return detail
  if (code && ADMIN_ERROR_MESSAGES[code]) return ADMIN_ERROR_MESSAGES[code]
  return detail ?? FALLBACK_MESSAGE
}

/** 일괄 결과 실패 항목의 code → 메시지(항목 message가 있으면 코드 문구 뒤에 병기하지 않고 코드 문구만). */
export function toBulkFailureMessage(code: string | undefined, message: string | undefined): string {
  if (code && ADMIN_ERROR_MESSAGES[code]) return ADMIN_ERROR_MESSAGES[code]
  return message && message !== '' ? message : FALLBACK_MESSAGE
}
