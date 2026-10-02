import type { ManualSection } from '~/types/manual'

// 시작하기. 근거: admin/login.vue:75-83 · AdminSidebar.vue:64,86 · AdminTopbar.vue:56-66 · admin-dashboard-view.ts:59-78 · admin-menu.ts:23-77
export const ADMIN_START_SECTION: ManualSection = {
  id: 'start',
  title: '시작하기',
  summary: '관리자 화면에 로그인하고, 메뉴·상단바·처리 대기 현황이 어디에 있는지 익힙니다. 이 매뉴얼은 상단 계정 메뉴의 도움말에서 언제든 다시 열 수 있습니다.',
  steps: [
    {
      id: 'login',
      title: '로그인',
      paragraphs: [
        '관리자 계정의 이메일과 비밀번호로 로그인합니다. 로그인하지 않은 채 관리자 화면 주소로 들어오면 이 화면으로 옮겨지고, 로그인하면 원래 가려던 화면으로 돌아갑니다.',
      ],
      captureId: 'start-login',
      captureAlt: '관리자 로그인 화면',
      callouts: [
        { number: 1, region: 'email', label: '이메일', description: '관리자 계정 이메일입니다.' },
        { number: 2, region: 'password', label: '비밀번호', description: '틀리면 "이메일 또는 비밀번호를 확인하세요"가 나옵니다.' },
        { number: 3, region: 'submit', label: '로그인', description: '로그인하면 대시보드나 원래 가려던 화면으로 이동합니다.' },
      ],
      warnings: [],
      rules: [],
    },
    {
      id: 'layout',
      title: '화면 구성',
      paragraphs: [
        '왼쪽 메뉴는 인박스·대시보드와 회원·주문·상품·고객센터·정산·통계 묶음으로 나뉩니다. 맨 위 인박스 옆 숫자는 오늘 처리할 건수입니다.',
        '대시보드의 처리 대기 칸은 지금 손이 필요한 일을 종류별로 셉니다. 칸을 누르면 그 일을 처리하는 화면으로 바로 이동합니다.',
      ],
      captureId: 'start-layout',
      captureAlt: '관리자 대시보드 전체 화면(왼쪽 메뉴·상단바·처리 대기)',
      callouts: [
        { number: 1, region: 'sidebar', label: '메뉴', description: '업무 묶음별 화면 목록입니다. 지금 보고 있는 메뉴가 강조됩니다.' },
        { number: 2, region: 'inboxBadge', label: '인박스 건수', description: '오늘 탭에 있는 처리 대기 건수입니다.' },
        { number: 3, region: 'breadcrumb', label: '현재 위치', description: '지금 화면이 어느 메뉴 아래인지 보여 줍니다.' },
        { number: 4, region: 'pending', label: '처리 대기', description: '정산 확정·클레임 요청·배송 대기 등 손이 필요한 건수입니다. 누르면 해당 화면으로 갑니다.' },
        { number: 5, region: 'account', label: '계정 메뉴', description: '도움말과 로그아웃이 있습니다.' },
      ],
      warnings: [],
      rules: [],
    },
    {
      id: 'help',
      title: '도움말과 로그아웃',
      paragraphs: [
        '오른쪽 위 계정 메뉴를 누르면 로그인한 계정, 도움말, 로그아웃이 나옵니다. 도움말을 누르면 이 매뉴얼이 열립니다.',
      ],
      captureId: 'start-account-menu',
      captureAlt: '상단바 계정 메뉴를 연 화면',
      callouts: [
        { number: 1, region: 'account', label: '계정 메뉴', description: '눌러서 메뉴를 엽니다.' },
        { number: 2, region: 'help', label: '도움말', description: '이 매뉴얼로 이동합니다.' },
        { number: 3, region: 'logout', label: '로그아웃', description: '관리자 화면에서 로그아웃합니다.' },
      ],
      warnings: [],
      rules: [],
    },
  ],
}
