/** app/error.vue → ErrorView(FE-74). 404와 그 외(일시 오류) 두 갈래만 구분한다. 에러 화면은 API를 호출하지 않는다. */
export interface ErrorPageVm {
  notFound: boolean
  /** 에러 상태를 지우고 홈(/)으로 이동. */
  handleGoHome: () => Promise<void>
  /** 이전 페이지로. 이전 기록이 없으면 홈으로. */
  handleGoBack: () => Promise<void>
}
