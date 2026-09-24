<script setup lang="ts">
import type { CategoryPageVm } from '~/skins/contracts/category'
import RenewProductListing from '../components/RenewProductListing.vue'

// renew 카테고리 목록. 잘못된 id(categoryId null)는 페이지가 조회하지 않아 vm.list가 없고 빈 상태를 보인다(classic과 같은 규칙).
defineProps<{ vm: CategoryPageVm }>()
</script>

<template>
  <RenewProductListing v-if="vm.list" :list="vm.list" :category-id="vm.categoryId" />
  <div v-else class="mx-auto max-w-[1440px] px-5 pb-8 pt-6 md:px-10 md:pt-10 lg:px-16">
    <h1 class="mb-6 text-h1 text-ink">카테고리</h1>
    <CommonEmptyState />
  </div>
</template>
