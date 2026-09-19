// vuetify/styles 부수효과 import 전용 모듈(관리자 FE-22c 동형). `import('vuetify/styles')` 직접 동적 import는 vue-tsc TS2306(styles.d.ts is not a module)이라
// 이 모듈을 동적 import한다. 순서: Vuetify 리셋·유틸 → 셀러 폰트·톤 재정의.
import 'vuetify/styles'
import '#layers/seller/app/assets/css/seller-vuetify.css'
