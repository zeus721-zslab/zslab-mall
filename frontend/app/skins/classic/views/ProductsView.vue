<script setup lang="ts">
import type { ProductsPageVm } from '~/skins/contracts/products'

defineProps<{ vm: ProductsPageVm }>()
</script>

<template>
  <ProductListView title="상품 목록" :category-id="vm.categoryId" />
</template>
