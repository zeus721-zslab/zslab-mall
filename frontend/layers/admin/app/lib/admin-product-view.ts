import type { AdminProductBulkResponse, AdminProductSummary } from '#layers/admin/app/types/admin-product'
import type { AdminSemantic } from '#layers/admin/app/lib/constants/semantic'

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
