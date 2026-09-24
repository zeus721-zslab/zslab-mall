<script setup lang="ts">
import type { CategoryPageVm } from '~/skins/contracts/category'

// FE-20: 카테고리 페이지 껍데기(D-161 /categories/[id] 신설·뷰는 ProductListView 공용). 미들웨어 없음(공개).
// id가 양의 정수가 아니면 API를 호출하지 않고 빈 상태만 보인다. 존재하지 않는 id는 API 결과(빈 목록)가 그대로 빈 상태다.
const route = useRoute()

const categoryId = computed<number | null>(() => {
  const raw = route.params.id
  return typeof raw === 'string' && /^[1-9]\d*$/.test(raw) ? Number(raw) : null
})

useSeoMeta({
  title: '카테고리 · zslab-mall',
  description: 'zslab-mall 카테고리별 상품 목록.',
})

// 번호 페이지 목록은 스킨이 productList를 선언했을 때만 조회한다(FE-69). 잘못된 id(null)는 classic처럼 조회하지 않고 빈 상태다.
const list = useSkinNeeds('productList') && categoryId.value !== null ? reactive(useProductPage(categoryId)) : undefined
const vm: CategoryPageVm = reactive({ categoryId, list })
</script>

<template>
  <component :is="useSkinView('CategoryView')" :vm="vm" />
</template>
