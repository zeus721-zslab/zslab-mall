package com.zslab.mall.common.config;

import java.util.Optional;
import org.springframework.data.domain.AuditorAware;

/**
 * 현재 감사자(created_by·updated_by) 공급자. 현재는 인증 컨텍스트가 없어 항상 빈 값을 반환한다(Q1=B 정합).
 *
 * <p>Spring Security·SystemUserId 도입 시 본 클래스만 수정하면 되며 Entity·BaseEntity는 영향받지 않는다.
 *
 * <p>Current policy: Audit actor is intentionally unresolved before Security integration.
 * NULL audit values are allowed by Q1 decision.
 */
public class AuditorAwareImpl implements AuditorAware<Long> {

    @Override
    public Optional<Long> getCurrentAuditor() {
        return Optional.empty();
    }
}
