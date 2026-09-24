<script setup lang="ts">
// renew 로그인·회원가입 공통 틀(FE-74): ≥1024는 왼쪽 연보라 안내 면 + 오른쪽 폼 카드, <1024는 폼 카드만.
// 안내 면·카드는 진입 시 짧게 떠오른다(카드가 조금 늦게). 움직임 줄이기면 없음.
defineProps<{ title: string; eyebrow: string; headline: string; description: string }>()

const CONTAINER = 'mx-auto max-w-[1440px] px-5 md:px-10 lg:px-16'
const ENTER = 'motion-safe:transition motion-safe:duration-500 motion-safe:ease-out motion-safe:starting:translate-y-3 motion-safe:starting:opacity-0'
</script>

<template>
  <div class="pb-12 pt-6 md:pb-16 md:pt-10">
    <div :class="[CONTAINER, 'lg:grid lg:grid-cols-2 lg:items-stretch lg:gap-8']">
      <section
        :class="[
          ENTER,
          'relative hidden overflow-hidden rounded-(--panel-radius) bg-(--pastel-lavender-bg) p-12 text-(--pastel-lavender-ink) lg:flex lg:flex-col lg:justify-end',
        ]"
        aria-hidden="true"
      >
        <!-- 장식 원: 파스텔 두 겹 -->
        <span class="absolute -right-16 -top-16 h-64 w-64 rounded-full bg-white/40"></span>
        <span class="absolute right-24 top-28 h-24 w-24 rounded-full bg-(--pastel-periwinkle-bg)"></span>
        <p class="relative font-mono text-xs font-medium uppercase tracking-widest opacity-80">{{ eyebrow }}</p>
        <p class="relative mt-3 text-4xl font-bold leading-tight tracking-tight">{{ headline }}</p>
        <p class="relative mt-4 max-w-sm text-sm leading-relaxed">{{ description }}</p>
      </section>

      <section :class="[ENTER, 'rounded-[28px] bg-white p-6 motion-safe:delay-100 sm:p-10 lg:p-14']">
        <div class="mx-auto w-full max-w-[440px]">
          <h1 class="text-3xl font-bold tracking-tight text-ink">{{ title }}</h1>
          <div class="mt-8">
            <slot />
          </div>
        </div>
      </section>
    </div>
  </div>
</template>
