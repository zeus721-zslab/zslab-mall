import type { ManualSection } from '~/types/manual'

// 클레임 처리. 처리는 관리자만: 셀러 클레임 API는 조회(GET)만 있다(SellerClaimQueryController.java:39,54 · seller/claims/index.vue:75).
// 근거: recon §2 A2 · inbox.ts:93-94 · InboxDeadlinePolicy.java:18-33 · ClaimSuggestionRule.java:9-19 · admin-claim-view.ts:51-93 ·
// admin-inbox-view.ts:55-89 · AdminClaimQueryService.java:282-325 · AdminClaimTable.vue:207-258 · AdminClaimInspectDialog.vue:165-249
export const ADMIN_CLAIM_SECTION: ManualSection = {
  id: 'claim',
  title: '클레임 처리',
  summary: '구매자가 낸 취소·반품·교환 요청을 처리합니다. 승인·거부·회수 확인·검수는 관리자만 할 수 있고, 셀러는 같은 클레임을 조회만 합니다. 인박스의 처리 제안과 일괄 승인으로 단순한 요청을 한 번에 끝내고, 판단이 필요한 요청만 하나씩 봅니다.',
  steps: [
    {
      id: 'inbox',
      title: '인박스에서 새 클레임 확인',
      paragraphs: [
        '인박스는 처리할 일을 기한 순으로 모아 둔 화면입니다. 오늘 탭에는 기한이 오늘이거나 이미 지난 항목이, 예정 탭에는 내일 이후 항목이 있습니다.',
        '유형 칩에서 "클레임 접수"를 고르면 새로 들어온 취소·반품·교환 요청만 남습니다. 클레임 접수의 처리 기한은 접수 후 24시간입니다.',
        '항목마다 처리 제안이 붙습니다. "승인 제안"은 정해진 규칙에 맞아 승인을 권하는 요청이고, "검토 필요"는 규칙만으로 판단할 수 없어 내용을 직접 확인해야 하는 요청입니다.',
      ],
      captureId: 'claim-inbox',
      captureAlt: '관리자 인박스에서 클레임 접수 유형만 골라 본 목록',
      callouts: [
        { number: 1, region: 'typeChip', label: '클레임 접수 칩', description: '새로 들어온 클레임만 보입니다. 칩의 숫자는 해당 건수입니다.' },
        { number: 2, region: 'todayTab', label: '오늘 탭', description: '기한이 오늘이거나 지난 항목입니다. 내일 이후 항목은 예정 탭에 있습니다.' },
        { number: 3, region: 'suggestion', label: '처리 제안', description: '"승인 제안" 또는 "검토 필요"가 표시됩니다.' },
        { number: 4, region: 'deadline', label: '기한', description: '기한까지 남은 시간이나 지난 날짜입니다. 지난 항목은 빨간색으로 보입니다.' },
      ],
      warnings: [],
      rules: [
        { title: '클레임 처리 기한', items: ['클레임 접수는 24시간, 승인 뒤 회수 확인·검수 같은 후속 처리는 48시간 안에 처리합니다.'] },
      ],
    },
    {
      id: 'suggestion',
      title: '처리 제안을 보고 승인하거나 거부',
      paragraphs: [
        '항목을 누르면 오른쪽에 상세가 열립니다. 맨 위에 처리 제안과 그 근거가 있고, 아래에 유형·상태, 사유, 금액, 첨부 수가 나옵니다. 아래 캡처는 사진 첨부가 없는 상품 불량 교환이라 "검토 필요 · 증빙 없음"으로 나온 예입니다.',
        '제안은 판단을 돕는 표시이며, 승인이나 거부는 직접 눌러야 처리됩니다. 거부를 권하는 제안은 없습니다. 검토 필요 항목은 사유와 첨부를 확인한 뒤 결정합니다.',
      ],
      captureId: 'claim-suggestion',
      captureAlt: '인박스에서 교환 요청 상세를 연 화면',
      callouts: [
        { number: 1, region: 'suggestion', label: '처리 제안과 근거', description: '어떤 규칙으로 이 제안이 나왔는지 함께 보입니다(예: 증빙 없음, 기한 내 단순 변심).' },
        { number: 2, region: 'reason', label: '클레임 사유', description: '구매자가 고른 사유입니다.' },
        { number: 3, region: 'approve', label: '승인', description: '확인 창에서 한 번 더 확인한 뒤 승인됩니다.' },
        { number: 4, region: 'reject', label: '거부', description: '거부 사유를 골라 처리합니다. 구매자에게 사유가 안내됩니다.' },
      ],
      warnings: [
        // 되돌릴 수 없는 동작 #5
        { title: '클레임 승인', body: '승인한 클레임은 되돌릴 수 없습니다. 취소 요청은 승인 즉시 환불이 진행되고, 교환 요청은 승인할 때 교환 옵션 재고가 예약됩니다(재고가 부족하면 승인되지 않습니다).' },
      ],
      rules: [
        {
          title: '처리 제안 규칙',
          items: [
            '승인 제안: 아직 출고되지 않은 품목의 취소 요청, 상품불량·오배송이면서 사진이 첨부된 반품·교환, 기한 내 단순 변심 반품·교환.',
            '검토 필요: 상품불량·오배송인데 첨부가 없는 경우, 교환할 옵션의 재고가 부족한 경우, 위 규칙에 해당하지 않는 경우.',
          ],
        },
        {
          // R5 ClaimRejectRequest.java:16 · Claim.java:369-381
          title: '거부할 때',
          items: [
            '거부한 요청은 종결되고 품목은 요청 전 상태로 돌아갑니다. 구매자는 같은 품목으로 다시 요청할 수 있습니다.',
            '거부 사유는 필수이고 메모는 500자까지 남길 수 있습니다. "이미 발송됨" 사유는 취소 요청에만 쓸 수 있습니다.',
          ],
        },
        {
          // R51 admin-error-message.ts:24,93
          title: '처리가 안 될 때',
          items: ['다른 화면에서 이미 처리된 클레임이면 "현재 상태에서 처리할 수 없는 클레임입니다"가 나옵니다. 목록을 새로고침해 상태를 확인합니다.'],
        },
      ],
    },
    {
      id: 'bulk',
      title: '승인 제안을 골라 한 번에 승인',
      paragraphs: [
        '승인 제안이 붙은 클레임 접수 항목에는 왼쪽에 체크 상자가 있습니다. 여러 건을 체크하면 목록 위에 선택 건수와 "선택 승인" 버튼이 나타납니다.',
        '선택 승인을 누르면 확인 창에 유형별 건수가 나옵니다. 승인 직전에 건마다 제안을 다시 계산해, 그사이 승인 제안이 아니게 된 건은 승인하지 않습니다. 결과는 "일괄 승인 — 성공 n / 실패 m"으로 알려 주고, 실패한 건은 결과 창에서 사유를 보여 줍니다.',
      ],
      captureId: 'claim-bulk',
      captureAlt: '인박스에서 승인 제안 항목 두 건을 체크해 일괄 승인 바가 나타난 화면',
      callouts: [
        { number: 1, region: 'check', label: '선택 체크', description: '승인 제안이 붙은 클레임 접수 항목만 체크할 수 있습니다.' },
        { number: 2, region: 'count', label: '선택 건수', description: '지금 고른 건수와 최대 건수(20건)입니다.' },
        { number: 3, region: 'approve', label: '선택 승인', description: '확인 창을 거쳐 고른 건을 한꺼번에 승인합니다.' },
        { number: 4, region: 'clear', label: '선택 해제', description: '고른 항목을 모두 풉니다.' },
      ],
      warnings: [
        // 되돌릴 수 없는 동작 #6
        { title: '일괄 승인', body: '한꺼번에 승인한 클레임도 하나씩 승인한 것과 같아 되돌릴 수 없습니다. 고른 건에 취소 요청이 있으면 승인 즉시 환불이 진행됩니다.' },
      ],
      rules: [
        {
          // R30 · R50 · R52
          title: '일괄 승인 제한',
          items: [
            '한 번에 최대 20건까지 고를 수 있습니다. 20건을 채우면 더 체크할 수 없습니다.',
            '한 번에 한 종류만 고를 수 있습니다. 다른 종류(예: 셀러 독촉)를 체크하면 기존 선택이 풀립니다.',
            '실패 사유 예: 지금은 승인 제안이 아님, 교환 옵션 재고 부족, 다른 처리와 겹침.',
          ],
        },
      ],
    },
    {
      id: 'followup',
      title: '반품·교환 후속 처리 찾기',
      paragraphs: [
        '반품·교환은 승인한 뒤에도 회수 → 회수 확인 → 검수 단계가 남습니다. 취소·반품·교환 화면에서 "필요 액션" 필터를 "후속 처리 전체"로 두면 다음 처리가 필요한 클레임만 모입니다. 관리 열에는 그 단계에서 할 수 있는 버튼만 나옵니다.',
        '구매자가 아직 회수 송장을 등록하지 않은 클레임은 관리 열에 "구매자 회수 송장 등록 대기"로 표시됩니다. 구매자에게 전화 등으로 송장을 받았다면 "회수 송장 대행 등록"으로 대신 넣습니다. 회수 송장이 등록되면 "회수 확인"과 "검수"가 나타납니다.',
      ],
      captureId: 'claim-followup',
      captureAlt: '취소·반품·교환 화면에서 후속 처리가 필요한 반품 행을 본 화면',
      callouts: [
        { number: 1, region: 'tabs', label: '유형 탭', description: '전체·취소·반품·교환 중 하나로 좁힙니다.' },
        { number: 2, region: 'actionFilter', label: '필요 액션', description: '회수 확인·검수·교환품 발송처럼 다음 처리가 필요한 건만 고릅니다.' },
        { number: 3, region: 'confirmPickup', label: '회수 확인', description: '회수품 도착을 확인합니다. 검수 단계로만 넘어가고 환불은 아직 일어나지 않습니다.' },
        { number: 4, region: 'inspect', label: '검수', description: '회수 확인 전에도 열 수 있어, 회수 확인과 검수를 한 번에 처리합니다.' },
      ],
      warnings: [
        // 되돌릴 수 없는 동작 #11 · #12 · #9 · #10
        { title: '회수 송장 대행 등록', body: '등록한 회수 송장은 화면에서 지울 수 없고, 같은 클레임에 다시 등록할 수 없습니다. 택배사와 송장번호를 확인한 뒤 등록하세요.' },
        { title: '교환품 발송 · 교환품 배송완료', body: '교환 검수에 합격하면 "교환품 발송"으로 송장을 등록하고, 도착하면 "배송완료"로 마칩니다. 둘 다 취소하는 경로가 없고, 배송완료하면 품목이 교환 옵션으로 바뀝니다.' },
        { title: '환불 개시', body: '자동 환불이 붙지 않은 건에만 "환불 개시"가 나타납니다. 개시한 환불은 되돌릴 수 없고, 금액은 0보다 커야 합니다.' },
      ],
      rules: [
        {
          // R40 AdminClaimQueryService.java:282-325
          title: '관리 열 버튼이 보이는 조건',
          items: [
            '요청 상태: 승인·거부.',
            '승인된 반품·교환에 회수 송장이 있으면: 회수 확인과 검수.',
            '교환 검수 합격 후: 교환품 발송, 발송 중이면 배송완료.',
            '취소 승인 또는 반품 검수 합격 뒤 환불이 붙지 않았으면: 환불 개시.',
          ],
        },
      ],
    },
    {
      id: 'inspect',
      title: '회수품 검수',
      paragraphs: [
        '관리 열의 "검수"를 누르면 검수 창이 열립니다. 아직 회수 확인 전이면 맨 위에 "회수 확인 후 검수" 체크 상자가 나오고, 체크하면 회수 확인을 먼저 처리한 뒤 검수합니다.',
        '합격을 고르면 재입고 여부를, 불합격을 고르면 재발송할 택배사와 송장번호를 입력합니다. 반품 합격은 환불이 자동으로 진행되고, 교환 합격은 교환품 발송 대기로 넘어갑니다. 불합격은 상품을 구매자에게 다시 보냅니다.',
      ],
      captureId: 'claim-inspect',
      captureAlt: '반품 회수품 검수 창에서 합격을 고른 화면',
      callouts: [
        { number: 1, region: 'pickup', label: '회수 확인 후 검수', description: '회수 확인 전 클레임에만 보입니다. 체크하면 회수 확인을 먼저 처리합니다.' },
        { number: 2, region: 'result', label: '검수 결과', description: '합격 또는 불합격(재발송)을 고릅니다.' },
        { number: 3, region: 'restock', label: '재입고 여부', description: '합격일 때만 나옵니다. 폐기를 고르면 재고가 늘지 않습니다.' },
        { number: 4, region: 'confirm', label: '합격 처리', description: '누르면 바로 처리됩니다.' },
      ],
      warnings: [
        // 되돌릴 수 없는 동작 #7 · #8
        { title: '회수 확인과 검수', body: '회수 확인과 검수 결과(합격·불합격)는 모두 되돌릴 수 없습니다. 이미 검수한 클레임은 다시 검수할 수 없습니다.' },
      ],
      rules: [
        {
          // R6 ClaimService.java:803-818 · R1
          title: '검수 입력',
          items: [
            '합격은 재입고 여부를 반드시 골라야 합니다.',
            '불합격은 재발송 택배사와 송장번호가 필수이고, 메모는 500자까지 남길 수 있습니다.',
            '송장번호는 영문·숫자·하이픈(-)만 써서 8~20자로 입력합니다.',
          ],
        },
      ],
    },
  ],
}
