import type { ManualSection } from '~/types/manual'

// 상품·재고. 근거: recon §2 A3 · admin/products/index.vue:112-144,269 · products/new.vue:44 · categories.vue:90-96,114 ·
// admin-risk-confirm.ts:40-53 · admin-product-view.ts:65-70 · AdminProductBulkStatusRequest.java:14-15 · ProductStatus.java:34-42
export const ADMIN_PRODUCT_SECTION: ManualSection = {
  id: 'product',
  title: '상품·재고',
  summary: '셀러가 등록한 상품을 승인·거부하고, 판매 상태와 품절을 관리합니다. 여러 상품을 한 번에 바꾸는 일괄 처리와 카테고리 관리도 여기서 합니다.',
  steps: [
    {
      id: 'list',
      title: '상품 목록과 일괄 처리',
      paragraphs: [
        '상품 목록에서 상태·품절·셀러·카테고리·재고로 상품을 좁힙니다. 셀러가 등록한 상품은 승인대기로 들어오며, 승인대기를 고르면 승인할 상품만 남습니다.',
        '행 왼쪽을 체크하면 위에 일괄 바가 나타납니다. 고른 상품의 판매 상태(판매중·판매중지)나 품절 여부를 한 번에 바꿉니다. 승인대기 상품에 판매중을 적용하면 승인입니다.',
      ],
      captureId: 'product-list',
      captureAlt: '관리자 상품 목록에서 상품 두 개를 체크해 일괄 바가 나타난 화면',
      callouts: [
        { number: 1, region: 'filters', label: '검색·필터', description: '상품명·ID, 상태, 품절, 셀러, 카테고리, 재고로 좁힙니다.' },
        { number: 2, region: 'bulkBar', label: '일괄 바', description: '고른 상품 수가 보이고, 상태 변경·품절 변경을 고릅니다.' },
        { number: 3, region: 'statusApply', label: '상태 적용', description: '확인 창을 거쳐 고른 상품에 한꺼번에 적용합니다. 결과 창에 성공·실패가 나옵니다.' },
        { number: 4, region: 'soldOut', label: '수동 품절', description: '누르는 즉시 품절로 바뀝니다(확인 창 없음).' },
      ],
      warnings: [],
      rules: [
        {
          // R32 · R34 · R28
          title: '일괄 처리 조건',
          items: [
            '한 번에 최대 100개까지 바꿀 수 있고, 일괄 상태는 판매중·판매중지 두 가지입니다.',
            '검색 조건을 바꾸면 고른 상품이 풀립니다.',
            '한 페이지에 20·50·100개씩 볼 수 있고, 검색어는 50자까지 입력합니다.',
          ],
        },
      ],
    },
    {
      id: 'manage',
      title: '상품 하나 관리하기',
      paragraphs: [
        '행 끝의 관리 메뉴에서 상품 하나의 판매 상태를 바꾸거나, 거부한 상품의 거부를 철회하거나, 상품을 삭제합니다. 수정 아이콘을 누르면 상품 수정 화면으로 갑니다.',
        '관리자가 판매중지한 상품은 셀러가 재판매할 수 없습니다. 셀러 화면에는 "관리자 판매중지"로 보입니다.',
      ],
      captureId: 'product-menu',
      captureAlt: '판매중 상품 행의 관리 메뉴를 연 화면',
      callouts: [
        { number: 1, region: 'edit', label: '수정', description: '상품 수정 화면으로 이동합니다.' },
        { number: 2, region: 'stop', label: '판매중지로', description: '판매중 상품을 판매중지합니다.' },
        { number: 3, region: 'delete', label: '삭제', description: '확인 창을 거쳐 상품을 삭제합니다.' },
      ],
      warnings: [
        // 되돌릴 수 없는 동작 #13 · R56
        { title: '상품 삭제', body: '삭제한 상품은 관리자 목록과 구매자 화면에서 사라지고 되돌릴 수 없습니다. 주문 이력이 있는 상품은 삭제되지 않으니("주문 이력이 있는 상품은 삭제할 수 없습니다. 판매중지로 전환하세요.") 판매중지로 바꿉니다.' },
      ],
      rules: [
        {
          // R44 · R10 · admin-product-view.ts:65-70
          title: '거부와 거부 철회',
          items: [
            '거부는 되돌릴 수 있습니다. 거부된 상품에만 "거부 철회"가 나오고, 철회하면 다시 승인대기로 돌아갑니다.',
            '거부·거부 철회 사유는 200자까지 입력합니다.',
          ],
        },
      ],
    },
    {
      id: 'new',
      title: '상품 등록',
      paragraphs: [
        '관리자도 셀러 대신 상품을 등록할 수 있습니다. 기본정보·이미지·옵션을 입력하고 등록하면 승인대기 상태로 만들어지고, 승인한 뒤에 판매됩니다.',
      ],
      captureId: 'product-new',
      captureAlt: '관리자 상품 등록 화면의 기본정보 영역',
      callouts: [
        { number: 1, region: 'seller', label: '셀러', description: '어느 셀러의 상품인지 고릅니다. 등록 뒤에는 바꿀 수 없습니다.' },
        { number: 2, region: 'category', label: '카테고리', description: '상품이 들어갈 카테고리입니다.' },
        { number: 3, region: 'name', label: '상품명', description: '200자까지 입력합니다.' },
        { number: 4, region: 'price', label: '판매가', description: '0원부터 10억 원까지 입력합니다.' },
      ],
      warnings: [],
      rules: [
        {
          // R9 Product.java:41 · ProductRegistrationRequest.java:20-25 · ProductVariantRequest.java:18-23
          title: '입력 제한',
          items: [
            '옵션 코드 50자, SKU·바코드 100자, 옵션값 100자, 옵션 그룹명 50자까지 입력합니다.',
            '초기 재고는 0~1,000,000개입니다.',
          ],
        },
      ],
    },
    {
      id: 'category',
      title: '카테고리 관리',
      paragraphs: [
        '카테고리 화면에서 루트 카테고리의 이름·노출 순서·수수료율을 관리합니다. 수수료율은 셀러 개별 수수료율이 없을 때 적용되고, 바꾼 뒤 새로 생기는 주문부터 반영됩니다.',
        '위·아래 화살표는 누르는 즉시 순서가 바뀝니다. 연결된 상품이 있는 카테고리는 삭제 버튼이 비활성이고, 버튼에 마우스를 올리면 연결된 상품 수가 보입니다.',
      ],
      captureId: 'product-category',
      captureAlt: '카테고리 관리 화면',
      callouts: [
        { number: 1, region: 'create', label: '카테고리 등록', description: '이름과 수수료율을 입력해 새 카테고리를 만듭니다.' },
        { number: 2, region: 'order', label: '순서 바꾸기', description: '누르는 즉시 순서가 바뀝니다.' },
        { number: 3, region: 'edit', label: '수정', description: '이름·수수료율을 고칩니다.' },
        { number: 4, region: 'delete', label: '삭제', description: '같은 이름으로 다시 만들 수 있어 되돌릴 수 있는 동작입니다.' },
      ],
      warnings: [],
      rules: [
        {
          // R15 · R57
          title: '카테고리 입력',
          items: [
            '이름은 200자까지, 수수료율은 0~100%입니다(소수 둘째 자리까지).',
            '같은 이름의 카테고리는 만들 수 없습니다("같은 이름의 카테고리가 이미 있습니다.").',
          ],
        },
      ],
    },
  ],
}
