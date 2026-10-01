<script setup lang="ts">
import type { AnswerDraftResponse } from '~/types/answer-draft'
import { answerEvidenceKindLabel } from '~/lib/constants/answer-draft'

/**
 * 답안 초안 표시(D-253 · FE-107 · 관리자 문의·셀러 Q&A 답변 다이얼로그 공용). 근거 목록 + "초안 사용"(누르면 부모가 입력란을 채운다 — 열 때 자동으로
 * 덮어쓰지 않는다) · 근거가 없으면 직접 작성 안내 · 조회 실패면 안내 1줄만.
 */
defineProps<{ result: AnswerDraftResponse | null; loading: boolean; failed: boolean; disabled?: boolean }>()
const emit = defineEmits<{ use: [draft: string] }>()
</script>

<template>
  <div class="mb-3" data-testid="answer-draft-box">
    <p v-if="loading" class="text-caption text-medium-emphasis" data-testid="answer-draft-loading">답안 초안을 준비하는 중…</p>
    <p v-else-if="failed" class="text-caption text-medium-emphasis" data-testid="answer-draft-failed">
      답안 초안을 불러오지 못했습니다. 직접 작성해 주세요.
    </p>
    <template v-else-if="result">
      <p v-if="result.draft === null" class="text-caption text-medium-emphasis" data-testid="answer-draft-empty">근거 부족 — 직접 작성해 주세요.</p>
      <v-sheet v-else border rounded class="pa-3">
        <div class="d-flex align-center mb-2">
          <span class="text-caption font-weight-bold">답안 초안 근거</span>
          <v-spacer />
          <v-btn
            size="small"
            variant="tonal"
            color="primary"
            :disabled="disabled"
            data-testid="answer-draft-use"
            @click="emit('use', result.draft)"
          >
            초안 사용
          </v-btn>
        </div>
        <ul class="text-caption pl-4">
          <li v-for="(evidence, index) in result.evidence" :key="index" class="mb-1" data-testid="answer-draft-evidence">
            <span class="font-weight-medium">[{{ answerEvidenceKindLabel(evidence.kind) }}] {{ evidence.title }}</span>
            <span class="d-block text-medium-emphasis">{{ evidence.summary }}</span>
          </li>
        </ul>
      </v-sheet>
    </template>
  </div>
</template>
