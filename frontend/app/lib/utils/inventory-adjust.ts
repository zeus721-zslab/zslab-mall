/**
 * 재고 조정 대량 입력 경고(Track 101-A). 재고 증감은 반대 방향 재조정으로 되돌릴 수 있지만, 자릿수 오타(100 → 1000)는
 * 그 사이에 주문이 들어오면 oversell·품절로 번지므로 저장 전에 한 번 확인받는다. **값 자체는 막지 않는다** — BE에도 상한이
 * 없고(AdminInventoryAdjustRequest·Seller*Request 모두 Bean Validation 없음) 정당한 대량 입고를 차단하면 안 되기 때문이다.
 *
 * 관리자(상품 폼의 variant 재고 수정 → delta)와 셀러(입고·출고 다이얼로그)가 같은 임계·같은 문구를 쓴다.
 */

/** 1회 조정 경고 임계(개). 이 값 이상(절댓값)이면 확인 1회. */
export const INVENTORY_ADJUST_WARN_THRESHOLD = 1000

/** 확인 대상 판정에 필요한 조정 1건. delta는 부호 있는 증감량(양수=증가·음수=감소), label은 화면 표시용 이름. */
export interface InventoryAdjustLine {
  label: string
  delta: number
}

/** 단일 수량이 경고 임계 이상인지. NaN·Infinity는 false(폼 검증이 먼저 걸러낸다). */
export function isLargeInventoryAdjust(quantity: number): boolean {
  return Number.isFinite(quantity) && Math.abs(quantity) >= INVENTORY_ADJUST_WARN_THRESHOLD
}

/** 임계 이상인 조정만 추린다. 빈 배열이면 확인 없이 저장한다. */
export function largeInventoryAdjusts(lines: InventoryAdjustLine[]): InventoryAdjustLine[] {
  return lines.filter((line) => isLargeInventoryAdjust(line.delta))
}

/** 확인 다이얼로그 본문. 대상이 여러 건이면 줄바꿈으로 나열한다. 빈 배열은 빈 문자열(다이얼로그가 열리지 않는 경우). */
export function largeInventoryAdjustMessage(lines: InventoryAdjustLine[]): string {
  if (lines.length === 0) return ''
  const rows = lines.map((line) => `· ${line.label}: ${line.delta > 0 ? '+' : ''}${line.delta}개`).join('\n')
  return `${INVENTORY_ADJUST_WARN_THRESHOLD}개 이상을 한 번에 조정합니다.\n${rows}\n수량을 다시 확인해 주세요.`
}
