package com.zslab.mall.audit.service;

import com.zslab.mall.audit.entity.AuditLog;
import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.repository.AuditLogRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 공통 감사 적재 오케스트레이션(Track 52 Phase 1·결정1 동기 적재·결정3 역할 분리). 변경 diff 생성 →
 * 마스킹 → {@link AuditLog} 조립 → 저장 흐름을 조율한다.
 *
 * <p><b>트랜잭션(결정1)</b>: 별도 전파를 지정하지 않아 호출자 트랜잭션에 그대로 참여한다(같은 TX 동기 save). 이벤트·
 * {@code AFTER_COMMIT}·{@code REQUIRES_NEW}는 쓰지 않는다 — 감사 실패는 호출자 트랜잭션과 함께 롤백된다(감사 무결성 우선).
 *
 * <p><b>skip 규약</b>: 마스킹 후 diff가 비면(= before/after가 모든 필드에서 동일한 no-op 변경) 적재하지 않고 반환한다.
 * 이번 트랙 3소비처(정산·상품승인·등급)는 항상 status·grade 등 전이 필드를 before/after로 전달하므로 실제 상태 변경은 빈 diff가
 * 아니며, 빈 diff는 멱등 재요청 같은 무변경 상황을 뜻한다. 무변경까지 적재가 필요한 행위가 생기면 호출자가 전이 필드를
 * before/after에 실어 전달하면 된다(현 3소비처 시나리오 기준·별도 force 플래그 미도입·YAGNI).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditRecorder {

    private final DiffBuilder diffBuilder;
    private final Masker masker;
    private final AuditLogRepository auditLogRepository;

    /**
     * 변경 이력을 감사 로그로 적재한다. before/after의 변경분만 diff_json에 담기며, 민감 필드는 마스킹된다.
     *
     * @param context    행위자 컨텍스트(actor·ip·ua)
     * @param action     감사 행위 유형
     * @param targetType polymorphic 대상 유형
     * @param targetId   polymorphic 대상 id(논리참조)
     * @param before     변경 전 필드맵(null 허용 = 빈 맵)
     * @param after      변경 후 필드맵(null 허용 = 빈 맵)
     * @throws IllegalArgumentException context가 null이거나 AuditLog 필수값이 누락된 경우
     */
    public void record(AuditContext context, AuditLogAction action, PolymorphicTargetType targetType,
            Long targetId, Map<String, Object> before, Map<String, Object> after) {
        Objects.requireNonNull(context, "AuditContext는 null일 수 없습니다.");

        Map<String, Object> masked = masker.mask(diffBuilder.diff(before, after));
        if (masked.isEmpty()) {
            log.info("[Audit] 변경 없음 → 감사 적재 skip: action={} targetType={} targetId={}", action, targetType, targetId);
            return;
        }

        String diffJson = diffBuilder.toJson(masked);
        AuditLog auditLog = AuditLog.create(
                context.actorUserId(),
                context.actorRole(),
                action,
                targetType,
                targetId,
                diffJson,
                context.ipAddress(),
                context.userAgent());
        auditLogRepository.save(auditLog);
        log.info("[Audit] 감사 적재: action={} targetType={} targetId={} actorUserId={}",
                action, targetType, targetId, context.actorUserId());
    }
}
