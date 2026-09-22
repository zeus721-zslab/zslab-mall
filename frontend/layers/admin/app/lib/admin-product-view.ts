import type { AdminProductBulkResponse, AdminProductSummary } from '#layers/admin/app/types/admin-product'
import type { AdminSemantic } from '#layers/admin/app/lib/constants/semantic'
import type { AdminProductStatus, AdminProductStatusTarget, AdminSaleStopSource } from '#layers/admin/app/lib/constants/product'
import { ADMIN_PRODUCT_ALLOWED_TRANSITIONS, ADMIN_PRODUCT_STATUS_TARGETS } from '#layers/admin/app/lib/constants/product'

/**
 * 표시 규칙(FE-25·순수 함수): 품절 표시 분기·결과→의미 색상 매핑·일괄 결과 집계. 컴포넌트·페이지가 공유하고 vitest가 직접 검증한다.
 * 색은 "요청 성공 여부"가 아니라 "결과의 의미"(constants/semantic.ts)로 고른다.
 */

/** 품절 표시: 수동 품절 → "품절(수동)"(수동이면 판정도 품절이라 우선), 재고 판정 품절 → "품절(재고)", 아니면 "재고 있음". 품절은 danger·판매 가능은 success. */
export function soldOutLabel(item: Pick<AdminProductSummary, 'soldOut' | 'soldOutManual'>): { text: string; semantic: AdminSemantic } {
  if (item.soldOutManual) return { text: '품절(수동)', semantic: 'danger' }
  if (item.soldOut) return { text: '품절(재고)', semantic: 'danger' }
  return { text: '재고 있음', semantic: 'success' }
}

// ---------- 판매중지 주체·제재 전환(D-206 보정·FE-57) ----------

export type SaleStateLike = { status: AdminProductStatus; saleStopSource?: AdminSaleStopSource | null }

/** 셀러가 중지한 상품인가(관리자 중지로 전환 대상). */
export function isSellerStopped(item: SaleStateLike): boolean {
  return item.status === 'STOPPED' && item.saleStopSource === 'SELLER'
}

/** 제재 전환 = 셀러 중지 상품에 STOPPED 목표(BE는 status 유지·주체만 ADMIN). */
export function isEscalation(item: SaleStateLike, target: AdminProductStatusTarget): boolean {
  return target === 'STOPPED' && isSellerStopped(item)
}

/** 상태 전환 메뉴 허용 목표: 기본 전이표 + 셀러 중지 상품은 STOPPED(전환) 추가. 관리자 중지 상품은 기존대로 SALE만. */
export function statusTargetsFor(item: SaleStateLike): AdminProductStatusTarget[] {
  const base = ADMIN_PRODUCT_ALLOWED_TRANSITIONS[item.status] ?? []
  return isSellerStopped(item) ? [...base, 'STOPPED'] : base
}

export const ESCALATE_STOP_TITLE = '관리자 중지로 전환'

/** 메뉴 항목 라벨: 셀러 중지 상품의 STOPPED 목표만 "관리자 중지로 전환", 그 외는 고정 라벨. */
export function statusTargetTitle(item: SaleStateLike, target: AdminProductStatusTarget): string {
  if (isEscalation(item, target)) return ESCALATE_STOP_TITLE
  return ADMIN_PRODUCT_STATUS_TARGETS.find((entry) => entry.value === target)?.title ?? target
}

/** 전환 확인 문구(순간 판매 노출 없이 주체만 바뀜·셀러 재판매 불가 고지). */
export function escalateConfirmMessage(productName: string): string {
  return `${productName}은(는) 셀러가 판매중지한 상품입니다.
관리자 중지로 전환하면 판매중지 상태는 그대로 유지되고, 셀러는 재판매할 수 없게 됩니다(관리자만 해제 가능).`
}

/** 상태 전환 응답 뒤 행에 반영할 주체: STOPPED 결과는 관리자 경로라 항상 ADMIN, SALE 복귀는 없음(BE 응답에 주체 없음). */
export function saleStopSourceAfterAdminChange(status: AdminProductStatus): AdminSaleStopSource | undefined {
  return status === 'STOPPED' ? 'ADMIN' : undefined
}

/** 일괄 결과 중 제재 전환 건수(성공 항목 code ESCALATED_TO_ADMIN). */
export function countEscalated(response: AdminProductBulkResponse): number {
  return response.results.filter((item) => item.success && item.code === 'ESCALATED_TO_ADMIN').length
}

/** 품절 토글 결과: ON(품절)=danger·OFF(재고 회복)=success. */
export function soldOutToggleSemantic(soldOut: boolean): AdminSemantic {
  return soldOut ? 'danger' : 'success'
}

/** 일괄 변경 의도(색상 결정용): 상태 변경은 중립, 품절 변경은 ON/OFF 의미. */
export type BulkIntent = { kind: 'status' } | { kind: 'soldOut'; soldOut: boolean }

/**
 * 일괄 결과 토스트 집계: 전부 실패=danger·일부 실패=warning·전부 성공은 의도의 의미(품절 ON=danger / 품절 OFF=success / 상태 변경=info).
 */
export function summarizeBulkResult(
  response: AdminProductBulkResponse,
  intent: BulkIntent = { kind: 'status' },
): { message: string; semantic: AdminSemantic; hasFailure: boolean } {
  const hasFailure = response.failureCount > 0
  const allFailed = hasFailure && response.successCount === 0
  let semantic: AdminSemantic
  if (allFailed) semantic = 'danger'
  else if (hasFailure) semantic = 'warning'
  else if (intent.kind === 'soldOut') semantic = soldOutToggleSemantic(intent.soldOut)
  else semantic = 'info'
  return {
    message: `일괄 변경 완료 — 성공 ${response.successCount} / 실패 ${response.failureCount}`,
    semantic,
    hasFailure,
  }
}
