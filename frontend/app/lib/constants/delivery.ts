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

/** 송장번호 최대 길이(BE ReturnShipmentRequest @Size). */
export const DELIVERY_TRACKING_NO_MAX = 100

/** 배송 상태 code(BE DeliveryStatus READY·SHIPPING·DELIVERED). 회수 송장은 등록 시 SHIPPING·회수 확인 시 DELIVERED. */
export type DeliveryStatus = 'READY' | 'SHIPPING' | 'DELIVERED'

/** 배송 방향(BE DeliveryDirection·Track 81-A). RETURN=반품 회수·OUTBOUND=발송(재발송 포함). */
export type DeliveryDirection = 'OUTBOUND' | 'RETURN'
