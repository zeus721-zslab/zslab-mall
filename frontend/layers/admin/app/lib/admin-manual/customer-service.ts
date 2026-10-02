import type { ManualSection } from '~/types/manual'

// Q&A·1:1 문의 · 리뷰 · FAQ. 근거: recon §2 A4~A6 · admin/inquiries.vue:80 · AdminInquiryAnswerDialog.vue:121,136-144 · AnswerDraftBox.vue:15-33 ·
// AnswerDraftService.java:43 · products/questions.vue:76 · AdminProductQuestionStatusDialog.vue:38-41 · reviews.vue:73 · AdminReviewStatusDialog.vue:37-41 ·
// faqs.vue:74,116 · AdminFaqEditDialog.vue:109,139
export const ADMIN_QNA_INQUIRY_SECTION: ManualSection = {
  id: 'qna-inquiry',
  title: 'Q&A·1:1 문의',
  summary: '구매자가 운영자에게 남긴 1:1 문의에 답변하고, 상품 Q&A에서 부적절한 질문을 숨깁니다. 답변 창은 FAQ·이전 답변·주문 정보에서 찾은 답안 초안을 함께 보여 줍니다. 상품 Q&A 답변은 셀러가 합니다.',
  steps: [
    {
      id: 'inquiries',
      title: '답변할 문의 찾기',
      paragraphs: [
        '1:1 문의 화면은 오래된 문의부터 보여 줍니다. 처음에는 미답변만 보이고, 답변완료·전체로 바꿀 수 있습니다. 카테고리 칩으로 문의 종류를 좁힙니다.',
      ],
      captureId: 'inquiry-list',
      captureAlt: '1:1 문의 목록',
      callouts: [
        { number: 1, region: 'answered', label: '답변 여부', description: '미답변·답변완료·전체 중에서 고릅니다.' },
        { number: 2, region: 'category', label: '카테고리', description: '주문·결제, 배송, 취소·반품·교환, 회원·계정, 기타로 좁힙니다.' },
        { number: 3, region: 'answer', label: '답변하기', description: '답변 창을 엽니다. 답변한 문의는 "답변 수정"으로 바뀝니다.' },
      ],
      warnings: [],
      rules: [
        // InboxDeadlinePolicy.java:23
        { title: '처리 기한', items: ['인박스 기준으로 1:1 문의는 접수 후 24시간 안에 답변합니다.'] },
      ],
    },
    {
      id: 'answer',
      title: '답안 초안으로 답변하기',
      paragraphs: [
        '답변 창을 열 때마다 답안 초안을 새로 계산합니다. 근거(FAQ·이전 답변·주문 정보, 종류마다 최대 3건)가 있으면 근거 목록과 "초안 사용" 버튼이 나옵니다. "초안 사용"을 눌러야 입력란이 채워지고, 열자마자 자동으로 덮어쓰지는 않습니다.',
        '근거가 없으면 "근거 부족 — 직접 작성해 주세요."가 나옵니다. 맞는 FAQ가 없는 문의에는 "FAQ 후보" 표시와 "답변 저장 후 FAQ로 등록" 체크 상자가 나오고, 체크한 채 등록하면 이 답변으로 FAQ 등록 창이 이어서 열립니다.',
        '답변은 구매자의 "내 문의"에만 보이며, 수정하면 구매자에게 새 답변으로 표시됩니다.',
      ],
      captureId: 'inquiry-answer',
      captureAlt: '1:1 문의 답변 창(답안 초안 포함)',
      callouts: [
        { number: 1, region: 'question', label: '문의 내용', description: '구매자가 남긴 문의입니다.' },
        { number: 2, region: 'draft', label: '답안 초안', description: '근거 목록과 "초안 사용" 버튼이 있습니다.' },
        { number: 3, region: 'content', label: '답변', description: '1,000자까지 입력합니다.' },
        { number: 4, region: 'confirm', label: '등록', description: '답변을 저장합니다. 수정할 때는 "수정"으로 보입니다.' },
      ],
      warnings: [],
      rules: [
        // R12 Inquiry.java:39
        { title: '답변 입력', items: ['답변은 필수이고 1,000자까지 입력할 수 있습니다.', '초안 근거를 불러오지 못하면 "답안 초안을 불러오지 못했습니다. 직접 작성해 주세요."가 나옵니다.'] },
      ],
    },
    {
      id: 'questions',
      title: '상품 Q&A 숨기기',
      paragraphs: [
        '상품 질문 화면에서 광고성 질문처럼 부적절한 질문을 숨기거나 숨김을 풉니다. 숨기면 상품 페이지 질문 목록과 즉시 답에서 바로 빠지고, 작성자는 수정할 수 없으며 셀러는 답변할 수 없습니다.',
      ],
      captureId: 'question-list',
      captureAlt: '관리자 상품 질문 목록',
      callouts: [
        { number: 1, region: 'status', label: '상태 필터', description: '공개·숨김으로 좁힙니다.' },
        { number: 2, region: 'rowStatus', label: '상태', description: '지금 공개인지 숨김인지 보여 줍니다.' },
        { number: 3, region: 'change', label: '숨김 / 숨김 해제', description: '사유를 적는 확인 창을 엽니다.' },
      ],
      warnings: [],
      rules: [
        // R13 AdminProductQuestionStatusChangeRequest.java:13 · R45
        { title: '숨김 조건', items: ['숨김·해제 사유는 필수이고 200자까지 입력합니다. 처리 이력에 남습니다.', '숨긴 질문에는 셀러가 답변할 수 없습니다.'] },
      ],
    },
  ],
}

