<script setup lang="ts">
// 공통 페이지 헤더(FE-22e·FE-22f): 상단 컬러 밴드 위에 놓이므로 흰색 텍스트(admin-vuetify.css .adm-page-header). 제목은 굵기로 위계, 설명은 반투명 흰색. 우측 슬롯은 페이지 액션 자리.
// guide(Track 102 FE-64): 상세 화면은 description 자리에 대상 이름(주문번호·회원명)이 들어가 "이 화면에서 무엇을 하는지"를 적을 곳이 없었다.
// 목록 화면은 description이 그 역할을 하므로 guide를 쓰지 않는다.
defineProps<{ title: string; description?: string; guide?: string }>()
</script>

<template>
  <header class="adm-page-header d-flex align-start justify-space-between flex-wrap ga-4 mb-6">
    <div>
      <h1 class="text-h6 font-weight-bold">{{ title }}</h1>
      <p v-if="description" class="adm-page-header__description text-body-2 mt-1 mb-0">{{ description }}</p>
      <p v-if="guide" class="adm-page-header__description text-body-2 mt-1 mb-0" data-testid="page-header-guide">{{ guide }}</p>
    </div>
    <div v-if="$slots.actions" class="d-flex align-center ga-2">
      <slot name="actions" />
    </div>
  </header>
</template>
