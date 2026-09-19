<script setup lang="ts">
import { useSellerLeaveGuard } from '#layers/seller/app/lib/seller-leave-guard'
import { useSellerFirstPaintGate } from '#layers/seller/app/lib/seller-first-paint-gate'

// 셀러 로그인 전용 레이아웃(관리자 admin-auth.vue 동형). 사이드바·상단바 없이 v-app + 상단 컬러 밴드만 제공한다(Vuetify 컴포넌트는 v-app 하위여야 함).
// 로그인 페이지에서 사용자·관리자 영역으로 클라이언트 이동하는 경우도 이탈 가드로 전체 새로고침한다.
useSellerLeaveGuard()

// 첫 페인트 게이트: 밴드와 로그인 카드를 같은 프레임에 렌더한다.
const ready = useSellerFirstPaintGate()
</script>

<template>
  <v-app>
    <template v-if="ready">
      <div class="slr-band slr-band-soft" aria-hidden="true" />
      <v-main class="slr-main">
        <v-container class="fill-height" fluid>
          <slot />
        </v-container>
      </v-main>
    </template>
  </v-app>
</template>
