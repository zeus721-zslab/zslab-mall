<script setup lang="ts">
import type { AdminClaimBulkApproveResponse } from '#layers/admin/app/types/admin-claim'
import type { InboxItem } from '~/lib/inbox-view'
import { INBOX_BULK_APPROVE_MAX, bulkApproveConfirmMessage, summarizeClaimBulkApprove } from '#layers/admin/app/lib/admin-inbox-view'
import { toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminClaims } from '#layers/admin/app/composables/useAdminClaims'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 인박스 클레임 일괄 승인(D-250): 선택 바 · 확인 다이얼로그(유형별 건수 · 취소 즉시 환불) · 실행 · 결과 토스트 + 실패 상세. 선택은 페이지가 소유하고
 * 선택이 있을 때만 렌더한다. 끝나면(성공·실패 무관) done으로 알려 페이지가 선택을 비우고 다시 읽는다.
 */
const props = defineProps<{ items: InboxItem[] }>()
const emit = defineEmits<{ done: []; clear: [] }>()

const claimsApi = useAdminClaims()
const toast = useAdminToast()

const confirmOpen = ref(false)
const busy = ref(false)
const result = ref<AdminClaimBulkApproveResponse | null>(null)
const resultTitles = ref<Record<string, string>>({})
const resultOpen = ref(false)
// 확인을 연 순간의 선택 — 다이얼로그가 열린 동안 변경 신호로 목록이 다시 읽혀도 문구의 건수와 보내는 대상이 같게 고정한다.
const pending = ref<InboxItem[]>([])

const confirmMessage = computed(() => bulkApproveConfirmMessage(pending.value))

function openConfirm(): void {
  pending.value = [...props.items]
  confirmOpen.value = true
}

async function run(): Promise<void> {
  if (busy.value) return
  if (pending.value.length === 0) {
    confirmOpen.value = false
    return
  }
  busy.value = true
  const titles: Record<string, string> = {}
  for (const item of pending.value) titles[item.sourceRef] = item.title
  try {
    const response = await claimsApi.bulkApprove(pending.value.map((item) => item.sourceRef))
    result.value = response
    resultTitles.value = titles
    confirmOpen.value = false
    const summary = summarizeClaimBulkApprove(response)
    toast.show(summary.semantic, summary.message, summary.hasFailure
      ? { action: { label: '상세 보기', onClick: () => { resultOpen.value = true } } }
      : undefined)
    emit('done')
  } catch (error) {
    confirmOpen.value = false
    toast.danger(toAdminErrorMessage(error))
    // 항목별 커밋이라 오류 전에 승인된 건이 있을 수 있다 — 선택을 비우고 다시 읽는다.
    emit('done')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div>
    <v-card v-if="items.length > 0" class="mb-3" color="primary" variant="tonal" data-testid="inbox-bulk-bar">
      <v-card-text class="d-flex align-center flex-wrap ga-3 py-3">
        <span class="font-weight-medium" data-testid="inbox-bulk-count">{{ items.length }}건 선택 (최대 {{ INBOX_BULK_APPROVE_MAX }}건)</span>
        <v-spacer />
        <v-btn variant="text" :disabled="busy" data-testid="inbox-bulk-clear" @click="emit('clear')">선택 해제</v-btn>
        <v-btn color="primary" variant="flat" :disabled="busy" data-testid="inbox-bulk-approve" @click="openConfirm">선택 승인</v-btn>
      </v-card-text>
    </v-card>
    <AdminConfirmDialog
      :open="confirmOpen"
      title="클레임 일괄 승인"
      :message="confirmMessage"
      confirm-label="승인"
      :loading="busy"
      risk
      test-id="inbox-bulk-confirm-dialog"
      @confirm="run"
      @cancel="confirmOpen = false"
    />
    <AdminClaimBulkResultDialog :open="resultOpen" :result="result" :titles="resultTitles" @close="resultOpen = false" />
  </div>
</template>
