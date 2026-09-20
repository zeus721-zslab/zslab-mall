<script setup lang="ts">
import { useDisplay } from 'vuetify'
import { useSellerLeaveGuard } from '#layers/seller/app/lib/seller-leave-guard'
import { useSellerFirstPaintGate } from '#layers/seller/app/lib/seller-first-paint-gate'
import { useSellerAuthStore } from '#layers/seller/app/stores/sellerAuth'
import { useSellerMe } from '#layers/seller/app/composables/useSellerMe'

// 셀러 셸 레이아웃(Track 90-A·관리자 admin.vue 동형). 사용자 셸(default.vue)·관리자 셸(admin.vue)과 분리.
// 각 페이지가 definePageMeta({ layout:'seller', middleware:['seller','seller-vuetify'] })로 지정한다. Vuetify는 seller-vuetify 미들웨어가 렌더 전에 설치한다.
// 셀러 이탈(뒤로가기 등 클라이언트 이동으로 /seller 밖으로) 시 전체 새로고침해 Vuetify 전역 스타일 누수·관리자 테마 혼합을 막는다.
useSellerLeaveGuard()

// 첫 페인트 게이트: 밴드·사이드바·상단바·콘텐츠를 같은 프레임에 렌더한다.
const ready = useSellerFirstPaintGate()

// 정지(SUSPENDED) 셀러 안내(D-190): GET /seller/me 상태(진입 시·Track 90-B-3) 또는 403 SELLER_SUSPENDED 수신 뒤부터 모든 셀러 화면 상단에 표시.
// 세션은 유지되며 조회는 계속 가능. 쓰기 호출부는 배너와 별개로 403 문구를 토스트로 보여야 한다(FE-44 §8).
const sellerAuth = useSellerAuthStore()
const sellerMe = useSellerMe()
onMounted(() => { void sellerMe.load() })

// 사이드바 열림: 데스크톱은 열린 채 시작, 모바일은 닫힌 채 시작·상단바 토글로 연다.
const { mdAndUp } = useDisplay()
const sidebarOpen = ref<boolean>(mdAndUp.value)
</script>

<template>
  <v-app>
    <template v-if="ready">
      <!-- 상단 컬러 밴드(300px): 투명 상단바·페이지 헤더 뒤, 콘텐츠 카드는 밴드 위로 겹친다 -->
      <div class="slr-band slr-band-soft" aria-hidden="true" />
      <SellerSidebar v-model="sidebarOpen" />
      <SellerTopbar @toggle-sidebar="sidebarOpen = !sidebarOpen" />
      <v-main class="slr-main">
        <div class="slr-content">
          <v-alert v-if="sellerAuth.suspended" type="warning" class="mb-4" role="alert" data-testid="seller-suspended-notice">
            정지 상태의 셀러입니다. 조회는 가능하지만 주문·상품·정산 등 변경 작업은 처리되지 않습니다. 문의는 관리자에게 하세요.
          </v-alert>
          <slot />
        </div>
      </v-main>
      <SellerToaster />
    </template>
  </v-app>
</template>
