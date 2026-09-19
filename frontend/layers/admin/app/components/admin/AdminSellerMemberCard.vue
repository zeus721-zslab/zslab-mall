<script setup lang="ts">
import { mdiPlus } from '@mdi/js'
import type { AdminSellerDetail, AdminSellerMember } from '#layers/admin/app/types/admin-seller'
import { SELLER_MEMBERS_EMPTY_NOTE } from '#layers/admin/app/lib/constants/admin-seller'
import { loginableMemberCount, memberDisplayName } from '#layers/admin/app/lib/admin-seller-view'
import { lastActiveOwnerBlockedReason, memberRoleChip } from '#layers/admin/app/lib/admin-seller-member-view'
import { formatDateTime } from '~/lib/utils/datetime'

/**
 * 셀러 상세 구성원 카드(FE-42·D-189). 목록(이름·이메일·역할 배지·상태·등록일)과 추가·제거·역할 변경 액션을 갖고 세 다이얼로그를 소유한다.
 * 성공(201/204) 후 changed를 올려 부모가 상세를 다시 읽는다(로그인 가능 구성원 경고·가드 판정 갱신).
 *
 * <p>마지막 활성 대표 미리보기 = `lastActiveOwnerBlockedReason`(BE `assertNotLastActiveOwner`와 같은 판정: 대상이 활성 OWNER이고 그 외 활성 OWNER 0명).
 * 응답 members가 판정 근거이고 조회~요청 사이 변화는 서버 409가 막는다(89-D terminable 선례). 탈퇴 구성원은 회색 처리하고 역할 변경은 막는다
 * (탈퇴 회원의 역할은 의미가 없음·제거만 가능 — 셀러 2 정리 경로). soft-delete 구성원(userPublicId 없음)은 경로로 지정할 수 없어 액션 없음.
 */
const props = defineProps<{ detail: AdminSellerDetail }>()
const emit = defineEmits<{ changed: [] }>()

const route = useRoute()
const members = computed<AdminSellerMember[]>(() => props.detail.members)
const loginable = computed(() => loginableMemberCount(members.value))

const addOpen = ref(false)
const removeTarget = ref<AdminSellerMember | null>(null)
const roleTarget = ref<AdminSellerMember | null>(null)

function removeBlockedReason(member: AdminSellerMember): string | null {
  if (!member.userPublicId) return '삭제된 회원은 화면에서 제거할 수 없습니다.'
  return lastActiveOwnerBlockedReason(members.value, member)
}
function roleBlockedReason(member: AdminSellerMember): string | null {
  if (!member.userPublicId) return '삭제된 회원의 역할은 변경할 수 없습니다.'
  if (member.withdrawnAt) return '탈퇴한 회원의 역할은 변경할 수 없습니다(제거만 가능).'
  return lastActiveOwnerBlockedReason(members.value, member)
}

function onDone(): void {
  addOpen.value = false
  removeTarget.value = null
  roleTarget.value = null
  emit('changed')
}
</script>

