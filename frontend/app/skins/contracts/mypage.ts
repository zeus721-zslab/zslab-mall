export interface MypageMenu {
  to: string
  label: string
  description: string
}

/** pages/mypage/index.vue → MypageView. */
export interface MypagePageVm {
  menus: MypageMenu[]
}
