package com.zslab.mall.claim.entity;

import com.zslab.mall.claim.enums.ClaimDecision;
import com.zslab.mall.claim.enums.ClaimSuggestion;
import com.zslab.mall.claim.enums.ClaimSuggestionRule;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 클레임 처리 제안 기록(D-250·V45). 관리자 승인·거부와 같은 트랜잭션에서 1행을 남기는 append-only 기록이다.
 */
@Entity
@Table(name = "claim_suggestion_record")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(onlyExplicitlyIncluded = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ClaimSuggestionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    @ToString.Include
    private Long id;

    @Column(name = "claim_id", nullable = false, updatable = false)
    private Long claimId;

    @Enumerated(EnumType.STRING)
    @Column(name = "suggestion", nullable = false, updatable = false)
    private ClaimSuggestion suggestion;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_key", nullable = false, updatable = false)
    private ClaimSuggestionRule ruleKey;

    @Column(name = "input_snapshot", nullable = false, updatable = false, columnDefinition = "LONGTEXT")
    private String inputSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, updatable = false)
    private ClaimDecision decision;

    @Column(name = "decided_by", nullable = false, updatable = false)
    private Long decidedBy;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private LocalDateTime decidedAt;

    public static ClaimSuggestionRecord of(Long claimId, ClaimSuggestionRule ruleKey, String inputSnapshot,
            ClaimDecision decision, Long decidedBy, LocalDateTime decidedAt) {
        ClaimSuggestionRecord record = new ClaimSuggestionRecord();
        record.claimId = claimId;
        record.suggestion = ruleKey.suggestion();
        record.ruleKey = ruleKey;
        record.inputSnapshot = inputSnapshot;
        record.decision = decision;
        record.decidedBy = decidedBy;
        record.decidedAt = decidedAt;
        return record;
    }
}
