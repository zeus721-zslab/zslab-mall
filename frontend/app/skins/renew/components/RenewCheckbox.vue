<script setup lang="ts">
// renew 체크박스: 실제 checkbox input을 appearance-none으로 꾸민다(키보드·라벨 연결·폼 동작은 네이티브 그대로).
defineProps<{
  checked: boolean
  disabled?: boolean
  ariaLabel?: string
}>()
const emit = defineEmits<{ change: [checked: boolean] }>()

function onChange(event: Event): void {
  emit('change', (event.target as HTMLInputElement).checked)
}
</script>

<template>
  <span class="relative inline-flex h-6 w-6 shrink-0">
    <input
      type="checkbox"
      class="peer h-6 w-6 cursor-pointer appearance-none rounded-lg border-2 border-line bg-white transition duration-200 checked:border-primary checked:bg-primary focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 disabled:cursor-default disabled:opacity-40"
      :checked="checked"
      :disabled="disabled"
      :aria-label="ariaLabel"
      @change="onChange"
    />
    <svg
      class="pointer-events-none absolute inset-0 m-auto h-4 w-4 text-white opacity-0 peer-checked:opacity-100"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2.6"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
    >
      <path d="M5 12.5l4.5 4.5L19 7.5" />
    </svg>
  </span>
</template>
