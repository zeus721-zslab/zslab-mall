<script setup lang="ts">
import { LifeBuoy } from '@lucide/vue'

// renew 도움말(FE-74 · FE-81 흰 카드). FE-64의 준비 중 자리 그대로다 — 본문은 매뉴얼 단계에서 채운다. "준비 중입니다." 문구·testid는 e2e(help.spec) 단언 대상.
const CONTAINER = 'mx-auto max-w-[1440px] px-5 md:px-10 lg:px-16'
</script>

<template>
  <div class="pb-12 pt-6 md:pb-16 md:pt-10">
    <div :class="CONTAINER">
      <section class="mx-auto flex max-w-[560px] flex-col items-center rounded-card bg-white px-6 py-14 text-center shadow-e1 md:py-20">
        <span class="flex h-20 w-20 items-center justify-center rounded-full bg-(--pastel-lavender-bg) text-(--pastel-lavender-ink)" aria-hidden="true">
          <LifeBuoy class="h-10 w-10" :stroke-width="1.6" />
        </span>
        <h1 class="mt-6 text-h1 text-ink">도움말</h1>
        <p class="mt-3 text-body text-sub" data-testid="help-placeholder">준비 중입니다.</p>
        <NuxtLink to="/" class="btn btn-secondary btn-md mt-8">홈으로</NuxtLink>
      </section>
    </div>
  </div>
</template>
