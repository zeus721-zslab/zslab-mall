<script setup lang="ts">
import { Toaster } from 'vue-sonner'
import 'vue-sonner/style.css'
import '#layers/admin/app/assets/css/admin-toast.css'

// 관리자 토스트 렌더러(FE-25 보강). admin·admin-auth 레이아웃에만 배치되므로 vue-sonner JS·CSS는 관리자 레이아웃 청크에만 묶인다
// (사용자 entry 무변경). 우상단·richColors·닫기 버튼·스택. 모바일(≤600px)은 라이브러리 기본으로 상단 전폭.
</script>

<template>
  <Toaster position="top-right" rich-colors close-button :visible-toasts="5" :offset="16" data-testid="admin-toaster" />
</template>
