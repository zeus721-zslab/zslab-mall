<script setup lang="ts">
// renew 더보기(Track 106-1). 누적 로딩 목록의 끝에 둔다(번호 페이지는 RenewPagination). 불러오는 동안 잠그고 aria-busy로 알린다.
withDefaults(defineProps<{ loading: boolean; label?: string }>(), { label: '더보기' })
const emit = defineEmits<{ more: [] }>()
</script>

<template>
  <button
    type="button"
    class="btn btn-secondary btn-md w-full md:w-72"
    :disabled="loading"
    :aria-busy="loading"
    data-testid="more-button"
    @click="emit('more')"
  >
    <span v-if="loading" class="h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent motion-reduce:animate-none" aria-hidden="true"></span>
    {{ loading ? '불러오는 중…' : label }}
  </button>
</template>
