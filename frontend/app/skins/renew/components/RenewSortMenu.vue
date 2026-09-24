<script setup lang="ts">
import type { AcceptableValue } from 'reka-ui'
import type { ProductSort } from '~/types/product'

// renew 정렬 목록(FE-69 보완 2). 네이티브 select의 펼침 목록은 OS가 그려 스타일을 입힐 수 없어 dropdown-menu(RadioGroup)로 그린다.
// 키보드(방향키·Enter·Esc)·포커스 복귀·aria는 dropdown-menu(reka-ui) 기본 동작 그대로다.
const props = defineProps<{
  modelValue: ProductSort
  options: { value: ProductSort; label: string }[]
}>()
const emit = defineEmits<{ 'update:modelValue': [value: ProductSort] }>()

const selectedLabel = computed(() => props.options.find((option) => option.value === props.modelValue)?.label ?? '')

function onSelect(value: AcceptableValue): void {
  const selected = props.options.find((option) => option.value === value)
  if (selected && selected.value !== props.modelValue) {
    emit('update:modelValue', selected.value)
  }
}
</script>

<template>
  <DropdownMenu>
    <DropdownMenuTrigger as-child>
      <button
        type="button"
        class="group inline-flex h-11 items-center gap-2 rounded-full border border-line bg-white pl-5 pr-4 text-sm font-bold text-ink transition duration-200 focus-visible:border-primary focus-visible:outline-hidden data-[state=open]:border-primary"
      >
        <span class="sr-only">정렬 기준: </span>{{ selectedLabel }}
        <svg
          class="pointer-events-none h-4 w-4 text-sub transition-transform duration-200 group-data-[state=open]:rotate-180"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.8"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <path d="M6 9l6 6 6-6" />
        </svg>
      </button>
    </DropdownMenuTrigger>
    <DropdownMenuContent
      align="end"
      :side-offset="8"
      class="min-w-(--reka-dropdown-menu-trigger-width) rounded-2xl border-line bg-white p-1.5 text-ink shadow-[0_16px_36px_-16px_rgba(34,31,43,0.28)] data-[state=closed]:animate-[renew-menu-out_150ms_ease-in] data-[state=open]:animate-[renew-menu-in_180ms_ease-out] motion-reduce:animate-none"
    >
      <DropdownMenuRadioGroup :model-value="modelValue" @update:model-value="onSelect">
        <DropdownMenuRadioItem
          v-for="option in options"
          :key="option.value"
          :value="option.value"
          class="min-h-11 cursor-pointer rounded-xl pl-9 pr-4 text-sm font-bold text-ink focus:bg-(--pastel-lavender-bg) focus:text-ink data-[highlighted]:bg-(--pastel-lavender-bg) data-[state=checked]:text-primary"
        >
          <template #indicator-icon>
            <svg
              class="h-4 w-4 text-primary"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
            >
              <path d="M5 12.5l4.5 4.5L19 7.5" />
            </svg>
          </template>
          {{ option.label }}
        </DropdownMenuRadioItem>
      </DropdownMenuRadioGroup>
    </DropdownMenuContent>
  </DropdownMenu>
</template>
