/** pages/mypage/withdraw.vue → WithdrawView. agreed는 뷰의 동의 체크박스가 v-model로 쓴다. */
export interface WithdrawPageVm {
  notice: string
  agreed: boolean
  submitting: boolean
  errorMessage: string
  handleWithdraw: () => Promise<void>
}
