<script setup lang="ts">
import { LifeBuoy } from '@lucide/vue'

// renew 도움말(FE-74). FE-64의 준비 중 자리 그대로다 — 본문은 매뉴얼 단계에서 채운다. "준비 중입니다." 문구·testid는 e2e(help.spec) 단언 대상.
const CONTAINER = 'mx-auto max-w-[1440px] px-5 md:px-10 lg:px-16'
</script>

<template>
  <div class="pb-12 pt-6 md:pb-16 md:pt-10">
    <div :class="CONTAINER">
      <section
        class="flex flex-col items-center rounded-(--panel-radius) bg-(--pastel-lavender-bg) px-6 py-16 text-center text-(--pastel-lavender-ink) md:py-24 motion-safe:transition motion-safe:duration-500 motion-safe:ease-out motion-safe:starting:translate-y-3 motion-safe:starting:opacity-0"
      >
        <span class="flex h-20 w-20 items-center justify-center rounded-full bg-white/60" aria-hidden="true">
          <LifeBuoy class="h-10 w-10" :stroke-width="1.6" />
        </span>
        <p class="mt-6 font-mono text-xs font-medium uppercase tracking-widest opacity-80">Help</p>
        <h1 class="mt-2 text-3xl font-bold tracking-tight md:text-4xl">도움말</h1>
        <p class="mt-4 text-sm" data-testid="help-placeholder">준비 중입니다.</p>
      </section>
    </div>
  </div>
</template>
