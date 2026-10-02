import type { ManualSection } from '~/types/manual'

// 시작하기. 근거: seller/login.vue:40,50,83-99 · middleware/seller.ts:12-25 · HeaderSellerActorResolver.java:21,53-63 · SellerSidebar.vue:61,101-102 ·
// SellerTopbar.vue:49-76 · seller-dashboard-view.ts:103-109 · layouts/seller.vue:36-38 · SellerSuspendedGuard.vue:6-20 · settings/password.vue:73-122
export const SELLER_START_SECTION: ManualSection = {
  id: 'start',
  title: '시작하기',
  summary: '셀러 센터에 로그인하고, 메뉴·상단바·처리 대기 현황이 어디에 있는지 익힙니다. 이 매뉴얼은 상단 계정 메뉴의 도움말에서 언제든 다시 열 수 있습니다.',
  steps: [
    {
      id: 'login',
      title: '로그인',
      paragraphs: [
        '셀러 구성원 계정의 이메일과 비밀번호로 로그인합니다. 승인 대기 중이거나 종료된 셀러의 계정은 로그인되지 않고 "이메일 또는 비밀번호를 확인하세요"가 나옵니다.',
        '운영자에게 임시 비밀번호를 받았다면 로그인 직후 비밀번호 변경 화면으로 옮겨지고, 새 비밀번호로 바꿔야 다른 화면을 쓸 수 있습니다.',
      ],
      captureId: 'start-login',
      captureAlt: '셀러 로그인 화면',
      callouts: [
        { number: 1, region: 'email', label: '이메일', description: '셀러 구성원 계정 이메일입니다.' },
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
        '왼쪽 메뉴는 인박스·대시보드와 주문·상품·통계·정산·설정 묶음으로 나뉩니다. 오른쪽 위에는 상호와 셀러 상태가 보입니다.',
        '대시보드의 처리 대기 칸은 배송 대기·클레임 요청·재고 임박·정산 예정·장기 배송중 건수입니다. 칸을 누르면 그 일을 처리하는 화면으로 이동합니다.',
      ],
      captureId: 'start-layout',
      captureAlt: '셀러 대시보드 전체 화면(왼쪽 메뉴·상단바·처리 대기)',
      callouts: [
        { number: 1, region: 'sidebar', label: '메뉴', description: '업무 묶음별 화면 목록입니다.' },
        { number: 2, region: 'inboxBadge', label: '인박스 건수', description: '오늘 탭에 있는 처리 대기 건수입니다.' },
        { number: 3, region: 'company', label: '상호와 상태', description: '로그인한 셀러의 상호와 상태(활성·정지 등)입니다.' },
        { number: 4, region: 'pending', label: '처리 대기', description: '누르면 인박스·클레임·정산 등 해당 화면으로 갑니다.' },
        { number: 5, region: 'account', label: '계정 메뉴', description: '도움말과 로그아웃이 있습니다.' },
      ],
      warnings: [],
      rules: [
        {
          // R67 · R70 · R71 · R72
          title: '정지 상태일 때',
          items: [
            '정지된 셀러는 조회만 할 수 있습니다. 모든 화면 위에 "정지 상태의 셀러입니다. 조회는 가능하지만 주문·상품·정산 등 변경 작업은 처리되지 않습니다. 문의는 관리자에게 하세요."가 보입니다.',
            '발송·답변·판매 상태 변경 같은 버튼은 비활성이고, 마우스를 올리면 "정지 상태에서는 변경할 수 없습니다"가 보입니다. 이미지 업로드도 되지 않습니다.',
          ],
        },
      ],
    },
    {
      id: 'help',
      title: '도움말과 로그아웃',
      paragraphs: [
        '오른쪽 위 계정 메뉴를 누르면 로그인한 계정, 상호와 내 역할(대표·매니저·담당자), 도움말, 로그아웃이 나옵니다. 도움말을 누르면 이 매뉴얼이 열립니다.',
      ],
      captureId: 'start-account-menu',
      captureAlt: '상단바 계정 메뉴를 연 화면',
      callouts: [
        { number: 1, region: 'membership', label: '상호와 역할', description: '어느 셀러의 어떤 역할로 로그인했는지 보여 줍니다.' },
        { number: 2, region: 'help', label: '도움말', description: '이 매뉴얼로 이동합니다.' },
        { number: 3, region: 'logout', label: '로그아웃', description: '셀러 센터에서 로그아웃합니다.' },
      ],
      warnings: [],
      rules: [],
    },
    {
      id: 'password',
      title: '비밀번호 변경',
      paragraphs: [
        '설정의 비밀번호 변경에서 현재 비밀번호와 새 비밀번호를 입력합니다. 바꾸면 모든 기기의 셀러 로그인과 같은 계정의 구매자 로그인이 함께 로그아웃되고, 새 비밀번호로 다시 로그인합니다.',
      ],
      captureId: 'start-password',
      captureAlt: '셀러 비밀번호 변경 화면',
      callouts: [
        { number: 1, region: 'notice', label: '로그아웃 안내', description: '변경하면 다시 로그인해야 한다는 안내입니다.' },
        { number: 2, region: 'current', label: '현재 비밀번호', description: '지금 쓰는 비밀번호입니다.' },
        { number: 3, region: 'next', label: '새 비밀번호', description: '8~72자로 정하고 아래 확인란에 한 번 더 입력합니다.' },
        { number: 4, region: 'submit', label: '비밀번호 변경', description: '변경 후 로그인 화면으로 이동합니다.' },
      ],
      warnings: [],
      rules: [
        // R27 ChangePasswordRequest.java:14 · R69 SellerMeController.java:47
        { title: '비밀번호 조건', items: ['새 비밀번호는 8~72자입니다.', '정지 상태에서도 비밀번호는 바꿀 수 있습니다.'] },
      ],
    },
  ],
}