<template>
  <v-card class="mb-4" data-testid="seller-members">
    <v-card-title class="d-flex align-center justify-space-between flex-wrap ga-2 pt-4 px-5">
      <span class="text-subtitle-2 font-weight-bold">구성원 ({{ members.length }})<span v-if="members.length > 0" class="text-caption text-medium-emphasis font-weight-regular"> · 활성 {{ loginable }}명</span></span>
      <v-btn
        size="small"
        :variant="loginable === 0 ? 'flat' : 'outlined'"
        color="primary"
        :prepend-icon="mdiPlus"
        data-testid="seller-member-add"
        @click="addOpen = true"
      >구성원 추가</v-btn>
    </v-card-title>
    <v-card-text class="px-5 pb-5">
      <v-table v-if="members.length > 0" density="compact" class="adm-table">
        <thead><tr><th>이름</th><th>이메일</th><th>역할</th><th>상태</th><th>등록일</th><th class="text-right">관리</th></tr></thead>
        <tbody>
          <tr v-for="(member, index) in members" :key="member.userPublicId ?? index" :class="{ 'adm-member-row--inactive': !member.userPublicId || member.withdrawnAt }" data-testid="seller-member-row">
            <td>
              <NuxtLink v-if="member.userPublicId" :to="{ path: `/admin/members/${member.userPublicId}`, query: { back: route.fullPath } }" class="text-primary text-decoration-none" data-testid="seller-member-name">{{ memberDisplayName(member) }}</NuxtLink>
              <span v-else class="text-medium-emphasis" data-testid="seller-member-name">{{ memberDisplayName(member) }}</span>
            </td>
            <td>{{ member.email ?? '—' }}</td>
            <td><v-chip size="small" variant="flat" :class="memberRoleChip(member.roleCode).chipClass" data-testid="seller-member-role">{{ memberRoleChip(member.roleCode).text }}</v-chip></td>
            <td>
              <span v-if="member.withdrawnAt" class="text-medium-emphasis" data-testid="seller-member-withdrawn">탈퇴 ({{ formatDateTime(member.withdrawnAt) }})</span>
              <span v-else-if="!member.userPublicId" class="text-medium-emphasis">삭제됨</span>
              <span v-else data-testid="seller-member-active">활성</span>
            </td>
            <td class="text-medium-emphasis" data-testid="seller-member-joined">{{ formatDateTime(member.joinedAt) }}</td>
            <td class="text-right text-no-wrap">
              <!-- 비활성 버튼은 이벤트를 받지 않으므로 툴팁은 감싸는 span에 건다(전이·계좌 버튼 패턴). -->
              <v-tooltip :disabled="roleBlockedReason(member) === null" location="top">
                <template #activator="{ props: tooltipProps }">
                  <span v-bind="tooltipProps" data-testid="seller-member-role-wrapper">
                    <v-btn size="x-small" variant="text" color="primary" :disabled="roleBlockedReason(member) !== null" data-testid="seller-member-change-role" @click="roleTarget = member">역할 변경</v-btn>
                  </span>
                </template>
                <span data-testid="seller-member-role-blocked">{{ roleBlockedReason(member) }}</span>
              </v-tooltip>
              <v-tooltip :disabled="removeBlockedReason(member) === null" location="top">
                <template #activator="{ props: tooltipProps }">
                  <span v-bind="tooltipProps" data-testid="seller-member-remove-wrapper">
                    <v-btn size="x-small" variant="text" color="error" :disabled="removeBlockedReason(member) !== null" data-testid="seller-member-remove" @click="removeTarget = member">제거</v-btn>
                  </span>
                </template>
                <span data-testid="seller-member-remove-blocked">{{ removeBlockedReason(member) }}</span>
              </v-tooltip>
            </td>
          </tr>
        </tbody>
      </v-table>
      <p v-else class="text-body-2 text-medium-emphasis mb-0" data-testid="seller-members-empty">소속 구성원이 없습니다.</p>
      <p v-if="members.length === 0" class="text-caption text-medium-emphasis mt-3 mb-0" data-testid="seller-members-empty-note">{{ SELLER_MEMBERS_EMPTY_NOTE }}</p>
    </v-card-text>

    <AdminSellerMemberAddDialog :open="addOpen" :detail="detail" @done="onDone" @stale="onDone" @cancel="addOpen = false" />
    <AdminSellerMemberRemoveDialog :open="removeTarget !== null" :detail="detail" :target="removeTarget" @done="onDone" @stale="onDone" @cancel="removeTarget = null" />
    <AdminSellerMemberRoleDialog :open="roleTarget !== null" :detail="detail" :target="roleTarget" @done="onDone" @stale="onDone" @cancel="roleTarget = null" />
  </v-card>
</template>

<style scoped>
.adm-member-row--inactive td {
  color: rgba(var(--v-theme-on-surface), var(--v-medium-emphasis-opacity));
  background: rgba(var(--v-theme-on-surface), 0.03);
}
</style>
