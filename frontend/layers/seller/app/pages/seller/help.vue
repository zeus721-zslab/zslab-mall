<script setup lang="ts">
import ManualShell from '~/components/common/manual/ManualShell.vue'
import { SELLER_MANUAL } from '#layers/seller/app/lib/seller-manual-content'
import captures from '~~/public/manual/seller/captures.json'

// 셀러 매뉴얼(C8). 진입 가드는 기존 seller 미들웨어(셀러 세션 확인 → 없으면 /seller/login) 그대로다.
// 캡처 이미지·좌표는 walkthrough/manual/seller.manual.ts가 public/manual/seller/에 만든다.
definePageMeta({ layout: 'seller', middleware: ['seller', 'seller-vuetify'] })
useSeoMeta({ title: '도움말 · zslab-mall 셀러' })
</script>

<template>
  <ManualShell :document="SELLER_MANUAL" :captures="captures" />
</template>
