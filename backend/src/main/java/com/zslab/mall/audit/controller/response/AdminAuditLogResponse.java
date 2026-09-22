package com.zslab.mall.audit.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.audit.entity.AuditLog;
import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 처리 이력 행(Track 101-A). 대상(클레임·정산)에 누가·언제·무엇을 했는지만 노출한다.
 *
 * <p><b>행위자 표기(Track 101-A 보완)</b>: 역할만으로는 운영자가 여럿일 때 누가 했는지 알 수 없어 이름·이메일을 함께 싣는다
 * (관리자 화면의 사용자 표기 관례 = 이름 + 이메일·{@code AdminOrderQueryService} 선례). 내부 회원 id는 싣지 않는다.
 * {@code actorUserId}가 없는 행(스케줄러·{@code AuditContext.system()})과 회원 행이 사라진 과거 행은 둘 다 null이며,
 * 화면은 역할만 보여 준다. 값 마스킹은 적재 시점에 {@code Masker}가 이미 끝냈으므로 조회는 저장된 diff를 그대로 옮긴다.
 *
 * @param auditPublicId 감사 행 public_id(aud_)
 * @param occurredAt    발생 시각(audit_log.created_at)
 * @param actorRole     행위자 역할(coarse·미상이면 null)
 * @param actorName     행위자 이름(해소 불가·시스템 행이면 null)
 * @param actorEmail    행위자 이메일(해소 불가·시스템 행이면 null)
 * @param action        행위 유형
 * @param changes       변경 필드 요약(diff_json 파싱 결과·없으면 빈 목록)
 */
public record AdminAuditLogResponse(
        String auditPublicId,
        @JsonSerialize(using = KstOffsetSerializer.class)
        LocalDateTime occurredAt,
        String actorRole,
        String actorName,
        String actorEmail,
        AuditLogAction action,
        List<Change> changes) {

    /** 변경 1건. before/after는 화면 표시용 문자열이며 값이 없던 필드는 null이다. */
    public record Change(String field, String before, String after) {
    }

    /** 행위자 회원이 해소되지 않으면 {@code actorName}·{@code actorEmail}에 null을 넘긴다(시스템 행·삭제된 회원). */
    public static AdminAuditLogResponse of(AuditLog auditLog, String actorName, String actorEmail, List<Change> changes) {
        return new AdminAuditLogResponse(
                auditLog.getPublicId(),
                auditLog.getCreatedAt(),
                auditLog.getActorRole(),
                actorName,
                actorEmail,
                auditLog.getAction(),
                changes);
    }
}
