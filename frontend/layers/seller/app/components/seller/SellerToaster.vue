<script setup lang="ts">
import { Toaster } from 'vue-sonner'
import 'vue-sonner/style.css'
import '#layers/seller/app/assets/css/seller-toast.css'

// 셀러 토스트 렌더러(Track 90-B-3·관리자 AdminToaster 복제). seller 레이아웃에만 배치되므로 vue-sonner JS·CSS는 셀러 레이아웃 청크에만 묶인다.
// 우상단·richColors·닫기 버튼·스택. 모바일(≤600px)은 라이브러리 기본으로 상단 전폭.
</script>

<template>
  <Toaster position="top-right" rich-colors close-button :visible-toasts="5" :offset="16" data-testid="seller-toaster" />
</template>
