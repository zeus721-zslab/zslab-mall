package com.zslab.mall;

import com.zslab.mall.support.AbstractDataJpaTest;

/**
 * Track 7 Batch-1 시드 7 Entity @DataJpaTest 공통 베이스.
 *
 * <p>Track 63: 슬라이스 설정·싱글톤 컨테이너·TestEntityManager·FK 세션 변수 복원은 {@link AbstractDataJpaTest}로 승격되었다.
 * 본 클래스는 다수 서브클래스의 상속 지점을 무수정으로 보존하기 위한 얇은 별칭이다.
 */
public abstract class Batch1DataJpaTestBase extends AbstractDataJpaTest {
}
