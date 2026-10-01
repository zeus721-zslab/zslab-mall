<script setup lang="ts">
import { mdiOpenInNew, mdiSleep } from '@mdi/js'
import type { AdminSellerDetail } from '#layers/admin/app/types/admin-seller'
import type { AdminInquiryListItem } from '#layers/admin/app/types/admin-inquiry'
import { ADMIN_SELLER_TRANSITION_LABEL, type AdminSellerStatus } from '#layers/admin/app/lib/constants/admin-seller'
import { availableTransitions, sellerStatusLabel, terminateBlockedReason } from '#layers/admin/app/lib/admin-seller-view'
import { adminInboxDeadlineChipClass, inboxInquiryAnswerItem } from '#layers/admin/app/lib/admin-inbox-view'
import { toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminSellers } from '#layers/admin/app/composables/useAdminSellers'
import { inboxItemTypeLabel } from '~/lib/constants/inbox'
import { type InboxItem, inboxDeadline, inboxPanelAction } from '~/lib/inbox-view'
import { inboxTargetRoute } from '~/lib/inbox-target'
import { formatDateTime } from '~/lib/utils/datetime'

/**
 * 관리자 인박스 상세 패널(D-248 · PC 오른쪽 칸 · 모바일 드로어 안). 공통 정보 · 원래 화면 이동 · 보류 · 패널 처리(1:1 문의 답변 · 셀러 입점 심사
 * 상태 전이). 처리 다이얼로그는 기존 컴포넌트를 그대로 쓰고, 끝나면 processed로 알려 페이지가 재조회·다음 항목 선택을 한다.
 */
type SellerTarget = Exclude<AdminSellerStatus, 'PENDING'>

const props = defineProps<{ item: InboxItem | null; nowMs: number }>()
const emit = defineEmits<{ processed: [outcome: 'done' | 'stale'] }>()

const sellersApi = useAdminSellers()

const action = computed(() => (props.item ? inboxPanelAction('ADMIN', props.item.type) : null))
const deadline = computed(() => inboxDeadline(props.item?.dueAt ?? null, props.nowMs))
const target = computed(() => (props.item ? inboxTargetRoute('ADMIN', props.item) : null))

// ---------- 보류 ----------
const snoozeOpen = ref(false)
function onSnoozed(outcome: 'done' | 'stale'): void {
  snoozeOpen.value = false
  emit('processed', outcome)
}

// ---------- 1:1 문의 답변 ----------
const inquiryItem = computed<AdminInquiryListItem | null>(() => (props.item ? inboxInquiryAnswerItem(props.item) : null))
const answerOpen = ref(false)
function onAnswered(outcome: 'done' | 'stale'): void {
  answerOpen.value = false
  emit('processed', outcome)
}

// ---------- 셀러 입점 심사 ----------
const seller = ref<AdminSellerDetail | null>(null)
const statusTarget = ref<SellerTarget | null>(null)
const sellerLoading = ref(false)
const sellerError = ref<string | null>(null)
let sellerSequence = 0

async function loadSeller(sellerPublicId: string): Promise<void> {
  const sequence = ++sellerSequence
  sellerLoading.value = true
  sellerError.value = null
  seller.value = null
  try {
    const detail = await sellersApi.get(sellerPublicId)
    if (sequence !== sellerSequence) return
    seller.value = detail
  } catch (error) {
    if (sequence !== sellerSequence) return
    sellerError.value = toAdminErrorMessage(error)
  } finally {
    if (sequence === sellerSequence) sellerLoading.value = false
  }
}

watch(() => props.item?.key, () => {
  snoozeOpen.value = false
  answerOpen.value = false
  statusTarget.value = null
  if (props.item && action.value === 'SELLER_STATUS') void loadSeller(props.item.sourceRef)
  else seller.value = null
}, { immediate: true })

const transitions = computed<SellerTarget[]>(() => (seller.value ? availableTransitions(seller.value.status) : []))
const terminateBlocked = computed(() => (seller.value ? terminateBlockedReason(seller.value) : null))

function transitionColor(next: SellerTarget): string {
  return next === 'TERMINATED' ? 'error' : next === 'SUSPENDED' ? 'warning' : 'primary'
}

function onStatusDone(): void {
  statusTarget.value = null
  emit('processed', 'done')
}
function onStatusStale(): void {
  statusTarget.value = null
  emit('processed', 'stale')
}
</script>

