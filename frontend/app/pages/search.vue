<script setup lang="ts">
import type { SearchPageVm } from '~/skins/contracts/search'

// FE-20: 검색 결과 페이지 껍데기(/search?keyword=). 미들웨어 없음(공개). 카테고리 탭은 숨긴다(검색은 전 카테고리 대상).
// keyword가 trim 후 비면 API를 호출하지 않고 안내 문구만 보인다. ?keyword= 변경은 페이지 재마운트 없이 computed로 전파된다.
const route = useRoute()

const keyword = computed<string>(() => {
  const raw = route.query.keyword
  return typeof raw === 'string' ? raw.trim() : ''
})

const title = computed(() => (keyword.value ? `'${keyword.value}' 검색 결과` : '검색'))

useSeoMeta({
  title: () => `${title.value} · zslab-mall`,
  description: 'zslab-mall 상품 검색 결과.',
})

const vm: SearchPageVm = reactive({ keyword, title })
</script>

<template>
  <component :is="useSkinView('SearchView')" :vm="vm" />
</template>
