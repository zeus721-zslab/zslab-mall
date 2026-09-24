<script setup lang="ts">
import type { ProductsPageVm } from '~/skins/contracts/products'
import RenewProductListing from '../components/RenewProductListing.vue'

// renew 전체 상품 목록. 목록 데이터는 페이지가 useProductPage로 조회해 vm.list로 넘긴다(FE-69·productList 선언).
defineProps<{ vm: ProductsPageVm }>()
</script>

<template>
  <RenewProductListing v-if="vm.list" :list="vm.list" :category-id="vm.categoryId" />
</template>
