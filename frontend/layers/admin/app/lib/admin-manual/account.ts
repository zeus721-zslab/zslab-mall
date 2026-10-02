import type { ManualSection } from '~/types/manual'

// 회원·셀러·관리자 계정. 근거: recon §2 A9 · members/index.vue:10 · withdrawn.vue:10 · members/[id].vue:221,242-267 · admin-risk-confirm.ts:56-70 ·
// sellers/index.vue:92 · sellers/[id].vue:115 · admin-seller.ts:37-63,184 · admin-seller-view.ts:94-120 · admins.vue:115 · admin-operator-view.ts:51-94
export const ADMIN_ACCOUNT_SECTION: ManualSection = {
  id: 'account',
  title: '회원·셀러·관리자 계정',
  summary: '구매 회원의 정보·등급을 고치고 탈퇴를 처리합니다. 셀러는 입점 등록부터 승인·정지·종료와 정산계좌·구성원을 관리하고, 관리자 계정은 슈퍼 관리자가 등록하고 역할을 회수합니다.',
  steps: [
    {
      id: 'members',
      title: '회원 찾기',
      paragraphs: [
        '일반회원 화면에서 이름·이메일·연락처로 회원을 찾고, 행의 "회원변경"으로 회원 상세에 들어갑니다.',
      ],
      captureId: 'member-list',
      captureAlt: '일반회원 목록',
      callouts: [
        { number: 1, region: 'keyword', label: '검색', description: '이름·이메일·연락처로 찾습니다(50자까지).' },
        { number: 2, region: 'sort', label: '정렬', description: '최근 가입순·오래된 가입순입니다.' },
        { number: 3, region: 'open', label: '회원변경', description: '회원 상세로 이동합니다.' },
      ],
      warnings: [],
      rules: [],
    },
    {
      id: 'member-detail',
      title: '회원 정보 수정·등급·임시 비밀번호·탈퇴',
      paragraphs: [
        '회원 상세에서 정보 수정, 등급 변경, 임시 비밀번호 발급, 탈퇴 처리를 하고, 아래 활동 탭에서 이 회원의 주문·취소·반품·교환 이력을 봅니다.',
        '임시 비밀번호는 발급 직후 화면에 한 번만 보이고, 등록된 연락처로 문자도 발송됩니다. 연락처가 없는 회원은 버튼이 비활성입니다("연락처가 없어 임시 비밀번호를 발급할 수 없습니다.").',
      ],
      captureId: 'member-detail',
      captureAlt: '회원 상세 화면의 처리 버튼',
      callouts: [
        { number: 1, region: 'edit', label: '정보 수정', description: '이름(50자)·휴대폰(20자)을 고칩니다.' },
        { number: 2, region: 'resetPassword', label: '임시 비밀번호 발급', description: '새 임시 비밀번호를 화면에 한 번 보여 주고 문자로도 보냅니다.' },
        { number: 3, region: 'withdraw', label: '탈퇴 처리', description: '확인 창을 거쳐 탈퇴시킵니다.' },
        { number: 4, region: 'grade', label: '등급 변경', description: '등급을 직접 정하고 고정 해제일을 둘 수 있습니다.' },
      ],
      warnings: [
        // 되돌릴 수 없는 동작 #19 · #20
        { title: '회원 탈퇴 처리', body: '탈퇴 처리한 회원은 되돌릴 수 없습니다. 진행 중인 주문이나 클레임이 있으면 탈퇴되지 않습니다.' },
        { title: '임시 비밀번호 발급', body: '발급하면 기존 비밀번호는 즉시 무효가 되고 로그인 세션도 끊기며, 되돌릴 수 없습니다.' },
      ],
      rules: [
        {
          // R20 · R21 · R46 · R53 · R54
          title: '처리가 안 될 때',
          items: [
            '진행 중인 주문·클레임이 있으면 "진행 중인 주문 또는 클레임이 있어 탈퇴할 수 없습니다."가 나옵니다.',
            '관리자 역할이 있는 회원은 탈퇴·임시 비밀번호를 할 수 없습니다. 운영자 화면에서 역할을 먼저 회수합니다.',
            '문자 발송에 실패하면 "SMS 발송에 실패했습니다. 비밀번호는 변경되지 않았습니다."가 나옵니다.',
            '등급 고정 해제일은 오늘 이후 날짜여야 합니다. 탈퇴 회원은 모든 버튼이 비활성입니다.',
          ],
        },
      ],
    },
    {
      id: 'withdrawn',
      title: '탈퇴회원 보기',
      paragraphs: [
        '탈퇴회원 화면은 탈퇴 처리된 회원만 보여 줍니다. 탈퇴 회원은 정보 수정·임시 비밀번호·등급 변경을 할 수 없고, 상세에서 이력만 볼 수 있습니다. 탈퇴 회원이 없으면 "탈퇴한 회원이 없습니다"가 나옵니다.',
      ],
      captureId: 'member-withdrawn',
      captureAlt: '탈퇴회원 화면',
      callouts: [
        { number: 1, region: 'keyword', label: '검색', description: '이름·이메일·연락처로 찾습니다.' },
        { number: 2, region: 'list', label: '탈퇴회원 목록', description: '일반회원 목록에 탈퇴일이 더해진 형태입니다. 없으면 빈 안내가 보입니다.' },
      ],
      warnings: [],
      rules: [],
    },
    {
      id: 'sellers',
      title: '셀러 찾기와 입점 등록',
      paragraphs: [
        '셀러 화면에서 상태·상호·사업자번호·담당자 이메일로 셀러를 찾습니다. 승인 대기 셀러가 있으면 위에 안내와 "승인 대기만 보기" 버튼이 나옵니다.',
        '"입점 등록"으로 새 셀러를 만듭니다. 처음 상태는 즉시 활성(바로 판매 가능)과 승인 대기(검토 후 활성화) 중에서 고릅니다.',
      ],
      captureId: 'seller-list',
      captureAlt: '셀러 목록',
      callouts: [
        { number: 1, region: 'provision', label: '입점 등록', description: '셀러 정보와 대표 계정을 입력하는 창을 엽니다.' },
        { number: 2, region: 'status', label: '상태 필터', description: '승인 대기·활성·정지·종료로 좁힙니다.' },
        { number: 3, region: 'rowStatus', label: '셀러 상태', description: '행을 누르면 셀러 상세로 갑니다.' },
      ],
      warnings: [],
      rules: [
        // R22 AdminSellerUpdateRequest.java:16-24
        { title: '셀러 정보 입력', items: ['상호 100자, 대표 50자, 사업자번호 20자, 이메일 254자, 연락처 20자까지입니다.', '수수료율을 바꿀 때는 사유가 필수입니다.'] },
      ],
    },
    {
      id: 'seller-status',
      title: '셀러 승인·정지·종료',
      paragraphs: [
        '셀러 상세의 기본 정보 카드에 지금 상태에서 갈 수 있는 버튼만 나옵니다. 승인 대기는 활성화·종료, 활성은 정지·종료, 정지는 활성화·종료로 갈 수 있습니다. 정지된 셀러는 조회만 할 수 있고 주문·상품·정산 변경을 할 수 없습니다.',
        '종료할 수 없는 셀러는 종료 버튼이 비활성이고, 아래에 남은 미지급 정산·진행 중 주문·처리 중 클레임 건수가 나옵니다.',
      ],
      captureId: 'seller-detail',
      captureAlt: '셀러 상세의 기본 정보와 상태 버튼',
      callouts: [
        { number: 1, region: 'status', label: '셀러 상태', description: '지금 상태입니다.' },
        { number: 2, region: 'edit', label: '정보 수정', description: '상호·연락처·수수료율 등을 고칩니다.' },
        { number: 3, region: 'suspend', label: '정지', description: '되돌릴 수 있습니다. 다시 활성화하면 원래대로 판매합니다.' },
        { number: 4, region: 'terminate', label: '종료', description: '종료 차단 사유가 남아 있으면 비활성입니다.' },
      ],
      warnings: [
        // 되돌릴 수 없는 동작 #18
        { title: '셀러 종료', body: '종료한 셀러는 다시 활성화할 수 없습니다. 미지급 정산·진행 중 주문·처리 중 클레임이 모두 끝나야 종료할 수 있습니다.' },
      ],
      rules: [
        // R23 · R42
        { title: '상태 변경 입력', items: ['상태를 바꿀 때 사유는 필수이고 200자까지 입력합니다.'] },
      ],
    },
    {
      id: 'seller-cards',
      title: '셀러 정산계좌·구성원',
      paragraphs: [
        '셀러 상세 아래쪽에서 정산계좌를 등록하거나 주 계좌를 바꾸고, 셀러 화면에 로그인할 구성원을 추가·제외하거나 역할(대표·매니저·담당자)을 바꿉니다. 정산 지급에 쓰인 계좌는 고칠 수 없으니 새 계좌를 등록해 주 계좌로 바꿉니다.',
      ],
      captureId: 'seller-cards',
      captureAlt: '셀러 상세의 정산계좌와 구성원 카드',
      callouts: [
        { number: 1, region: 'bank', label: '정산계좌', description: '계좌 등록·수정·주 계좌 지정을 합니다.' },
        { number: 2, region: 'members', label: '구성원', description: '셀러 화면에 로그인할 수 있는 계정 목록입니다.' },
        { number: 3, region: 'memberAdd', label: '구성원 추가', description: '기존 회원을 검색해 넣거나 새 계정을 만들어 넣습니다.' },
      ],
      warnings: [],
      rules: [
        {
          // R24 · R25 · R48
          title: '계좌·구성원 조건',
          items: [
            '계좌번호는 숫자와 하이픈(-)만 6~30자, 예금주는 50자까지입니다.',
            '새 구성원 계정은 이메일 254자·이름 50자·휴대폰 20자까지이고, 역할은 대표·매니저·담당자 중 하나입니다.',
            '마지막 활성 대표는 제외하거나 다른 역할로 바꿀 수 없습니다. 제외·역할 변경 사유는 200자까지입니다.',
          ],
        },
      ],
    },
    {
      id: 'admins',
      title: '관리자 계정 관리',
      paragraphs: [
        '관리자 화면에 로그인할 수 있는 운영자 계정과 역할(슈퍼 관리자·운영 관리자)을 관리합니다. 운영자 등록과 역할 회수는 슈퍼 관리자만 할 수 있고, 그 밖의 관리자에게는 버튼이 비활성으로 보입니다.',
      ],
      captureId: 'admin-operators',
      captureAlt: '관리자 계정 목록',
      callouts: [
        { number: 1, region: 'provision', label: '신규 운영자 등록', description: '기존 회원을 검색해 운영 관리자 역할을 줍니다(새 계정을 만들지 않습니다).' },
        { number: 2, region: 'role', label: '역할 필터', description: '슈퍼 관리자·운영 관리자로 좁힙니다.' },
        { number: 3, region: 'revoke', label: '역할 회수', description: '사유를 적고 관리자 역할을 거둡니다. 자기 자신은 회수할 수 없습니다.' },
      ],
      warnings: [
        // 되돌릴 수 없는 동작 #21
        { title: '슈퍼 관리자 역할 회수', body: '화면에는 슈퍼 관리자 역할을 다시 주는 기능이 없어, 회수하면 되돌릴 수 없습니다. 운영 관리자 역할은 다시 등록할 수 있습니다.' },
      ],
      rules: [
        // R26 · R47
        { title: '회수 조건', items: ['역할 회수 사유는 필수이고 200자까지입니다.', '마지막 슈퍼 관리자는 회수하거나 탈퇴할 수 없습니다("마지막 슈퍼 관리자는 탈퇴하거나 권한을 해제할 수 없습니다.").'] },
      ],
    },
  ],
}
