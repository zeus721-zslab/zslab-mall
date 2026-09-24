<script setup lang="ts">
import type { CategoryIllustrationName } from '../category-theme'
import CategoryIllustration from './CategoryIllustration.vue'

// renew 로그인·회원가입 공통 틀(FE-74 · FE-81): ≥1024는 왼쪽 라벤더 띠 면 + 오른쪽 흰 폼 카드, <1024는 폼 카드만.
// 띠 면은 위→아래로 눈썹 · 제목 · 리드 · 카테고리 선 일러스트 칩 2×2 · 보조 문구(note가 있을 때만). 첫 화면 등장 모션은 두지 않는다(FE-76).
defineProps<{ title: string; eyebrow: string; headline: string; description: string; note?: string }>()

const CONTAINER = 'mx-auto max-w-[1440px] px-5 md:px-10 lg:px-16'
const PANEL_ILLUSTRATIONS: readonly CategoryIllustrationName[] = ['shirt', 'mug', 'bottle', 'bag']
</script>

<template>
  <div class="pb-12 pt-6 md:pb-16 md:pt-10">
    <div :class="[CONTAINER, 'lg:grid lg:grid-cols-2 lg:items-stretch lg:gap-8']">
      <section
        class="hidden rounded-(--panel-radius) bg-(--pastel-lavender-bg) p-12 lg:flex lg:flex-col"
        aria-hidden="true"
      >
        <!-- 색은 메인 히어로 띠 면과 같다(눈썹 lavender-ink · 제목 ink · 리드 sub). -->
        <p class="text-caption uppercase tracking-[0.12em] text-(--pastel-lavender-ink)">{{ eyebrow }}</p>
        <p class="mt-3 text-h1 text-ink">{{ headline }}</p>
        <p class="mt-3 text-body text-sub">{{ description }}</p>
        <div class="mt-10 grid w-max grid-cols-2 gap-4">
          <span
            v-for="illustration in PANEL_ILLUSTRATIONS"
            :key="illustration"
            class="flex h-16 w-16 items-center justify-center rounded-full bg-white text-(--pastel-lavender-ink) shadow-e1"
          >
            <CategoryIllustration :name="illustration" class="h-8 w-8" />
          </span>
        </div>
        <p v-if="note" class="mt-auto pt-10 text-small text-sub" data-testid="auth-panel-note">{{ note }}</p>
      </section>

      <section class="rounded-card bg-white p-6 shadow-e1 sm:p-10 lg:p-14">
        <div class="mx-auto w-full max-w-[440px]">
          <h1 class="text-h1 text-ink">{{ title }}</h1>
          <div class="mt-8">
            <slot />
          </div>
        </div>
      </section>
    </div>
  </div>
</template>
