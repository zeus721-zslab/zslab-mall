import { IRREVERSIBLE, riskConfirmMessage } from '~/lib/utils/risk-confirm'

/**
 * 배송 상수 단일 소스(사용자 영역·FE-29). BE DeliveryCarrier enum 4값·DeliveryStatus·DeliveryDirection과 정합. 관리자 레이어는 자체 상수
 * (admin-order.ts)를 쓰며 base 레이어는 admin 레이어를 참조할 수 없어 값만 동일하게 둔다.
 */

/** 택배사 code(BE DeliveryCarrier enum 4값). */
export type DeliveryCarrier = 'CJ' | 'HANJIN' | 'POST' | 'LOGEN'

export const DELIVERY_CARRIER_LABELS: Record<DeliveryCarrier, string> = {
  CJ: 'CJ대한통운',
  HANJIN: '한진택배',
  POST: '우체국택배',
  LOGEN: '로젠택배',
}

/** 드롭다운 노출용 택배사 목록(정의 순서 유지). */
export const DELIVERY_CARRIER_CODES: DeliveryCarrier[] = ['CJ', 'HANJIN', 'POST', 'LOGEN']

/** 택배사 라벨 변환. 매핑에 없는 값은 원본 폴백(방어). */
export function deliveryCarrierLabel(code: string): string {
  return DELIVERY_CARRIER_LABELS[code as DeliveryCarrier] ?? code
}

/**
 * 송장번호 형식(BE Delivery.TRACKING_NO_PATTERN·D-227). 앞뒤 공백을 제거한 값에 적용한다. 송장 입력 화면 전부(구매자 회수·관리자·셀러)가
 * 이 규칙과 문구를 쓴다 — 레이어끼리는 서로 import할 수 없어 base에 둔다.
 */
export const DELIVERY_TRACKING_NO_PATTERN = /^[A-Za-z0-9-]{8,20}$/
export const DELIVERY_TRACKING_NO_FORMAT_MESSAGE = '송장번호는 숫자·영문·하이픈 8~20자로 입력해 주세요.'
/** 송장번호 입력 최대 길이(형식 규칙 상한). */
export const DELIVERY_TRACKING_NO_MAX = 20

/** 송장 등록 실패 응답(RFC7807 + BE fieldErrors) 중 송장 필드 오류만 읽기 위한 최소 형태. */
export interface TrackingNoErrorLike {
  data?: { fieldErrors?: { field?: string; message?: string }[] }
}

/** 400 VALIDATION_FAILED의 trackingNo 필드 문구(없으면 null). 구매자 회수 송장 화면이 서버 문구를 그대로 보여 줄 때 쓴다. */
export function trackingNoFieldError(error: TrackingNoErrorLike): string | null {
  const fieldErrors = error.data?.fieldErrors
  if (!Array.isArray(fieldErrors)) return null
  const matched = fieldErrors.find((entry) => entry.field === 'trackingNo' && typeof entry.message === 'string' && entry.message !== '')
  return matched?.message ?? null
}

/** 배송 상태 code(BE DeliveryStatus READY·SHIPPING·DELIVERED). 회수 송장은 등록 시 SHIPPING·회수 확인 시 DELIVERED. */
export type DeliveryStatus = 'READY' | 'SHIPPING' | 'DELIVERED'

/** 배송 방향(BE DeliveryDirection·Track 81-A). RETURN=반품 회수·OUTBOUND=발송(재발송 포함). */
export type DeliveryDirection = 'OUTBOUND' | 'RETURN'

/**
 * 배송완료 처리 확인 문구(Track 102 FE-64). 관리자·셀러 배송 화면이 같은 조작을 가지므로 문구도 한 곳에서 낸다
 * (레이어끼리는 서로 import할 수 없어 base에 둔다).
 */
export const MARK_DELIVERED_CONFIRM_MESSAGE = riskConfirmMessage(
  '선택한 배송을 배송완료로 바꿉니다.\n구매확정 기한(배송완료 기준)이 이때부터 계산됩니다.',
  IRREVERSIBLE,
)
