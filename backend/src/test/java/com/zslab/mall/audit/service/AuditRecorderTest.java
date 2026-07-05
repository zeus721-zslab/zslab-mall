package com.zslab.mall.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.audit.entity.AuditLog;
import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.repository.AuditLogRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link AuditRecorder} 오케스트레이션 검증(Mockito) — diff→mask→toJson→save 흐름·AuditLog 필드 매핑 정합·빈 diff skip.
 * DiffBuilder/Masker는 mock으로 대체해 오케스트레이션만 검증한다(각 책임 자체는 전용 테스트 소관).
 */
@ExtendWith(MockitoExtension.class)
class AuditRecorderTest {

    private static final Long ACTOR_ID = 42L;
    private static final String ACTOR_ROLE = "ADMIN";
    private static final Long TARGET_ID = 100L;

    @Mock
    private DiffBuilder diffBuilder;
    @Mock
    private Masker masker;
    @Mock
    private AuditLogRepository auditLogRepository;

    @InjectMocks
    private AuditRecorder auditRecorder;

    @Test
    @DisplayName("변경 있음: diff→mask→toJson→save·AuditLog 필드(actor·action·target·diff·ip/ua) 정합")
    void record_withChange_savesMappedAuditLog() {
        Map<String, Object> before = Map.of("status", "PENDING");
        Map<String, Object> after = Map.of("status", "SALE");
        Map<String, Object> diff = Map.of("status", Map.of("before", "PENDING", "after", "SALE"));
        String diffJson = "{\"status\":{\"before\":\"PENDING\",\"after\":\"SALE\"}}";

        when(diffBuilder.diff(before, after)).thenReturn(diff);
        when(masker.mask(diff)).thenReturn(diff);
        when(diffBuilder.toJson(diff)).thenReturn(diffJson);

        AuditContext context = AuditContext.of(ACTOR_ID, ACTOR_ROLE, "127.0.0.1", "JUnit");
        auditRecorder.record(context, AuditLogAction.APPROVE, PolymorphicTargetType.PRODUCT, TARGET_ID, before, after);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getActorUserId()).isEqualTo(ACTOR_ID);
        assertThat(saved.getActorRole()).isEqualTo(ACTOR_ROLE);
        assertThat(saved.getAction()).isEqualTo(AuditLogAction.APPROVE);
        assertThat(saved.getTargetType()).isEqualTo(PolymorphicTargetType.PRODUCT);
        assertThat(saved.getTargetId()).isEqualTo(TARGET_ID);
        assertThat(saved.getDiffJson()).isEqualTo(diffJson);
        assertThat(saved.getIpAddress()).isEqualTo("127.0.0.1");
        assertThat(saved.getUserAgent()).isEqualTo("JUnit");
    }

    @Test
    @DisplayName("변경 없음(masked diff 빈 맵): save·toJson 미호출 skip")
    void record_emptyDiff_skipsSave() {
        Map<String, Object> before = Map.of("status", "SALE");
        Map<String, Object> after = Map.of("status", "SALE");

        when(diffBuilder.diff(before, after)).thenReturn(Map.of());
        when(masker.mask(Map.of())).thenReturn(Map.of());

        auditRecorder.record(AuditContext.of(ACTOR_ID, ACTOR_ROLE), AuditLogAction.UPDATE,
                PolymorphicTargetType.SETTLEMENT, TARGET_ID, before, after);

        verify(auditLogRepository, never()).save(any(AuditLog.class));
        verify(diffBuilder, never()).toJson(any());
    }
}
