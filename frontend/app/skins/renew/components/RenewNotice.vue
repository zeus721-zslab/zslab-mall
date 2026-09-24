<script setup lang="ts">
import type { Component } from 'vue'
import { CircleCheck, Info, TriangleAlert } from '@lucide/vue'

// renew 공용 알림(FE-72 보완 2). 톤 4가지 — success(저장·삭제 완료) · info(안내) · warning(되돌릴 수 없는 동작 안내) · danger(실패).
// 색은 기존 파스텔 토큰(mint·lavender·butter·pink)만 쓴다. 실패만 role=alert(즉시 읽힘), 나머지는 role=status(조용히 읽힘).
// action 슬롯(FE-79): 버튼이 있으면 ≥768 아이콘·문구·버튼을 세로 가운데로 맞추고 <768은 버튼을 아래 줄로 내린다.
// 버튼이 없으면 아이콘을 문구 첫 줄 높이에 맞춘다(문구가 여러 줄이어도 아이콘은 첫 줄 옆). 띠 면 위 버튼은 흰 바탕 보조 규칙.
export type RenewNoticeTone = 'success' | 'info' | 'warning' | 'danger'

const props = defineProps<{ tone: RenewNoticeTone }>()
const slots = defineSlots<{ default?: () => unknown; action?: () => unknown }>()

const TONES: Record<RenewNoticeTone, { icon: Component; role: 'status' | 'alert'; colors: string }> = {
  success: { icon: CircleCheck, role: 'status', colors: 'bg-(--pastel-mint-bg) text-(--pastel-mint-ink)' },
  info: { icon: Info, role: 'status', colors: 'bg-(--pastel-lavender-bg) text-(--pastel-lavender-ink)' },
  warning: { icon: TriangleAlert, role: 'status', colors: 'bg-(--pastel-butter-bg) text-(--pastel-butter-ink)' },
  danger: { icon: TriangleAlert, role: 'alert', colors: 'bg-(--pastel-pink-bg) text-(--pastel-pink-ink)' },
}

const tone = computed(() => TONES[props.tone])
const hasAction = computed(() => slots.action !== undefined)
</script>

<template>
  <div
    :role="tone.role"
    :data-tone="props.tone"
    :class="[
      'flex gap-3 rounded-card px-5 py-4 text-body font-semibold',
      hasAction ? 'flex-col md:flex-row md:items-center md:gap-4' : 'items-start',
      tone.colors,
    ]"
  >
    <div :class="['flex min-w-0 flex-1 gap-3', hasAction ? 'items-start md:items-center' : 'items-start']">
      <!-- 문구 첫 줄(24px) 가운데에 20px 아이콘 — mt-0.5. 버튼이 있는 ≥768은 행 전체가 가운데 정렬이라 여백 없음. -->
      <component :is="tone.icon" :class="['mt-0.5 h-5 w-5 shrink-0', hasAction ? 'md:mt-0' : '']" aria-hidden="true" />
      <div class="min-w-0 flex-1 break-keep">
        <slot />
      </div>
    </div>
    <!-- <768 아래 줄은 문구 시작선(아이콘 20 + 간격 12)에 맞춘다. -->
    <div v-if="hasAction" class="flex shrink-0 flex-wrap gap-2 max-md:pl-8" data-slot="notice-action">
      <slot name="action" />
    </div>
  </div>
</template>
