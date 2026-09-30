package com.zslab.mall.inquiry.entity;

import com.zslab.mall.common.entity.AbstractPublicIdSoftDeletableEntity;
import com.zslab.mall.inquiry.enums.InquiryCategory;
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
import org.hibernate.annotations.SQLRestriction;

/**
 * 운영자 문의(INQ Aggregate Root·SOFT·public_id {@code inq_}·Track 106-4·V43). 구매자 비공개 문의이고 관리자 답변은 문의당 최대 1개라 같은
 * 행의 답변 3컬럼으로 둔다. 미답변·답변 완료는 {@code answeredAt}에서 파생한다(상태 컬럼 없음).
 *
 * <p>상태 규칙: 구매자 수정·삭제는 미답변일 때만, 답변 확인은 답변이 있을 때만. 위반은 {@link IllegalStateException}이고 서비스가 422로 바꾼다.
 * 관리자 답변 등록·수정은 언제나 가능하며 확인 시각을 비워 다시 미확인으로 만든다. 동시 명령의 직렬화는 서비스의 행 락이 맡는다.
 *
 * <p>{@code @SQLRestriction}은 Hibernate HHH-17453으로 상위 클래스에서 전파되지 않아 본 클래스에 직접 선언한다(LT-03).
 */
@Entity
@Table(name = "inquiry")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inquiry extends AbstractPublicIdSoftDeletableEntity {

    /** 본문 길이는 106-2 상품 질문 규칙과 같다(trim 후 5~500자·답변 1000자). */
    public static final int MIN_CONTENT_LENGTH = 5;
    public static final int MAX_CONTENT_LENGTH = 500;
    public static final int MAX_ANSWER_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "buyer_id", nullable = false, updatable = false)
    private Long buyerId;

    /** 첨부 주문(선택). 작성 시에만 정하고 바꾸지 않는다. */
    @Column(name = "order_id", updatable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private InquiryCategory category;

    @Column(name = "content", nullable = false, length = MAX_CONTENT_LENGTH)
    private String content;

    /** 답변 3컬럼은 함께 채워지거나 함께 비어 있다(V43 chk_inquiry_answer). */
    @Column(name = "answer_content", length = MAX_ANSWER_LENGTH)
    private String answerContent;

    @Column(name = "answered_at")
    private LocalDateTime answeredAt;

    @Column(name = "answered_by")
    private Long answeredBy;

    /** 구매자가 현재 답변을 확인한 시각. 답변 전·미확인이면 null(V43 chk_inquiry_answer_checked). */
    @Column(name = "answer_checked_at")
    private LocalDateTime answerCheckedAt;

    /**
     * 미답변 문의를 만든다. 주문 소유 판정은 서비스가 한다.
     *
     * @param orderId 첨부 주문 id(없으면 null)
     * @throws IllegalArgumentException 필수값 누락
     */
    public static Inquiry create(Long buyerId, Long orderId, InquiryCategory category, String content) {
        if (buyerId == null || category == null || content == null) {
            throw new IllegalArgumentException("Inquiry 필수값 누락(buyerId·category·content).");
        }
        Inquiry inquiry = new Inquiry();
        inquiry.buyerId = buyerId;
        inquiry.orderId = orderId;
        inquiry.category = category;
        inquiry.content = content;
        return inquiry;
    }

    /**
     * 구매자 수정(카테고리·본문 교체). 답변이 달린 뒤 고치면 답변이 다른 문의에 대한 것이 된다.
     *
     * @throws IllegalStateException 답변 완료 문의(호출부가 422로 변환)
     */
    public void edit(InquiryCategory category, String content) {
        if (isAnswered()) {
            throw new IllegalStateException("답변이 달린 문의는 수정할 수 없습니다.");
        }
        this.category = category;
        this.content = content;
    }

    /**
     * 구매자 삭제(soft delete). 답변이 달린 뒤 지우면 운영자 답변이 함께 사라지므로 막는다.
     *
     * @throws IllegalStateException 답변 완료 문의(호출부가 422로 변환)
     */
    public void deleteByAuthor() {
        if (isAnswered()) {
            throw new IllegalStateException("답변이 달린 문의는 삭제할 수 없습니다.");
        }
        markDeleted();
    }

    /**
     * 관리자 답변 등록·수정(덮어쓰기). 시각·답변자는 마지막으로 쓴 값이고, 구매자가 바뀐 답변을 다시 보도록 확인 시각을 비운다.
     *
     * @throws IllegalArgumentException 필수값 누락
     */
    public void answer(String answerContent, Long answeredBy, LocalDateTime answeredAt) {
        if (answerContent == null || answeredBy == null || answeredAt == null) {
            throw new IllegalArgumentException("답변 필수값 누락(answerContent·answeredBy·answeredAt).");
        }
        this.answerContent = answerContent;
        this.answeredBy = answeredBy;
        this.answeredAt = answeredAt;
        this.answerCheckedAt = null;
    }

    /**
     * 구매자 답변 확인. 이미 확인했으면 아무것도 바꾸지 않는다(멱등).
     *
     * @throws IllegalStateException 미답변 문의(호출부가 422로 변환)
     */
    public void checkAnswer(LocalDateTime checkedAt) {
        if (!isAnswered()) {
            throw new IllegalStateException("답변이 없는 문의는 확인할 수 없습니다.");
        }
        if (answerCheckedAt == null) {
            this.answerCheckedAt = checkedAt;
        }
    }

    public boolean isAnswered() {
        return answeredAt != null;
    }

    /** 답변이 있고 구매자가 아직 확인하지 않았다. */
    public boolean isAnswerUnchecked() {
        return isAnswered() && answerCheckedAt == null;
    }

    public boolean isWrittenBy(Long userId) {
        return buyerId.equals(userId);
    }

    @Override
    protected String getPublicIdPrefix() {
        return "inq";
    }
}
