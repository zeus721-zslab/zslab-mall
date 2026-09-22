import { DELIVERY_CARRIER_CODES, type DeliveryCarrier } from '~/lib/constants/delivery'

/**
 * 출고 다이얼로그가 기억하는 "직전에 고른 택배사" 판정 단일 소스(Track 99 FE-61). 운영자는 대개 같은 택배사로 계속 출고하는데
 * 다이얼로그가 매번 빈 상태로 열려 택배사를 다시 골라야 했다(라운드 2 실측: 출고 1건당 입력 2필드 중 1개가 택배사).
 *
 * 저장 매체는 쿠키다 — 관리자·셀러 화면은 SSR이라 localStorage는 서버 렌더 단계에서 쓸 수 없고, 이 저장소에 브라우저 저장 선례가 없다
 * (세션은 전부 useCookie·app/stores/auth.ts 관례). 쿠키에는 택배사 enum 코드만 담으며, 사람이 고친 값·구 버전 값은 전부 무시한다.
 */

/** 쿠키 수명(초·30일). 세션 쿠키보다 길게 둬 다음 로그인에서도 직전 택배사가 남는다. */
export const LAST_CARRIER_MAX_AGE_SECONDS = 30 * 24 * 60 * 60

/**
 * 쿠키 문자열을 택배사 코드로 해석한다. 값 집합(BE DeliveryCarrier enum 4값) 밖이면 null —
 * 호출부는 null을 "기본 선택 없음"(현행 동작)으로 다룬다.
 */
export function parseLastCarrier(raw: string | null | undefined): DeliveryCarrier | null {
  if (typeof raw !== 'string') return null
  return DELIVERY_CARRIER_CODES.includes(raw as DeliveryCarrier) ? (raw as DeliveryCarrier) : null
}
