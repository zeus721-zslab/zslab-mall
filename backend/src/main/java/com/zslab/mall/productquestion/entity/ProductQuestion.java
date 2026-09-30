package com.zslab.mall.productquestion.entity;

import com.zslab.mall.common.entity.AbstractPublicIdSoftDeletableEntity;
import com.zslab.mall.productquestion.enums.ProductQuestionStatus;
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
 * 상품 질문(PQN Aggregate Root·SOFT·public_id {@code pqn_}·Track 106-2·V41). 답변은 질문당 최대 1개라 같은 행의 답변 3컬럼으로 둔다.
 *
 * <p>상태 규칙(D-239): 질문자 수정은 미답변 + VISIBLE, 질문자 삭제는 미답변(숨김이어도 허용), 셀러 답변 등록·수정은 VISIBLE일 때만.
 * 위반은 {@link IllegalStateException}이고 서비스가 422로 바꾼다. 동시 명령의 직렬화는 서비스의 행 락이 맡는다.
 *
 * <p>{@code @SQLRestriction}은 Hibernate HHH-17453으로 상위 클래스에서 전파되지 않아 본 클래스에 직접 선언한다(LT-03).
 */
@Entity
@Table(name = "product_question")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductQuestion extends AbstractPublicIdSoftDeletableEntity {

    public static final int MIN_CONTENT_LENGTH = 5;
    public static final int MAX_CONTENT_LENGTH = 500;
    public static final int MAX_ANSWER_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "buyer_id", nullable = false, updatable = false)
    private Long buyerId;

    @Column(name = "content", nullable = false, length = MAX_CONTENT_LENGTH)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProductQuestionStatus status;

    /** 관리자 숨김 사유. HIDDEN일 때만 값이 있다(V41 chk_product_question_hidden_reason). */
    @Column(name = "hidden_reason", length = 200)
    private String hiddenReason;

    /** 답변 3컬럼은 함께 채워지거나 함께 비어 있다(V41 chk_product_question_answer). */
    @Column(name = "answer_content", length = MAX_ANSWER_LENGTH)
    private String answerContent;

    @Column(name = "answered_at")
    private LocalDateTime answeredAt;

    @Column(name = "answered_by")
    private Long answeredBy;

    /**
     * 공개 상태(VISIBLE)·미답변으로 질문을 만든다. 상품 노출 판정은 서비스가 한다.
     *
     * @throws IllegalArgumentException 필수값 누락
     */
    public static ProductQuestion create(Long productId, Long buyerId, String content) {
        if (productId == null || buyerId == null || content == null) {
            throw new IllegalArgumentException("ProductQuestion 필수값 누락(productId·buyerId·content).");
        }
        ProductQuestion question = new ProductQuestion();
        question.productId = productId;
        question.buyerId = buyerId;
        question.content = content;
        question.status = ProductQuestionStatus.VISIBLE;
        return question;
    }

    /**
     * 질문자 수정. 답변이 달린 뒤 고치면 답변이 다른 질문에 대한 것이 되고, 숨김 질문을 고치면 숨김 근거가 된 원문이 사라진다.
     *
     * @throws IllegalStateException 답변 완료·숨김 질문(호출부가 422로 변환)
     */
    public void edit(String content) {
        if (isAnswered()) {
            throw new IllegalStateException("답변이 달린 질문은 수정할 수 없습니다.");
        }
        if (status == ProductQuestionStatus.HIDDEN) {
            throw new IllegalStateException("숨김 처리된 질문은 수정할 수 없습니다.");
        }
        this.content = content;
    }

    /**
     * 질문자 삭제(soft delete). 답변이 달린 뒤 지우면 셀러 답변이 함께 사라지므로 막는다. 숨김은 삭제를 막지 않는다(행이 남는다).
     *
     * @throws IllegalStateException 답변 완료 질문(호출부가 422로 변환)
     */
    public void deleteByAuthor() {
        if (isAnswered()) {
            throw new IllegalStateException("답변이 달린 질문은 삭제할 수 없습니다.");
        }
        markDeleted();
    }

    /**
     * 셀러 답변 등록·수정(덮어쓰기). 시각·답변자는 마지막으로 쓴 값이다.
     *
     * @throws IllegalStateException 숨김 질문(호출부가 422로 변환)
     */
    public void answer(String answerContent, Long answeredBy, LocalDateTime answeredAt) {
        if (answerContent == null || answeredBy == null || answeredAt == null) {
            throw new IllegalArgumentException("답변 필수값 누락(answerContent·answeredBy·answeredAt).");
        }
        if (status == ProductQuestionStatus.HIDDEN) {
            throw new IllegalStateException("숨김 처리된 질문에는 답변할 수 없습니다.");
        }
        this.answerContent = answerContent;
        this.answeredBy = answeredBy;
        this.answeredAt = answeredAt;
    }

    /**
     * 관리자 숨김·숨김 해제. 숨기면 사유를 남기고, 해제하면 사유를 비운다.
     *
     * @throws IllegalStateException 같은 상태로의 재요청(호출부가 422로 변환)
     */
    public void changeStatus(ProductQuestionStatus target, String reason) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("질문 상태를 전환할 수 없습니다: " + status + " → " + target);
        }
        this.status = target;
        this.hiddenReason = target == ProductQuestionStatus.HIDDEN ? reason : null;
    }

    public boolean isAnswered() {
        return answeredAt != null;
    }

    public boolean isWrittenBy(Long userId) {
        return buyerId.equals(userId);
    }

    @Override
    protected String getPublicIdPrefix() {
        return "pqn";
    }
}
