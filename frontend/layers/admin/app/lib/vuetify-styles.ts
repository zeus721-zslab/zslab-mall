// vuetify/styles 부수효과 import 전용 모듈(FE-22c). `import('vuetify/styles')` 직접 동적 import는 vue-tsc TS2306(styles.d.ts is not a module)이라
// 이 모듈을 동적 import한다. 순서: Vuetify 리셋·유틸 → 관리자 폰트 재정의(D-14).
import 'vuetify/styles'
import '#layers/admin/app/assets/css/admin-vuetify.css'
// 위험 조작 시각 규약(Track 102 FE-64·셀러 레이어와 공용 1곳).
import '~/assets/css/risk-action.css'
