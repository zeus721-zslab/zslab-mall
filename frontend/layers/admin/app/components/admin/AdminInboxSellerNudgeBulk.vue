<script setup lang="ts">
import type { AdminSellerNudgeResponse } from '#layers/admin/app/types/admin-seller-delay'
import type { InboxItem } from '~/lib/inbox-view'
import { INBOX_BULK_NUDGE_MAX, sellerNudgeConfirmMessage, summarizeSellerNudge } from '#layers/admin/app/lib/admin-inbox-view'
import { toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminInbox } from '#layers/admin/app/composables/useAdminInbox'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 인박스 셀러 지연 일괄 독촉(D-252): 선택 바 · 확인 다이얼로그(대상 셀러 수 · 24시간 쿨다운) · 실행 · 결과 토스트 + 셀러별 결과 다이얼로그.
 * 클레임 일괄 승인(AdminInboxClaimBulkApprove)과 같은 흐름이며 선택은 페이지가 소유한다. 끝나면 done으로 알려 페이지가 선택을 비우고 다시 읽는다.
 */
const props = defineProps<{ items: InboxItem[] }>()
const emit = defineEmits<{ done: []; clear: [] }>()

const inboxApi = useAdminInbox()
const toast = useAdminToast()

const confirmOpen = ref(false)
const busy = ref(false)
const result = ref<AdminSellerNudgeResponse | null>(null)
const resultTitles = ref<Record<string, string>>({})
const resultOpen = ref(false)
// 확인을 연 순간의 선택 — 다이얼로그가 열린 동안 목록이 다시 읽혀도 문구와 보내는 대상이 같게 고정한다.
const pending = ref<InboxItem[]>([])

const confirmMessage = computed(() => sellerNudgeConfirmMessage(pending.value.map((item) => item.title)))

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
    const response = await inboxApi.nudgeSellers(pending.value.map((item) => item.sourceRef))
    result.value = response
    resultTitles.value = titles
    confirmOpen.value = false
    const summary = summarizeSellerNudge(response)
    toast.show(summary.semantic, summary.message, summary.hasIssue
      ? { action: { label: '상세 보기', onClick: () => { resultOpen.value = true } } }
      : undefined)
    emit('done')
  } catch (error) {
    confirmOpen.value = false
    toast.danger(toAdminErrorMessage(error))
    // 셀러별로 따로 보내 오류 전에 발송된 셀러가 있을 수 있다 — 선택을 비우고 다시 읽는다.
    emit('done')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div>
    <v-card v-if="items.length > 0" class="mb-3" color="primary" variant="tonal" data-testid="inbox-nudge-bar">
      <v-card-text class="d-flex align-center flex-wrap ga-3 py-3">
        <span class="font-weight-medium" data-testid="inbox-nudge-count">셀러 {{ items.length }}곳 선택 (최대 {{ INBOX_BULK_NUDGE_MAX }}곳)</span>
        <v-spacer />
        <v-btn variant="text" :disabled="busy" data-testid="inbox-nudge-clear" @click="emit('clear')">선택 해제</v-btn>
        <v-btn color="primary" variant="flat" :disabled="busy" data-testid="inbox-nudge-send" @click="openConfirm">선택 독촉</v-btn>
      </v-card-text>
    </v-card>
    <AdminConfirmDialog
      :open="confirmOpen"
      title="셀러 일괄 독촉"
      :message="confirmMessage"
      confirm-label="독촉 보내기"
      :loading="busy"
      risk
      test-id="inbox-nudge-confirm-dialog"
      @confirm="run"
      @cancel="confirmOpen = false"
    />
    <AdminSellerNudgeResultDialog :open="resultOpen" :result="result" :titles="resultTitles" @close="resultOpen = false" />
  </div>
</template>
