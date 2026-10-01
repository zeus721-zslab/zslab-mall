<script setup lang="ts">
import type { AdminSellerDelay } from '#layers/admin/app/types/admin-seller-delay'
import { sellerNudgeConfirmMessage } from '#layers/admin/app/lib/admin-inbox-view'
import { toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminInbox } from '#layers/admin/app/composables/useAdminInbox'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'
import { SELLER_NUDGE_RESULT_LABELS } from '~/lib/constants/inbox'
import { formatDateTime } from '~/lib/utils/datetime'

/**
 * 인박스 셀러 지연 패널(D-252 · 상세 패널 안). 유형별 초과 건수(지금 다시 셈)·마지막 독촉 시각을 보이고, 독촉은 일괄 독촉 API에 1곳으로 보낸다.
 * 독촉은 원천을 바꾸지 않아 행이 남으므로 패널만 다시 읽는다. 지연이 그사이 사라졌으면(NO_DELAY) stale로 알려 페이지가 목록을 다시 읽는다.
 */
const props = defineProps<{ sellerPublicId: string }>()
const emit = defineEmits<{ processed: [outcome: 'done' | 'stale'] }>()

const inboxApi = useAdminInbox()
const toast = useAdminToast()

const delay = ref<AdminSellerDelay | null>(null)
const loading = ref(false)
const loadError = ref<string | null>(null)
let loadSequence = 0

async function load(sellerPublicId: string): Promise<void> {
  const sequence = ++loadSequence
  loading.value = true
  loadError.value = null
  try {
    const response = await inboxApi.sellerDelay(sellerPublicId)
    if (sequence !== loadSequence) return
    delay.value = response
  } catch (error) {
    if (sequence !== loadSequence) return
    delay.value = null
    loadError.value = toAdminErrorMessage(error)
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

const confirmOpen = ref(false)
const busy = ref(false)
const confirmMessage = computed(() => sellerNudgeConfirmMessage([delay.value?.companyName ?? '']))

async function nudge(): Promise<void> {
  if (busy.value || !delay.value) return
  busy.value = true
  const sellerPublicId = delay.value.sellerPublicId
  try {
    const response = await inboxApi.nudgeSellers([sellerPublicId])
    confirmOpen.value = false
    const result = response.results[0]?.result
    if (result === 'SENT') toast.info('독촉 SMS를 보냈습니다.')
    else if (result === 'FAILED') toast.danger('독촉 SMS 발송에 실패했습니다. 잠시 후 다시 시도해 주세요.')
    else if (result) toast.warning(`보내지 않았습니다 — ${SELLER_NUDGE_RESULT_LABELS[result]}`)
    if (result === 'NO_DELAY') emit('processed', 'stale')
    else await load(sellerPublicId)
  } catch (error) {
    confirmOpen.value = false
    toast.danger(toAdminErrorMessage(error))
  } finally {
    busy.value = false
  }
}

// 항목이 바뀌면 열린 확인을 닫고 새로 읽는다(다이얼로그 상태 선언 뒤에 둔다 — immediate 실행).
watch(() => props.sellerPublicId, (sellerPublicId) => {
  confirmOpen.value = false
  void load(sellerPublicId)
}, { immediate: true })
</script>

<template>
  <div data-testid="inbox-seller-delay-panel">
    <v-skeleton-loader v-if="loading && !delay" type="list-item-two-line" />
    <v-alert v-else-if="loadError" type="error" variant="tonal" density="compact" data-testid="inbox-seller-delay-error">{{ loadError }}</v-alert>
    <template v-else-if="delay">
      <v-row dense class="mb-2">
        <v-col cols="6">
          <div class="text-caption text-medium-emphasis">발송 대기 기한 초과</div>
          <div class="text-body-2" data-testid="inbox-seller-delay-delivery">{{ delay.deliveryReadyOverdueCount }}건</div>
        </v-col>
        <v-col cols="6">
          <div class="text-caption text-medium-emphasis">상품 Q&amp;A 미답변 기한 초과</div>
          <div class="text-body-2" data-testid="inbox-seller-delay-question">{{ delay.questionUnansweredOverdueCount }}건</div>
        </v-col>
        <v-col cols="12">
          <div class="text-caption text-medium-emphasis">마지막 독촉</div>
          <div class="text-body-2" data-testid="inbox-seller-delay-last-nudged">{{ delay.lastNudgedAt ? formatDateTime(delay.lastNudgedAt) : '없음' }}</div>
        </v-col>
      </v-row>
      <div class="d-flex flex-wrap ga-2 mb-2">
        <v-btn color="primary" variant="flat" :disabled="busy" data-testid="inbox-seller-delay-nudge" @click="confirmOpen = true">독촉 보내기</v-btn>
      </div>
    </template>

    <AdminConfirmDialog
      :open="confirmOpen"
      title="셀러 독촉"
      :message="confirmMessage"
      confirm-label="독촉 보내기"
      :loading="busy"
      risk
      test-id="inbox-seller-delay-confirm-dialog"
      @confirm="nudge"
      @cancel="confirmOpen = false"
    />
  </div>
</template>