export const ADMIN_REVIEW_SECTION: ManualSection = {
  id: 'review',
  title: '리뷰',
  summary: '구매자가 쓴 상품 리뷰를 확인하고, 부적절한 리뷰를 숨기거나 숨김을 풉니다.',
  steps: [
    {
      id: 'list',
      title: '숨길 리뷰 찾기',
      paragraphs: [
        '리뷰 화면에서 상태(공개·숨김)로 리뷰를 좁히고, 별점과 내용을 확인합니다. 행의 "숨김"을 누르면 확인 창이 열립니다.',
      ],
      captureId: 'review-list',
      captureAlt: '관리자 리뷰 목록',
      callouts: [
        { number: 1, region: 'status', label: '상태 필터', description: '공개·숨김으로 좁힙니다.' },
        { number: 2, region: 'rating', label: '별점', description: '구매자가 남긴 별점입니다.' },
        { number: 3, region: 'change', label: '숨김 / 숨김 해제', description: '사유를 적는 확인 창을 엽니다.' },
      ],
      warnings: [],
      rules: [],
    },
    {
      id: 'hide',
      title: '리뷰 숨기기',
      paragraphs: [
        '숨기면 상품 페이지의 리뷰 목록·요약·사진에서 바로 빠지고, 작성자는 리뷰를 수정할 수 없습니다. 숨김을 해제하면 상품 페이지에 다시 보입니다.',
      ],
      captureId: 'review-hide',
      captureAlt: '리뷰 숨김 확인 창',
      callouts: [
        { number: 1, region: 'target', label: '대상 리뷰', description: '숨길 리뷰입니다.' },
        { number: 2, region: 'message', label: '안내', description: '숨기면 무엇이 바뀌는지 적혀 있습니다.' },
        { number: 3, region: 'reason', label: '사유', description: '필수입니다(예: 광고성 게시물).' },
        { number: 4, region: 'confirm', label: '확인', description: '사유를 입력하면 누를 수 있습니다.' },
      ],
      warnings: [],
      rules: [
        // R13 AdminReviewStatusChangeRequest.java:13 · ReviewStatus.java:13-15
        { title: '숨김 조건', items: ['사유는 필수이고 200자까지 입력합니다. 처리 이력에 남습니다.', '이미 숨긴 리뷰를 다시 숨기거나, 공개 리뷰의 숨김을 해제할 수는 없습니다.'] },
      ],
    },
  ],
}

export const ADMIN_FAQ_SECTION: ManualSection = {
  id: 'faq',
  title: 'FAQ',
  summary: '구매자 화면 채팅 도우미에 보이는 자주 묻는 질문을 등록·수정하고, 카테고리 안 순서와 공개 여부를 관리합니다.',
  steps: [
    {
      id: 'list',
      title: 'FAQ 목록 관리',
      paragraphs: [
        '카테고리 칩을 고르면 그 카테고리의 FAQ가 순서대로 보입니다. 위·아래 화살표는 누르는 즉시 순서가 바뀝니다. 숨김으로 바꾼 FAQ는 도우미에서 바로 빠집니다.',
      ],
      captureId: 'faq-list',
      captureAlt: 'FAQ 관리 화면',
      callouts: [
        { number: 1, region: 'create', label: 'FAQ 등록', description: '새 FAQ를 만드는 창을 엽니다.' },
        { number: 2, region: 'categories', label: '카테고리', description: '카테고리마다 FAQ 건수가 함께 보입니다.' },
        { number: 3, region: 'order', label: '순서 바꾸기', description: '같은 카테고리 안에서 위·아래로 옮깁니다. 누르는 즉시 저장됩니다.' },
        { number: 4, region: 'visible', label: '공개 상태', description: '공개 또는 숨김입니다.' },
        { number: 5, region: 'delete', label: '삭제', description: '확인 창을 거쳐 삭제합니다.' },
      ],
      warnings: [
        // 되돌릴 수 없는 동작 #14
        { title: 'FAQ 삭제', body: '삭제한 FAQ는 구매자 도우미에서 바로 사라지고 되돌릴 수 없습니다. 잠시 내리려면 삭제 대신 숨김으로 바꿉니다.' },
      ],
      rules: [],
    },
    {
      id: 'edit',
      title: 'FAQ 등록·수정',
      paragraphs: [
        'FAQ 창에서 카테고리·질문·답변과 공개 여부를 정합니다. 카테고리를 바꿔 저장하면 새 카테고리의 맨 끝으로 이동합니다.',
      ],
      captureId: 'faq-edit',
      captureAlt: 'FAQ 등록 창',
      callouts: [
        { number: 1, region: 'category', label: '카테고리', description: '주문·결제, 배송, 취소·반품·교환, 회원·계정, 리뷰·문의 중에서 고릅니다.' },
        { number: 2, region: 'question', label: '질문', description: '200자까지 입력합니다.' },
        { number: 3, region: 'answer', label: '답변', description: '2,000자까지 입력합니다.' },
        { number: 4, region: 'visible', label: '공개 여부', description: '공개면 구매자 도우미에 보이고, 숨김이면 보이지 않습니다.' },
      ],
      warnings: [],
      rules: [
        // R14 FaqWriteRequest.java:9-13
        { title: 'FAQ 입력', items: ['카테고리·질문·답변·공개 여부는 모두 필수입니다. 질문은 200자, 답변은 2,000자까지입니다.'] },
      ],
    },
  ],
}
