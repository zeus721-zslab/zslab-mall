<script setup lang="ts">
import type { AdminReconciliationIssue } from '#layers/admin/app/types/admin-reconciliation'
import {
  RECONCILIATION_ISSUE_STATUS_LABEL,
  RECONCILIATION_ISSUE_STATUS_SEMANTIC,
} from '#layers/admin/app/lib/constants/reconciliation'
import {
  reconciliationFacts,
  reconciliationSourceLabel,
  reconciliationSummary,
  reconciliationTypeLabel,
} from '#layers/admin/app/lib/admin-reconciliation-view'
import { semanticChipClass } from '#layers/admin/app/lib/constants/semantic'
import { formatDateTime } from '~/lib/utils/datetime'

/**
 * 불일치 행 목록(Track 104-2 FE-66). 불일치 목록 화면과 주문 상세 불일치 섹션이 같이 쓴다 — 행마다 유형·상태·요약·세부 사실·감지 시각,
 * 해결된 행은 처리자·메모, 열린 행은 "해결 처리" 버튼. 주문 링크는 목록 화면에서만 보인다(showOrder). 호출·다이얼로그는 부모가 소유한다.
 */
defineProps<{
  issues: AdminReconciliationIssue[]
  showOrder?: boolean
}>()
const emit = defineEmits<{ resolve: [issue: AdminReconciliationIssue]; openOrder: [issue: AdminReconciliationIssue] }>()
</script>

<template>
  <div>
    <div v-for="issue in issues" :key="issue.issueId" class="py-3 adm-reconciliation-row" data-testid="reconciliation-issue">
      <div class="d-flex align-center flex-wrap ga-2">
        <span class="text-body-2 font-weight-bold" data-testid="reconciliation-type">{{ reconciliationTypeLabel(issue.issueType) }}</span>
        <v-chip
          :class="semanticChipClass(RECONCILIATION_ISSUE_STATUS_SEMANTIC[issue.status])"
          size="x-small"
          variant="flat"
          data-testid="reconciliation-status"
        >{{ RECONCILIATION_ISSUE_STATUS_LABEL[issue.status] }}</v-chip>
        <span class="text-caption text-medium-emphasis">{{ reconciliationSourceLabel(issue.detail) }} · {{ formatDateTime(issue.detectedAt) }}</span>
        <v-spacer />
        <v-btn
          v-if="showOrder && issue.orderId"
          size="x-small"
          variant="text"
          data-testid="reconciliation-open-order"
          @click="emit('openOrder', issue)"
        >{{ issue.orderNo ?? issue.orderId }}</v-btn>
        <span v-else-if="showOrder" class="text-caption text-medium-emphasis">주문 없음</span>
        <v-btn
          v-if="issue.status === 'OPEN'"
          size="x-small"
          variant="flat"
          class="op-risk-action"
          data-testid="reconciliation-resolve"
          @click="emit('resolve', issue)"
        >해결 처리</v-btn>
      </div>
      <p class="text-body-2 mt-1 mb-0" data-testid="reconciliation-summary">{{ reconciliationSummary(issue) }}</p>
      <p v-if="reconciliationFacts(issue.detail).length > 0" class="text-caption text-medium-emphasis mb-0" data-testid="reconciliation-facts">
        {{ reconciliationFacts(issue.detail).join(' · ') }}
        <template v-if="issue.pgTid"> · PG 거래번호 {{ issue.pgTid }}</template>
        <template v-if="issue.pgRefundId"> · PG 환불번호 {{ issue.pgRefundId }}</template>
      </p>
      <p v-if="issue.status === 'RESOLVED'" class="text-caption mb-0" data-testid="reconciliation-resolution">
        해결 {{ issue.resolvedAt ? formatDateTime(issue.resolvedAt) : '—' }} · {{ issue.resolvedBySystem ? '시스템' : (issue.resolvedByName ?? '관리자') }} — {{ issue.resolutionMemo }}
      </p>
    </div>
  </div>
</template>

<style scoped>
.adm-reconciliation-row + .adm-reconciliation-row {
  border-top: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
}
</style>
