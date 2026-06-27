package com.zslab.mall.order.controller.response;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * 페이징 응답 DTO(D-54). Spring Data {@link Page} 직접 직렬화 금지·필드 5개({@code items·page·size·totalCount·hasNext}) 한정.
 */
public record PagedResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalCount,
        boolean hasNext) {

    /** Repository 반환 {@link Page}를 응답 DTO로 변환한다(노출 필드 5개 한정). */
    public static <T> PagedResponse<T> from(Page<T> page) {
        return new PagedResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.hasNext());
    }
}
