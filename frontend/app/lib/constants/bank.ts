/**
 * 셀러 정산계좌 은행·형식 상수 단일 소스(Track 90-D-3·FE-51). 관리자(Track 89-F)와 셀러 본인 등록 폼이 같은 목록·한도를 쓰므로 레이어 밖
 * 공용에 두고, 관리자 레이어는 기존 ADMIN_* 이름으로 re-export한다(셀러 레이어는 admin import 금지·no-admin-import.spec).
 *
 * 은행 선택 옵션: bank_code VARCHAR(20)·BE는 자유 문자열·시드 KB/SHINHAN/WOORI와 같은 영문 약칭. 목록에 없는 코드는 화면이 코드 그대로 표기한다
 * (bankLabel). 실명인증 연동 시 금융결제원 표준 코드로 바꿀 수 있도록 code를 값으로 둔다.
 */
export const BANK_OPTIONS: { value: string; title: string }[] = [
  { value: 'KB', title: 'KB국민은행' },
  { value: 'SHINHAN', title: '신한은행' },
  { value: 'WOORI', title: '우리은행' },
  { value: 'HANA', title: '하나은행' },
  { value: 'NH', title: 'NH농협은행' },
  { value: 'IBK', title: 'IBK기업은행' },
  { value: 'SC', title: 'SC제일은행' },
  { value: 'CITI', title: '씨티은행' },
  { value: 'POST', title: '우체국' },
  { value: 'KAKAO', title: '카카오뱅크' },
  { value: 'TOSS', title: '토스뱅크' },
  { value: 'KBANK', title: '케이뱅크' },
  { value: 'BUSAN', title: '부산은행' },
  { value: 'DAEGU', title: 'iM뱅크(대구은행)' },
  { value: 'GWANGJU', title: '광주은행' },
  { value: 'JEONBUK', title: '전북은행' },
  { value: 'KYONGNAM', title: '경남은행' },
  { value: 'JEJU', title: '제주은행' },
  { value: 'SUHYUP', title: '수협은행' },
  { value: 'SAEMAUL', title: '새마을금고' },
  { value: 'SHINHYUP', title: '신협' },
]

/** BE SellerBankAccountRegisterRequest·AdminSellerBankAccountRegisterRequest @Size·@Pattern(계좌번호 숫자·하이픈 6~30자·bank_code 20·account_holder 50). */
export const BANK_CODE_MAX = 20
export const ACCOUNT_NUMBER_MIN = 6
export const ACCOUNT_NUMBER_MAX = 30
export const ACCOUNT_HOLDER_MAX = 50
export const ACCOUNT_NUMBER_PATTERN = /^[0-9-]+$/

/** 은행 코드 → 표시명. 목록에 없으면 코드 그대로. */
export function bankLabel(bankCode: string): string {
  return BANK_OPTIONS.find((option) => option.value === bankCode)?.title ?? bankCode
}
