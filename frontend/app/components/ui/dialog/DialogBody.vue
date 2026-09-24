<script setup lang="ts">
import type { HTMLAttributes } from "vue"
import { cn } from '~/lib/utils'

const props = defineProps<{
  class?: HTMLAttributes["class"]
}>()
</script>

<template>
  <!-- 본문만 안쪽 스크롤(머리·바닥 버튼은 고정). 시트 최대 높이 90dvh 안에서 넘치면 여기서 스크롤한다. -->
  <div
    data-slot="dialog-body"
    :class="cn('min-h-0 flex-1 overflow-y-auto px-6 py-5 md:px-8', props.class)"
  >
    <slot />
  </div>
</template>
