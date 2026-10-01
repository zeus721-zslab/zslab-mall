package com.zslab.mall.inbox.entity;

import com.zslab.mall.inbox.enums.InboxItemType;
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
 * 인박스 보류(D-248 만료형 오버레이·V44). 대기 여부는 원천이 정하고, 이 행은 {@code untilAt} 전까지 수집 결과에서 항목을 빼는 데만 쓴다.
 * 쓰기는 {@code InboxSnoozeRepository.upsert}(동시 요청 안전)와 삭제(HARD)뿐이며, 엔티티는 수집 NOT EXISTS 대조·삭제에 쓴다.
 * 만료 행은 수집이 무시한다.
 */
@Entity
@Table(name = "inbox_snooze")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(onlyExplicitlyIncluded = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class InboxSnooze {

    public static final int MAX_REF_LENGTH = 40;
    public static final int MAX_REASON_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    @ToString.Include
    private Long id;

    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private Long ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, updatable = false, length = 30)
    private InboxItemType itemType;

    @Column(name = "item_ref", nullable = false, updatable = false, length = MAX_REF_LENGTH)
    private String itemRef;

    @Column(name = "until_at", nullable = false)
    private LocalDateTime untilAt;

    @Column(name = "reason", nullable = false, length = MAX_REASON_LENGTH)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
