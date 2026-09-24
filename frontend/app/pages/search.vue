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

// 번호 페이지 검색 결과는 스킨이 productList를 선언했을 때만 조회한다(FE-74). classic은 SearchView → ProductListView가 무한스크롤로 조회한다.
const list = useSkinNeeds('productList') ? reactive(useProductPage(ref<number | null>(null), keyword)) : undefined
const vm: SearchPageVm = reactive({ keyword, title, list })
</script>

<template>
  <component :is="useSkinView('SearchView')" :vm="vm" />
</template>