<template>
  <v-card class="h-100" data-testid="inbox-detail">
    <template v-if="item">
      <v-card-title class="d-flex align-center flex-wrap ga-2 pt-5 px-5">
        <span class="adm-chip adm-chip--neutral" data-testid="inbox-detail-type">{{ inboxItemTypeLabel(item.type) }}</span>
        <span :class="adminInboxDeadlineChipClass(deadline.tone)" data-testid="inbox-detail-deadline">{{ deadline.text }}</span>
      </v-card-title>
      <v-card-text class="px-5">
        <p class="text-body-1 font-weight-medium mb-1" style="white-space: pre-line" data-testid="inbox-detail-title">{{ item.title }}</p>
        <p v-if="item.subtitle" class="text-body-2 text-medium-emphasis mb-4" data-testid="inbox-detail-subtitle">{{ item.subtitle }}</p>
        <v-row dense class="mb-2">
          <v-col cols="6">
            <div class="text-caption text-medium-emphasis">기한</div>
            <div class="text-body-2" data-testid="inbox-detail-due">{{ item.dueAt ? formatDateTime(item.dueAt) : '없음' }}</div>
          </v-col>
          <v-col cols="6">
            <div class="text-caption text-medium-emphasis">기준 시각</div>
            <div class="text-body-2">{{ item.baseAt ? formatDateTime(item.baseAt) : '-' }}</div>
          </v-col>
        </v-row>

        <!-- 셀러 입점 심사: 단건 조회로 현재 상태·가능한 전이를 계산한다(셀러 상세 화면과 같은 판정). -->
        <template v-if="action === 'SELLER_STATUS'">
          <v-skeleton-loader v-if="sellerLoading" type="list-item-two-line" />
          <v-alert v-else-if="sellerError" type="error" variant="tonal" density="compact" data-testid="inbox-seller-error">{{ sellerError }}</v-alert>
          <div v-else-if="seller" class="mb-2" data-testid="inbox-seller">
            <div class="text-caption text-medium-emphasis">현재 상태</div>
            <div class="text-body-2 mb-3">{{ sellerStatusLabel(seller.status) }} · 대표 {{ seller.ceoName }}</div>
          </div>
        </template>
      </v-card-text>
      <v-card-actions class="px-5 pb-5 flex-wrap ga-2">
        <template v-if="action === 'INQUIRY_ANSWER' && inquiryItem">
          <v-btn color="primary" variant="flat" data-testid="inbox-detail-action" @click="answerOpen = true">답변하기</v-btn>
        </template>
        <template v-if="action === 'SELLER_STATUS' && seller">
          <v-btn
            v-for="next in transitions"
            :key="next"
            :color="transitionColor(next)"
            :variant="next === 'ACTIVE' ? 'flat' : 'outlined'"
            :disabled="next === 'TERMINATED' && terminateBlocked !== null"
            :data-testid="`inbox-detail-status-${next}`"
            @click="statusTarget = next"
          >{{ ADMIN_SELLER_TRANSITION_LABEL[next] }}</v-btn>
        </template>
        <v-btn v-if="target" variant="outlined" :prepend-icon="mdiOpenInNew" :to="{ path: target.path, query: target.query }" data-testid="inbox-detail-open-origin">
          원래 화면에서 열기
        </v-btn>
        <v-btn variant="text" :prepend-icon="mdiSleep" data-testid="inbox-detail-snooze" @click="snoozeOpen = true">보류</v-btn>
      </v-card-actions>
    </template>
    <v-card-text v-else class="text-body-2 text-medium-emphasis text-center py-10" data-testid="inbox-detail-empty">
      왼쪽 목록에서 항목을 고르세요.
    </v-card-text>

    <AdminInboxSnoozeDialog :open="snoozeOpen" :item="item" @done="onSnoozed('done')" @stale="onSnoozed('stale')" @cancel="snoozeOpen = false" />
    <AdminInquiryAnswerDialog :open="answerOpen" :item="inquiryItem" @done="onAnswered('done')" @stale="onAnswered('stale')" @cancel="answerOpen = false" />
    <AdminSellerStatusDialog
      :open="statusTarget !== null"
      :detail="seller"
      :target="statusTarget"
      @done="onStatusDone"
      @stale="onStatusStale"
      @cancel="statusTarget = null"
    />
  </v-card>
</template>
