<script setup lang="ts">
import { mdiAlertOutline } from '@mdi/js'

// 확인 다이얼로그(FE-25 공용). 부모가 open·loading을 소유하고 confirm/cancel만 올린다. 처리 중에는 닫기·확인을 막는다.
// warningLines(STEP 498): message 아래 warning alert로 붙는 경고 문장(차단 아님·확인은 그대로 활성). warningEmphasis면 마지막 줄(핵심 경고)을 굵게.
defineProps<{
  open: boolean
  title: string
  message: string
  confirmLabel?: string
  confirmColor?: string
  loading?: boolean
  warningLines?: string[]
  warningEmphasis?: boolean
  /** 한 페이지에 다이얼로그가 여럿일 때 테스트 셀렉터 구분용(닫힌 다이얼로그 DOM도 남아 있음). */
  testId?: string
}>()
const emit = defineEmits<{ confirm: []; cancel: [] }>()
</script>

<template>
  <v-dialog :model-value="open" max-width="440" :persistent="loading" @update:model-value="(value: boolean) => !value && emit('cancel')">
    <v-card :data-testid="testId ?? 'admin-confirm-dialog'">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">{{ title }}</v-card-title>
      <v-card-text class="px-5 text-body-2" style="white-space: pre-line">{{ message }}</v-card-text>
      <v-card-text v-if="warningLines && warningLines.length > 0" class="px-5 pt-0">
        <v-alert type="warning" variant="tonal" density="compact" :icon="mdiAlertOutline" :data-testid="`${testId ?? 'admin-confirm-dialog'}-warning`">
          <p v-for="(line, index) in warningLines" :key="line" class="text-body-2 mb-0" :class="{ 'font-weight-bold': warningEmphasis && index === warningLines.length - 1 }">{{ line }}</p>
        </v-alert>
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="loading" :data-testid="`${testId ?? 'admin-confirm-dialog'}-cancel`" @click="emit('cancel')">취소</v-btn>
        <v-btn :color="confirmColor ?? 'primary'" variant="flat" :loading="loading" :data-testid="`${testId ?? 'admin-confirm-dialog'}-ok`" @click="emit('confirm')">
          {{ confirmLabel ?? '확인' }}
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
