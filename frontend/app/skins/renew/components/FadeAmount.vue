<script setup lang="ts">
// renew 금액 표기(mono 숫자 + "원"). 값이 바뀌면 숫자를 0.2s 페이드로 교체한다(움직임 줄이기 설정이면 즉시).
defineProps<{ value: number }>()
</script>

<template>
  <span class="inline-flex items-baseline text-ink">
    <Transition
      mode="out-in"
      enter-active-class="transition-opacity duration-200 ease-out motion-reduce:transition-none"
      leave-active-class="transition-opacity duration-200 ease-out motion-reduce:transition-none"
      enter-from-class="opacity-0"
      leave-to-class="opacity-0"
    >
      <span :key="value" class="font-mono font-semibold">{{ value.toLocaleString('ko-KR') }}</span>
    </Transition>
    <span class="ml-0.5 text-[0.7em]">원</span>
  </span>
</template>
