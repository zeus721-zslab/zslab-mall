<script setup lang="ts">
import { mdiHammerWrench } from '@mdi/js'

definePageMeta({ layout: 'seller', middleware: ['seller', 'seller-vuetify'] })
useSeoMeta({ title: '대시보드 · zslab-mall 셀러' })

// 셀러 홈(Track 90-A). 이번 트랙은 레이어 골격·독립 인증까지이며 대시보드 지표는 셀러 조회 API(recon §7)와 함께 90-B 이후 붙는다.
</script>

<template>
  <div data-testid="seller-dashboard">
    <SellerPageHeader title="대시보드" description="셀러 센터에 로그인했습니다. 주문·상품·정산 화면은 순차 제공됩니다." />
    <v-card data-testid="seller-placeholder">
      <v-card-text class="d-flex flex-column align-center text-center py-12">
        <v-avatar color="surface-variant" size="48" class="mb-4">
          <v-icon :icon="mdiHammerWrench" size="22" class="text-medium-emphasis" />
        </v-avatar>
        <p class="text-subtitle-1 font-weight-medium mb-1">준비 중입니다</p>
        <p class="text-body-2 text-medium-emphasis mb-0">대시보드 지표는 셀러 조회 API 연동 후 제공됩니다.</p>
      </v-card-text>
    </v-card>
  </div>
</template>
