<script setup lang="ts">
import type { ErrorPageVm } from '~/skins/contracts/error'

// renew 에러 화면(FE-74 · FE-81 흰 카드). app/error.vue가 구매자 경로는 기본 레이아웃(헤더·푸터) 안에, 관리자·셀러 경로는 레이아웃 없이 렌더한다. classic도 이 뷰를 쓴다(classic 레지스트리가 가리킴).
// 404 = 큰 숫자 + 페이지 없음 · 그 외 = 숫자 없이 일시 오류. 버튼은 홈으로(주 · 에러 상태 해제) · 이전 페이지(보조).
defineProps<{ vm: ErrorPageVm }>()

const CONTAINER = 'mx-auto max-w-[1440px] px-5 md:px-10 lg:px-16'
</script>

<template>
  <div class="pb-12 pt-6 md:pb-16 md:pt-10">
    <div :class="CONTAINER">
      <section
        class="mx-auto flex max-w-[560px] flex-col items-center rounded-card bg-white px-6 py-14 text-center shadow-e1 md:py-20"
        data-testid="error-view"
      >
        <p v-if="vm.notFound" class="text-display text-primary tabular-nums" aria-hidden="true">404</p>
        <h1 :class="['text-h1 text-ink', vm.notFound ? 'mt-3' : '']" data-testid="error-title">
          {{ vm.notFound ? '페이지를 찾을 수 없어요' : '일시적인 오류가 발생했어요' }}
        </h1>
        <p class="mt-3 text-body text-sub">
          {{ vm.notFound ? '주소가 바뀌었거나 사라진 페이지예요. 주소를 다시 확인해 주세요.' : '잠시 후 다시 시도해 주세요.' }}
        </p>
        <div class="mt-8 flex w-full flex-col gap-3 sm:w-auto sm:flex-row">
          <button type="button" class="btn btn-primary btn-md" data-testid="error-home" @click="vm.handleGoHome">홈으로</button>
          <button type="button" class="btn btn-secondary btn-md" data-testid="error-back" @click="vm.handleGoBack">이전 페이지</button>
        </div>
      </section>
    </div>
  </div>
</template>
