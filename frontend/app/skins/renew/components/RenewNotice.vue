<script setup lang="ts">
import type { Component } from 'vue'
import { CircleCheck, Info, TriangleAlert } from '@lucide/vue'

// renew 공용 알림(FE-72 보완 2). 톤 4가지 — success(저장·삭제 완료) · info(안내) · warning(되돌릴 수 없는 동작 안내) · danger(실패).
// 색은 기존 파스텔 토큰(mint·lavender·butter·pink)만 쓴다. 실패만 role=alert(즉시 읽힘), 나머지는 role=status(조용히 읽힘).
export type RenewNoticeTone = 'success' | 'info' | 'warning' | 'danger'

const props = defineProps<{ tone: RenewNoticeTone }>()

const TONES: Record<RenewNoticeTone, { icon: Component; role: 'status' | 'alert'; colors: string }> = {
  success: { icon: CircleCheck, role: 'status', colors: 'bg-(--pastel-mint-bg) text-(--pastel-mint-ink)' },
  info: { icon: Info, role: 'status', colors: 'bg-(--pastel-lavender-bg) text-(--pastel-lavender-ink)' },
  warning: { icon: TriangleAlert, role: 'status', colors: 'bg-(--pastel-butter-bg) text-(--pastel-butter-ink)' },
  danger: { icon: TriangleAlert, role: 'alert', colors: 'bg-(--pastel-pink-bg) text-(--pastel-pink-ink)' },
}

const tone = computed(() => TONES[props.tone])
</script>

<template>
  <div :role="tone.role" :data-tone="props.tone" :class="['flex items-start gap-3 rounded-[20px] px-5 py-4 text-sm font-bold', tone.colors]">
    <component :is="tone.icon" class="mt-px h-5 w-5 shrink-0" aria-hidden="true" />
    <div class="min-w-0 flex-1 break-keep">
      <slot />
    </div>
  </div>
</template>
