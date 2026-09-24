<script setup lang="ts">
import type { SearchPageVm } from '~/skins/contracts/search'

defineProps<{ vm: SearchPageVm }>()
</script>

<template>
  <ProductListView v-if="vm.keyword" :title="vm.title" :keyword="vm.keyword" :show-category-tabs="false" />
  <div v-else class="py-14 md:py-20">
    <div class="mx-auto max-w-[1240px] px-4 md:px-6">
      <h1 class="mb-6 text-2xl font-medium tracking-tight text-gray-900">{{ vm.title }}</h1>
      <CommonEmptyState message="검색어를 입력하세요" />
    </div>
  </div>
</template>
