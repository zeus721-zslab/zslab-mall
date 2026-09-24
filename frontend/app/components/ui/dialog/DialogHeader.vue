<script setup lang="ts">
import type { HTMLAttributes } from "vue"
import { cn } from '~/lib/utils'

const props = defineProps<{
  class?: HTMLAttributes["class"]
}>()
</script>

<template>
  <!-- 오른쪽 여백은 닫기 버튼 자리. -->
  <div
    data-slot="dialog-header"
    :class="cn('flex flex-col gap-1.5 px-6 pr-16 pt-6 md:px-8 md:pr-16 md:pt-8', props.class)"
  >
    <slot />
  </div>
</template>
