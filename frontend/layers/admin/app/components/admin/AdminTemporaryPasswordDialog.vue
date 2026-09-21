<script setup lang="ts">
import { mdiAlertOutline, mdiContentCopy } from '@mdi/js'
import { TEMPORARY_PASSWORD_DIALOG_NOTICE } from '#layers/admin/app/lib/constants/admin-member'

/**
 * 임시 비밀번호 1회 표시 다이얼로그(FE-55·D-204·회원 재발급·셀러 구성원 신규 계정 공용). 부모가 open·temporaryPassword를 소유하고 닫힘(closed)만
 * 올린다 — 부모는 closed에서 값을 null로 지운다. 평문은 이 컴포넌트 화면과 복사 버튼 외 어디로도 나가지 않는다(토스트·console·스토어 금지).
 * 닫기는 2단(닫기 → "다시 볼 수 없음" 확인)이며 바깥 클릭·ESC로는 닫히지 않는다(persistent). 닫힐 때 로컬 상태(복사 안내·확인 단계)를 비운다.
 */
const props = defineProps<{
  open: boolean
  temporaryPassword: string | null
  /** 누구의 비밀번호인지(이름·이메일 등). */
  recipientLabel?: string
  testId?: string
}>()
const emit = defineEmits<{ closed: [] }>()

const testId = computed(() => props.testId ?? 'temporary-password-dialog')
const copyNotice = ref<{ tone: 'success' | 'error'; text: string } | null>(null)
const confirmingClose = ref(false)

async function copyPassword(): Promise<void> {
  if (!props.temporaryPassword) return
  try {
    await navigator.clipboard.writeText(props.temporaryPassword)
    copyNotice.value = { tone: 'success', text: '임시 비밀번호를 복사했습니다.' }
  } catch (copyError) {
    // 권한 거부·비보안 컨텍스트: 화면에 표시된 값을 직접 옮기도록 안내한다(평문은 로그에 남기지 않는다).
    console.warn('임시 비밀번호 복사 실패', copyError instanceof Error ? copyError.name : 'unknown')
    copyNotice.value = { tone: 'error', text: '복사하지 못했습니다. 화면의 비밀번호를 직접 옮겨 적어 주세요.' }
  }
}

function requestClose(): void {
  confirmingClose.value = true
}

function cancelClose(): void {
  confirmingClose.value = false
}

function confirmClose(): void {
  copyNotice.value = null
  confirmingClose.value = false
  emit('closed')
}

watch(() => props.open, (open) => {
  if (!open) {
    copyNotice.value = null
    confirmingClose.value = false
  }
})
</script>

<template>
  <v-dialog :model-value="open" max-width="480" persistent no-click-animation>
    <v-card :data-testid="testId">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">임시 비밀번호</v-card-title>
      <v-card-text class="px-5">
        <p v-if="recipientLabel" class="text-body-2 text-medium-emphasis mb-3" :data-testid="`${testId}-recipient`">{{ recipientLabel }}</p>
        <div class="adm-temp-password d-flex align-center justify-space-between ga-2 rounded pa-3 mb-2">
          <code class="adm-temp-password__value" :data-testid="`${testId}-value`">{{ temporaryPassword ?? '' }}</code>
          <v-btn size="small" variant="tonal" :prepend-icon="mdiContentCopy" :disabled="!temporaryPassword" :data-testid="`${testId}-copy`" @click="copyPassword">복사</v-btn>
        </div>
        <p v-if="copyNotice" role="status" class="text-caption mb-3" :class="copyNotice.tone === 'success' ? 'text-success' : 'text-error'" :data-testid="`${testId}-copy-notice`">{{ copyNotice.text }}</p>
        <v-alert type="warning" variant="tonal" density="compact" :icon="mdiAlertOutline" :data-testid="`${testId}-notice`">
          <p v-for="line in TEMPORARY_PASSWORD_DIALOG_NOTICE" :key="line" class="text-body-2 mb-0">{{ line }}</p>
        </v-alert>
        <v-alert v-if="confirmingClose" type="error" variant="tonal" density="compact" class="mt-3" :data-testid="`${testId}-close-confirm`">
          닫으면 이 임시 비밀번호는 다시 볼 수 없습니다. 회원에게 전달했는지 확인한 뒤 닫아 주세요.
        </v-alert>
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <template v-if="confirmingClose">
          <v-btn variant="text" :data-testid="`${testId}-close-cancel`" @click="cancelClose">계속 보기</v-btn>
          <v-btn color="error" variant="flat" :data-testid="`${testId}-close-ok`" @click="confirmClose">닫기</v-btn>
        </template>
        <v-btn v-else color="primary" variant="flat" :data-testid="`${testId}-close`" @click="requestClose">닫기</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.adm-temp-password {
  background: rgba(var(--v-theme-on-surface), 0.06);
}
.adm-temp-password__value {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 1.25rem;
  letter-spacing: 0.08em;
  word-break: break-all;
  user-select: all;
}
</style>
