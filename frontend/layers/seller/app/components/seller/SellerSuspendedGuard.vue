<script setup lang="ts">
import { useSellerAuthStore } from '#layers/seller/app/stores/sellerAuth'
import { SELLER_SUSPENDED_WRITE_TOOLTIP } from '#layers/seller/app/lib/constants/auth'

/**
 * 정지(SUSPENDED) 셀러 쓰기 버튼 가드(warn W10). 정지 배너와 같은 sellerAuth.suspended를 읽어 슬롯에 suspended를 넘기고, 정지면 툴팁을 붙인다.
 * 비활성 버튼은 마우스 이벤트를 받지 않으므로 툴팁 활성자는 감싼 span이다. 서버(403 SELLER_SUSPENDED)가 최종 차단점이고 이것은 사전 안내다.
 */
const sellerAuth = useSellerAuthStore()
</script>

<template>
  <v-tooltip v-if="sellerAuth.suspended" :text="SELLER_SUSPENDED_WRITE_TOOLTIP" location="top">
    <template #activator="{ props: activatorProps }">
      <span v-bind="activatorProps" class="d-inline-block" data-testid="seller-suspended-guard">
        <slot :suspended="true" />
      </span>
    </template>
  </v-tooltip>
  <slot v-else :suspended="false" />
</template>
