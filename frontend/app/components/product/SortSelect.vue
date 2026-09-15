<script setup lang="ts">
import { PRODUCT_SORT_OPTIONS } from '~/lib/constants/product'
import type { ProductSort } from '~/types/product'

// 정렬 select 레이아웃 조각(FE-20·products/index.vue 인라인에서 분리). 상태는 소유하지 않고 v-model로만 주고받는다.
const model = defineModel<ProductSort>({ required: true })
</script>

<template>
  <select
    v-model="model"
    aria-label="정렬 기준"
    class="rounded-full border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 transition duration-200 hover:bg-gray-50 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-gray-900"
  >
    <option v-for="option in PRODUCT_SORT_OPTIONS" :key="option.value" :value="option.value">
      {{ option.label }}
    </option>
  </select>
</template>
