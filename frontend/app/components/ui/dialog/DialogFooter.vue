<script setup lang="ts">
import type { HTMLAttributes } from "vue"
import { cn } from '~/lib/utils'

const props = defineProps<{
  class?: HTMLAttributes["class"]
}>()
</script>

<template>
  <!-- <768 시트는 버튼을 같은 폭으로 나눠 채운다. -->
  <div
    data-slot="dialog-footer"
    :class="cn('flex items-center justify-end gap-2 border-t border-border px-6 py-4 max-md:[&>*]:flex-1 md:px-8 md:py-5', props.class)"
  >
    <slot />
  </div>
</template>
