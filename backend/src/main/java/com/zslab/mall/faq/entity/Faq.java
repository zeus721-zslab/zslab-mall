package com.zslab.mall.faq.entity;

import com.zslab.mall.common.entity.AbstractSoftDeletableEntity;
import com.zslab.mall.faq.enums.FaqCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * 구매자 채팅 도우미 FAQ 1건(Track 106-3·SOFT). 노출 순서는 카테고리 안의 sortOrder다(카테고리 사이 순서는 {@link FaqCategory} 선언 순서).
 *
 * <p>{@code @SQLRestriction}은 {@code @MappedSuperclass}에서 전파되지 않아 본 클래스에 직접 선언한다(Category 선례). equals/hashCode는 id 기반.
 */
@Entity
@Table(name = "faq")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Faq extends AbstractSoftDeletableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private FaqCategory category;

    @Column(name = "question", nullable = false, length = 200)
    private String question;

    @Column(name = "answer", nullable = false, length = 2000)
    private String answer;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "visible", nullable = false)
    private boolean visible;

    public static Faq create(FaqCategory category, String question, String answer, int sortOrder, boolean visible) {
        Faq faq = new Faq();
        faq.category = category;
        faq.question = question;
        faq.answer = answer;
        faq.sortOrder = sortOrder;
        faq.visible = visible;
        return faq;
    }

    /** 관리자 수정(전체 치환). 카테고리를 옮기면 sortOrder는 호출자가 새 카테고리 끝 값을 넘긴다. */
    public void update(FaqCategory category, String question, String answer, int sortOrder, boolean visible) {
        this.category = category;
        this.question = question;
        this.answer = answer;
        this.sortOrder = sortOrder;
        this.visible = visible;
    }

    /** 카테고리 안 일괄 정렬 — 순서 배열의 index를 그대로 sortOrder로 쓴다. */
    public void changeSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
