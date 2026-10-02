<script setup lang="ts">
import { INBOX_SNOOZE_REASON_MAX, INBOX_SNOOZE_REASON_PRESETS, inboxItemTypeLabel } from '~/lib/constants/inbox'
import type { InboxItem } from '~/lib/inbox-view'
import { type InboxSnoozePreset, customSnoozeUntil, inboxSnoozePresets, kstLocalInputMin } from '~/lib/inbox-snooze'
import { extractErrorCode, isSellerSuspendedError, toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { useSellerInbox } from '#layers/seller/app/composables/useSellerInbox'
import { useSellerToast } from '#layers/seller/app/composables/useSellerToast'

/**
 * 셀러 인박스 보류 다이얼로그(D-248 · 관리자 AdminInboxSnoozeDialog 복제 · 셀러 레이어에 확인 다이얼로그가 없어 인박스에 필요한 만큼만 둔다).
 * 성공(204) → done · INBOX_ITEM_NOT_FOUND(이미 처리·다른 셀러)는 warning 토스트 후 stale ·
 * **403 SELLER_SUSPENDED는 danger 토스트로 직접 표시**(배너만 남지 않게 · FE-44 §8) 후 cancel.
 */
const props = defineProps<{ open: boolean; item: InboxItem | null }>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const CUSTOM_PRESET = 'CUSTOM'

const inboxApi = useSellerInbox()
const toast = useSellerToast()

const reason = ref('')
const presets = ref<InboxSnoozePreset[]>([])
const selectedPreset = ref<string>('')
const customValue = ref('')
const customMin = ref('')
const error = ref<string | null>(null)
const submitting = ref(false)

watch(() => props.open, (open) => {
  if (!open) return
  const now = Date.now()
  reason.value = ''
  presets.value = inboxSnoozePresets(now)
  selectedPreset.value = presets.value[0]?.key ?? CUSTOM_PRESET
  customValue.value = ''
  customMin.value = kstLocalInputMin(now)
  error.value = null
  submitting.value = false
})

function resolveUntil(): string | null {
  if (selectedPreset.value === CUSTOM_PRESET) return customSnoozeUntil(customValue.value, Date.now())
  return presets.value.find((preset) => preset.key === selectedPreset.value)?.untilAt ?? null
}

const canSubmit = computed(() => reason.value.trim() !== '' && (selectedPreset.value !== CUSTOM_PRESET || customValue.value !== ''))

async function submit(): Promise<void> {
  if (submitting.value || !props.item) return
  const untilAt = resolveUntil()
  if (untilAt === null) {
    error.value = '지금 이후의 시각을 선택하세요.'
    return
  }
  submitting.value = true
  try {
    await inboxApi.snooze({ type: props.item.type, ref: props.item.ref, untilAt, reason: reason.value.trim() })
    toast.success('보류했습니다. 정한 시각에 다시 표시됩니다.')
    emit('done')
  } catch (cause) {
    if (isSellerSuspendedError(cause)) {
      toast.danger(toSellerErrorMessage(cause))
      emit('cancel')
    } else if (extractErrorCode(cause) === 'INBOX_ITEM_NOT_FOUND') {
      toast.warning(toSellerErrorMessage(cause))
      emit('stale')
    } else {
      error.value = toSellerErrorMessage(cause)
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <v-dialog :model-value="open" max-width="520" :persistent="submitting" @update:model-value="(value: boolean) => !value && emit('cancel')">
    <v-card data-testid="inbox-snooze-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">보류</v-card-title>
      <v-card-text class="px-5">
        <p v-if="item" class="text-caption text-medium-emphasis mb-4">{{ inboxItemTypeLabel(item.type) }} · {{ item.title }}</p>
        <p class="text-body-2 font-weight-medium mb-2">사유</p>
        <div class="d-flex flex-wrap ga-2 mb-2">
          <v-chip
            v-for="(preset, index) in INBOX_SNOOZE_REASON_PRESETS"
            :key="preset"
            size="small"
            :variant="reason === preset ? 'flat' : 'outlined'"
            :color="reason === preset ? 'primary' : undefined"
            :data-testid="`inbox-snooze-reason-${index}`"
            @click="reason = preset"
          >{{ preset }}</v-chip>
        </div>
        <v-text-field
          v-model="reason"
          label="사유 직접 입력"
          density="compact"
          :maxlength="INBOX_SNOOZE_REASON_MAX"
          :counter="INBOX_SNOOZE_REASON_MAX"
          :disabled="submitting"
          data-testid="inbox-snooze-reason-input"
        />
        <p class="text-body-2 font-weight-medium mt-2 mb-2">다시 표시할 시각</p>
        <v-chip-group v-model="selectedPreset" mandatory selected-class="text-primary" column>
          <v-chip v-for="preset in presets" :key="preset.key" :value="preset.key" size="small" variant="outlined" :data-testid="`inbox-snooze-preset-${preset.key}`">
            {{ preset.label }}
          </v-chip>
          <v-chip :value="CUSTOM_PRESET" size="small" variant="outlined" data-testid="inbox-snooze-preset-CUSTOM">직접 선택</v-chip>
        </v-chip-group>
        <v-text-field
          v-if="selectedPreset === CUSTOM_PRESET"
          v-model="customValue"
          type="datetime-local"
          label="날짜·시각(한국 시간)"
          density="compact"
          :min="customMin"
          :disabled="submitting"
          class="mt-2"
          data-testid="inbox-snooze-custom"
        />
        <v-alert v-if="error" type="error" variant="tonal" density="compact" class="mt-2" data-testid="inbox-snooze-error">{{ error }}</v-alert>
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="inbox-snooze-cancel" @click="emit('cancel')">닫기</v-btn>
        <SellerSuspendedGuard v-slot="{ suspended }">
          <v-btn color="primary" variant="flat" :loading="submitting" :disabled="submitting || !canSubmit || suspended" data-testid="inbox-snooze-submit" @click="submit">보류</v-btn>
        </SellerSuspendedGuard>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
