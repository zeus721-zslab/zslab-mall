<script setup lang="ts">
import type { AdminReconciliationIssue } from '#layers/admin/app/types/admin-reconciliation'
import { reconciliationFacts, reconciliationSourceLabel, reconciliationSummary } from '#layers/admin/app/lib/admin-reconciliation-view'
import { toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminReconciliationIssues } from '#layers/admin/app/composables/useAdminReconciliationIssues'
import { formatDateTime } from '~/lib/utils/datetime'

/**
 * 인박스 정합성 불일치 패널(D-251 · 상세 패널 안). 단건 조회로 사유·주문·PG 식별자·세부 사실을 보이고, 열린 건만 기존 해결 다이얼로그로 해결한다.
 * 이미 해결된 건(자동 해소 등 · 422)은 다이얼로그가 stale로 알려 페이지가 다시 읽는다.
 */
const props = defineProps<{ issueRef: string }>()
const emit = defineEmits<{ processed: [outcome: 'done' | 'stale'] }>()

const reconciliationApi = useAdminReconciliationIssues()

const issue = ref<AdminReconciliationIssue | null>(null)
const loading = ref(false)
const loadError = ref<string | null>(null)
let loadSequence = 0

async function load(issueRef: string): Promise<void> {
  const sequence = ++loadSequence
  loading.value = true
  loadError.value = null
  issue.value = null
  try {
    const response = await reconciliationApi.get(Number(issueRef))
    if (sequence !== loadSequence) return
    issue.value = response
  } catch (error) {
    if (sequence !== loadSequence) return
    loadError.value = toAdminErrorMessage(error)
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

const resolveTarget = ref<AdminReconciliationIssue | null>(null)
function onResolved(outcome: 'done' | 'stale'): void {
  resolveTarget.value = null
  emit('processed', outcome)
}

// 항목이 바뀌면 열린 다이얼로그를 닫고 새로 읽는다(다이얼로그 상태 선언 뒤에 둔다 — immediate 실행).
watch(() => props.issueRef, (issueRef) => {
  resolveTarget.value = null
  void load(issueRef)
}, { immediate: true })
</script>

<template>
  <div data-testid="inbox-reconciliation-panel">
    <v-skeleton-loader v-if="loading" type="list-item-three-line" />
    <v-alert v-else-if="loadError" type="error" variant="tonal" density="compact" data-testid="inbox-reconciliation-error">{{ loadError }}</v-alert>
    <template v-else-if="issue">
      <v-row dense class="mb-2">
        <v-col cols="12">
          <div class="text-caption text-medium-emphasis">사유</div>
          <div class="text-body-2" data-testid="inbox-reconciliation-summary">{{ reconciliationSummary(issue) }}</div>
        </v-col>
        <v-col cols="6">
          <div class="text-caption text-medium-emphasis">주문</div>
          <div class="text-body-2">{{ issue.orderNo ?? '-' }}</div>
        </v-col>
        <v-col cols="6">
          <div class="text-caption text-medium-emphasis">기록</div>
          <div class="text-body-2">{{ reconciliationSourceLabel(issue.detail) }} · {{ formatDateTime(issue.detectedAt) }}</div>
        </v-col>
        <v-col v-if="issue.pgTid || issue.pgRefundId" cols="12">
          <div class="text-caption text-medium-emphasis">PG 식별자</div>
          <div class="text-body-2">{{ [issue.pgTid, issue.pgRefundId].filter(Boolean).join(' · ') }}</div>
        </v-col>
        <v-col v-if="reconciliationFacts(issue.detail).length > 0" cols="12">
          <div class="text-caption text-medium-emphasis">세부</div>
          <div class="text-body-2" data-testid="inbox-reconciliation-facts">{{ reconciliationFacts(issue.detail).join(' · ') }}</div>
        </v-col>
      </v-row>
      <div class="d-flex flex-wrap ga-2 mb-2">
        <v-btn v-if="issue.status === 'OPEN'" color="primary" variant="flat" data-testid="inbox-reconciliation-resolve" @click="resolveTarget = issue">
          해결 처리
        </v-btn>
      </div>
    </template>

    <AdminReconciliationResolveDialog
      :open="resolveTarget !== null"
      :issue="resolveTarget"
      @done="onResolved('done')"
      @stale="onResolved('stale')"
      @cancel="resolveTarget = null"
    />
  </div>
</template>
