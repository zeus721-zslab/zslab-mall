import type { Profile } from '~/types/user'

/** pages/mypage/profile.vue → ProfileView. data는 SSR 로드한 프로필(email 읽기전용 표시), name·phone은 편집 폼 값. */
export interface ProfilePageVm {
  data: Profile | undefined
  pending: boolean
  error: Error | undefined
  refresh: () => Promise<void>
  name: string
  phone: string
  submitting: boolean
  errorMessage: string
  successMessage: string
  handleSubmit: () => Promise<void>
  NAME_MAX: number
  PHONE_MAX: number
}
