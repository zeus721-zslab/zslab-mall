<script setup lang="ts">
import type { CategoryPageVm } from '~/skins/contracts/category'

defineProps<{ vm: CategoryPageVm }>()
</script>

<template>
  <ProductListView v-if="vm.categoryId !== null" title="카테고리" :category-id="vm.categoryId" />
  <div v-else class="py-14 md:py-20">
    <div class="mx-auto max-w-[1240px] px-4 md:px-6">
      <h1 class="mb-6 text-2xl font-medium tracking-tight text-gray-900">카테고리</h1>
      <CommonEmptyState />
    </div>
  </div>
</template>
