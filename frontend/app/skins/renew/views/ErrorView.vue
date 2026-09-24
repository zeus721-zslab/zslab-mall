<script setup lang="ts">
import { CloudAlert, SearchX } from '@lucide/vue'
import type { ErrorPageVm } from '~/skins/contracts/error'

// renew 에러 화면(FE-74). app/error.vue가 구매자 경로는 기본 레이아웃(헤더·푸터) 안에, 관리자·셀러 경로는 레이아웃 없이 렌더한다. classic도 이 뷰를 쓴다(classic 레지스트리가 가리킴).
// 404 = 페이지 없음 · 그 외 = 일시 오류. 버튼은 홈으로(에러 상태 해제) · 이전 페이지.
defineProps<{ vm: ErrorPageVm }>()

const CONTAINER = 'mx-auto max-w-[1440px] px-5 md:px-10 lg:px-16'
const BUTTON =
  'inline-flex min-h-12 items-center justify-center rounded-full px-8 text-sm font-bold transition duration-200 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2'
</script>

<template>
  <div class="pb-12 pt-6 md:pb-16 md:pt-10">
    <div :class="CONTAINER">
      <section
        class="flex flex-col items-center rounded-(--panel-radius) bg-(--pastel-lavender-bg) px-6 py-16 text-center text-(--pastel-lavender-ink) md:py-24 motion-safe:transition motion-safe:duration-500 motion-safe:ease-out motion-safe:starting:translate-y-3 motion-safe:starting:opacity-0"
        data-testid="error-view"
      >
        <span class="flex h-20 w-20 items-center justify-center rounded-full bg-white/60" aria-hidden="true">
          <SearchX v-if="vm.notFound" class="h-10 w-10" :stroke-width="1.6" />
          <CloudAlert v-else class="h-10 w-10" :stroke-width="1.6" />
        </span>
        <p class="mt-6 font-mono text-xs font-medium uppercase tracking-widest opacity-80">{{ vm.notFound ? '404' : 'Error' }}</p>
        <h1 class="mt-2 text-3xl font-bold tracking-tight md:text-4xl" data-testid="error-title">
          {{ vm.notFound ? '페이지를 찾을 수 없어요' : '일시적인 오류가 발생했어요' }}
        </h1>
        <p class="mt-4 text-sm">
          {{ vm.notFound ? '주소가 바뀌었거나 사라진 페이지예요. 주소를 다시 확인해 주세요.' : '잠시 후 다시 시도해 주세요.' }}
        </p>
        <div class="mt-8 flex flex-col gap-3 sm:flex-row">
          <button
            type="button"
            :class="[BUTTON, 'bg-primary text-primary-foreground hover:bg-primary-hover']"
            data-testid="error-home"
            @click="vm.handleGoHome"
          >
            홈으로
          </button>
          <button type="button" :class="[BUTTON, 'bg-white text-ink hover:bg-surface-muted']" data-testid="error-back" @click="vm.handleGoBack">
            이전 페이지
          </button>
        </div>
      </section>
    </div>
  </div>
</template>
