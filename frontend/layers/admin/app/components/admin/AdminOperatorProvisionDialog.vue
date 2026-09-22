<script setup lang="ts">
import { mdiMagnify } from '@mdi/js'
import type { AdminMemberSummary } from '#layers/admin/app/types/admin-member'
import { ADMIN_OPERATOR_MEMBER_SEARCH_SIZE, ADMIN_OPERATOR_KEYWORD_MAX } from '#layers/admin/app/lib/constants/admin-operator'
import { DEFAULT_ADMIN_MEMBER_QUERY } from '#layers/admin/app/lib/admin-member-query'
import { toOperatorErrorMessage } from '#layers/admin/app/lib/admin-operator-view'
import { toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminMembers } from '#layers/admin/app/composables/useAdminMembers'
import { useAdminOperators } from '#layers/admin/app/composables/useAdminOperators'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 신규 운영자 등록 다이얼로그(FE-39·BE D-186). 프로비저닝 API는 기존 회원에 ADMIN_OPERATOR 역할을 부여할 뿐이라(신규 계정·비밀번호 발급 없음)
 * 회원 목록 API(keyword·활성 BUYER)로 검색해 한 명을 고른 뒤 POST한다. 이미 운영자인 회원은 서버 409(ADMIN_OPERATOR_ALREADY_EXISTS)로 안내한다.
 * 부여에는 사유가 없다(회수만 사유 필수·D-186). 호출·토스트는 다이얼로그가 소유하고 부모는 done 시 다시 읽는다.
 */
const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ done: []; cancel: [] }>()

const membersApi = useAdminMembers()
const operatorsApi = useAdminOperators()
const toast = useAdminToast()

const keyword = ref('')
const results = ref<AdminMemberSummary[]>([])
const searched = ref(false)
const searching = ref(false)
const searchError = ref<string | null>(null)
const selected = ref<AdminMemberSummary | null>(null)
const submitting = ref(false)
let searchSequence = 0

function reset(): void {
  keyword.value = ''
  results.value = []
  searched.value = false
  searching.value = false
  searchError.value = null
  selected.value = null
  submitting.value = false
}
watch(() => props.open, (open) => { if (open) reset() })

async function search(): Promise<void> {
  const trimmed = keyword.value.trim()
  if (trimmed === '' || searching.value) return
  const sequence = ++searchSequence
  searching.value = true
  searchError.value = null
  selected.value = null
  try {
    const response = await membersApi.list(
      { ...DEFAULT_ADMIN_MEMBER_QUERY, keyword: trimmed, size: ADMIN_OPERATOR_MEMBER_SEARCH_SIZE },
      'ACTIVE',
    )
    if (sequence !== searchSequence) return
    results.value = response.items
    searched.value = true
  } catch (error) {
    if (sequence !== searchSequence) return
    searchError.value = toAdminErrorMessage(error)
  } finally {
    if (sequence === searchSequence) searching.value = false
  }
}

// FE-58: :disabled(submitting)와 같은 값을 핸들러가 재검사한다 — Vuetify VListItem은 disabled여도 click을 emit한다(프로그래밍 클릭 fallthrough).
function selectMember(member: AdminMemberSummary): void {
  if (submitting.value) return
  selected.value = member
}

const confirmDisabled = computed(() => submitting.value || selected.value === null)

async function submit(): Promise<void> {
  if (submitting.value || !selected.value) return
  submitting.value = true
  try {
    await operatorsApi.provision({ userPublicId: selected.value.publicId })
    toast.success(`${selected.value.name ?? selected.value.email ?? selected.value.publicId} 회원에게 운영 관리자 역할을 부여했습니다.`)
    emit('done')
  } catch (error) {
    // 403(SUPER_ADMIN 아님)·409(이미 운영자)·404(회원 없음)는 구체 문구로 알리고 다이얼로그는 유지한다(다른 회원 선택 가능).
    toast.danger(toOperatorErrorMessage(error))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <v-dialog :model-value="open" max-width="560" :persistent="submitting" @update:model-value="(value: boolean) => !value && emit('cancel')">
    <v-card data-testid="admin-operator-provision-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">신규 운영자 등록</v-card-title>
      <v-card-text class="px-5">
        <p class="text-body-2 mb-3">
          기존 회원을 검색해 <span class="font-weight-medium">운영 관리자</span> 역할을 부여합니다. 새 계정이나 비밀번호를 만들지 않으며, 회원은 자기 비밀번호로 관리자 로그인할 수 있게 됩니다.
        </p>
        <v-text-field
          v-model="keyword"
          label="이름 · 이메일 · 연락처"
          placeholder="회원 검색어"
          :prepend-inner-icon="mdiMagnify"
          :maxlength="ADMIN_OPERATOR_KEYWORD_MAX"
          :loading="searching"
          :disabled="submitting"
          hide-details
          data-testid="provision-keyword"
          @keyup.enter="search"
        >
          <template #append>
            <v-btn size="small" variant="tonal" :disabled="keyword.trim() === '' || searching || submitting" data-testid="provision-search" @click="search">검색</v-btn>
          </template>
        </v-text-field>

        <v-alert v-if="searchError" type="error" variant="tonal" density="compact" class="mt-3" data-testid="provision-search-error">{{ searchError }}</v-alert>
        <v-list v-else-if="results.length > 0" density="compact" class="mt-3 adm-provision-results" data-testid="provision-results">
          <v-list-item
            v-for="member in results"
            :key="member.publicId"
            :active="selected?.publicId === member.publicId"
            :disabled="submitting"
            data-testid="provision-result"
            @click="selectMember(member)"
          >
            <v-list-item-title>{{ member.name ?? '—' }} <span class="text-medium-emphasis">{{ member.email ?? '' }}</span></v-list-item-title>
            <v-list-item-subtitle>{{ member.phone ?? '연락처 없음' }}</v-list-item-subtitle>
          </v-list-item>
        </v-list>
        <p v-else-if="searched" class="text-body-2 text-medium-emphasis mt-3" data-testid="provision-empty">조건에 맞는 활성 회원이 없습니다.</p>

        <p v-if="selected" class="text-body-2 mt-3" data-testid="provision-confirm-text">
          <span class="font-weight-medium">{{ selected.name ?? selected.email ?? selected.publicId }}</span> 회원에게 운영 관리자 역할을 부여합니다.
        </p>
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="provision-dialog-close" @click="emit('cancel')">닫기</v-btn>
        <v-btn color="primary" variant="flat" :loading="submitting" :disabled="confirmDisabled" data-testid="provision-dialog-ok" @click="submit">
          역할 부여
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.adm-provision-results {
  max-height: 280px;
  overflow-y: auto;
  border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  border-radius: 8px;
}
</style>
