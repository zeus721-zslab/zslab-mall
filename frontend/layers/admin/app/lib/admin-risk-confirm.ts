import { IRREVERSIBLE, reversibleBy, riskConfirmMessage } from '~/lib/utils/risk-confirm'

/**
 * 관리자 위험 조작 확인 문구 모음(Track 102 FE-64·순수 함수·vitest 대상).
 *
 * 페이지 template 안에서 템플릿 문자열로 조립하던 문구를 여기로 옮겼다. 흩어져 있으면 가역성 줄이 다시 빠지고,
 * 무엇보다 "어떤 조작에 확인 문구가 있는지"를 한곳에서 셀 수 없다. 화면별 고유 문구(셀러 상태 전이·상품 거부·운영자 회수)는
 * 각 도메인 view 파일이 이미 갖고 있어 그쪽에서 같은 riskConfirmMessage를 쓴다.
 *
 * 모든 함수는 riskConfirmMessage를 거치므로 마지막 줄이 항상 가역성이다(admin-risk-confirm.spec.ts가 전수 검사).
 */

/** 정산 확정(PENDING → CONFIRMED). 셀러 공개 + SMS 발송이 함께 일어난다. */
export function settlementConfirmMessage(periodLabel: string, companyName: string): string {
  return riskConfirmMessage(
    `${periodLabel} ${companyName} 정산을 확정합니다.\n확정 즉시 셀러에게 공개되고 SMS가 발송됩니다.`,
    IRREVERSIBLE,
  )
}

/** 정산 지급완료(CONFIRMED → PAID). 지급 시점의 주 정산계좌가 기록된다. */
export function settlementPayMessage(periodLabel: string, companyName: string, amount: string, bankAccountText: string): string {
  return riskConfirmMessage(
    `${periodLabel} ${companyName} 정산 ${amount}을 지급완료로 표시합니다.\n계좌: ${bankAccountText}\n지급 시점의 주 정산계좌가 함께 기록됩니다.`,
    IRREVERSIBLE,
  )
}

/** 상품 삭제(목록에서 사라짐). */
export function productDeleteMessage(productName: string): string {
  return riskConfirmMessage(
    `${productName}을(를) 삭제합니다.\n삭제된 상품은 관리자 목록·사용자 화면에서 사라집니다.`,
    IRREVERSIBLE,
  )
}

/** 카테고리 삭제. 같은 이름으로 다시 만들 수 있어 가역이다(연결된 상품이 있으면 BE가 막는다). */
export function categoryDeleteMessage(displayName: string): string {
  return riskConfirmMessage(
    `"${displayName}" 카테고리를 삭제합니다.\n상품 등록 드롭다운과 카탈로그 탭에서 즉시 사라집니다.`,
    reversibleBy('같은 이름으로 다시 등록할 수 있습니다'),
  )
}

/** 회원 탈퇴 처리. */
export function memberWithdrawMessage(name: string, email: string): string {
  return riskConfirmMessage(
    `${name}(${email}) 회원을 탈퇴 처리합니다.\n진행 중인 주문이나 클레임이 있으면 처리되지 않습니다.`,
    IRREVERSIBLE,
  )
}

/** 임시 비밀번호 발급. 기존 비밀번호는 사라지고 세션이 끊긴다. */
export function temporaryPasswordMessage(phone: string): string {
  return riskConfirmMessage(
    `새 임시 비밀번호를 발급해 화면에 1회 표시하고, 등록된 연락처(${phone})로 SMS도 발송합니다.`
      + '\n기존 비밀번호는 즉시 무효가 되고 로그인 세션도 종료됩니다.',
    IRREVERSIBLE,
  )
}

/** 환불 개시(수동). 금액 입력은 폼이 받고, 이 문구는 무엇이 일어나는지와 가역성만 말한다. */
export function refundInitiateMessage(productName: string): string {
  return riskConfirmMessage(
    `${productName} 환불을 수동으로 개시합니다(자동 환불이 붙지 않았거나 실패한 경우에만 사용).`,
    IRREVERSIBLE,
  )
}
