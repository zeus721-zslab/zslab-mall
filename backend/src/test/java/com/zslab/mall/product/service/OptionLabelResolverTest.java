package com.zslab.mall.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zslab.mall.product.entity.ProductOptionGroup;
import com.zslab.mall.product.entity.ProductOptionValue;
import com.zslab.mall.product.entity.ProductVariant;
import com.zslab.mall.product.repository.ProductOptionGroupRepository;
import com.zslab.mall.product.repository.ProductOptionValueRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link OptionLabelResolver} 단위 테스트(Track 75). 포맷(1~3축)·DEFAULT sentinel·미해소 null·배치 조회 횟수를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class OptionLabelResolverTest {

    private static final long COLOR_GROUP_ID = 1L;
    private static final long SIZE_GROUP_ID = 2L;
    private static final long MATERIAL_GROUP_ID = 3L;
    private static final long DEFAULT_GROUP_ID = 9L;
    private static final long BLACK_VALUE_ID = 11L;
    private static final long MEDIUM_VALUE_ID = 21L;
    private static final long COTTON_VALUE_ID = 31L;
    private static final long DEFAULT_VALUE_ID = 91L;
    private static final long MISSING_VALUE_ID = 999L;

    @Mock private ProductOptionValueRepository productOptionValueRepository;
    @Mock private ProductOptionGroupRepository productOptionGroupRepository;

    @InjectMocks private OptionLabelResolver resolver;

    private static ProductVariant variant(long id, Long option1, Long option2, Long option3) {
        ProductVariant variant = mock(ProductVariant.class);
        lenient().when(variant.getId()).thenReturn(id);
        lenient().when(variant.getOption1ValueId()).thenReturn(option1);
        lenient().when(variant.getOption2ValueId()).thenReturn(option2);
        lenient().when(variant.getOption3ValueId()).thenReturn(option3);
        return variant;
    }

    private static ProductOptionGroup group(long id, String name) {
        ProductOptionGroup group = mock(ProductOptionGroup.class);
        lenient().when(group.getId()).thenReturn(id);
        lenient().when(group.getName()).thenReturn(name);
        return group;
    }

    private static ProductOptionValue value(long id, ProductOptionGroup group, String value) {
        ProductOptionValue optionValue = mock(ProductOptionValue.class);
        lenient().when(optionValue.getId()).thenReturn(id);
        lenient().when(optionValue.getOptionGroup()).thenReturn(group);
        lenient().when(optionValue.getValue()).thenReturn(value);
        return optionValue;
    }

    private void stubCatalog() {
        ProductOptionGroup color = group(COLOR_GROUP_ID, "색상");
        ProductOptionGroup size = group(SIZE_GROUP_ID, "사이즈");
        ProductOptionGroup material = group(MATERIAL_GROUP_ID, "소재");
        ProductOptionGroup sentinel = group(DEFAULT_GROUP_ID, ProductRegistrationService.DEFAULT_OPTION_GROUP_NAME);
        List<ProductOptionValue> values = List.of(
                value(BLACK_VALUE_ID, color, "블랙"),
                value(MEDIUM_VALUE_ID, size, "M"),
                value(COTTON_VALUE_ID, material, "면"),
                value(DEFAULT_VALUE_ID, sentinel, "DEFAULT"));
        // 요청된 id만 돌려준다(미해소 id는 결과에 없음).
        when(productOptionValueRepository.findAllById(anyCollection())).thenAnswer(invocation -> {
            Iterable<Long> ids = invocation.getArgument(0);
            List<Long> requested = new ArrayList<>();
            ids.forEach(requested::add);
            return values.stream().filter(v -> requested.contains(v.getId())).toList();
        });
        List<ProductOptionGroup> groups = List.of(color, size, material, sentinel);
        when(productOptionGroupRepository.findAllById(anyCollection())).thenAnswer(invocation -> {
            Iterable<Long> ids = invocation.getArgument(0);
            List<Long> requested = new ArrayList<>();
            ids.forEach(requested::add);
            return groups.stream().filter(g -> requested.contains(g.getId())).toList();
        });
    }

    @Test
    @DisplayName("1축: '색상: 블랙'")
    void resolve_singleAxis() {
        stubCatalog();

        Map<Long, String> labels = resolver.resolve(List.of(variant(100L, BLACK_VALUE_ID, null, null)));

        assertThat(labels).containsEntry(100L, "색상: 블랙");
    }

    @Test
    @DisplayName("2축: '색상: 블랙 / 사이즈: M' (option1→2 순)")
    void resolve_twoAxes() {
        stubCatalog();

        Map<Long, String> labels = resolver.resolve(List.of(variant(100L, BLACK_VALUE_ID, MEDIUM_VALUE_ID, null)));

        assertThat(labels).containsEntry(100L, "색상: 블랙 / 사이즈: M");
    }

    @Test
    @DisplayName("3축: '색상: 블랙 / 사이즈: M / 소재: 면'")
    void resolve_threeAxes() {
        stubCatalog();

        Map<Long, String> labels = resolver.resolve(
                List.of(variant(100L, BLACK_VALUE_ID, MEDIUM_VALUE_ID, COTTON_VALUE_ID)));

        assertThat(labels).containsEntry(100L, "색상: 블랙 / 사이즈: M / 소재: 면");
    }

    @Test
    @DisplayName("DEFAULT sentinel 단순상품 → 라벨 없음(null)")
    void resolve_defaultSentinel_null() {
        stubCatalog();

        Map<Long, String> labels = resolver.resolve(List.of(variant(100L, DEFAULT_VALUE_ID, null, null)));

        assertThat(labels.get(100L)).isNull();
    }

    @Test
    @DisplayName("옵션값 미해소(행 없음) → 해당 조각 건너뜀·전부 미해소면 null·예외 없음")
    void resolve_unresolvedValue_null() {
        stubCatalog();

        Map<Long, String> labels = resolver.resolve(List.of(
                variant(100L, MISSING_VALUE_ID, null, null),
                variant(200L, BLACK_VALUE_ID, MISSING_VALUE_ID, null)));

        assertThat(labels.get(100L)).isNull();
        assertThat(labels).containsEntry(200L, "색상: 블랙");
    }

    @Test
    @DisplayName("variant N개여도 옵션값 배치 1회·그룹 배치 1회만 조회한다")
    void resolve_batchQueriesOnce() {
        stubCatalog();

        resolver.resolve(List.of(
                variant(100L, BLACK_VALUE_ID, MEDIUM_VALUE_ID, null),
                variant(200L, BLACK_VALUE_ID, null, null),
                variant(300L, COTTON_VALUE_ID, null, null)));

        verify(productOptionValueRepository, times(1)).findAllById(anyCollection());
        verify(productOptionGroupRepository, times(1)).findAllById(anyCollection());
    }

    @Test
    @DisplayName("빈 variant 목록 → 빈 맵·리포지토리 미접근")
    void resolve_empty_noQuery() {
        Map<Long, String> labels = resolver.resolve(List.of());

        assertThat(labels).isEmpty();
        verifyNoInteractions(productOptionValueRepository, productOptionGroupRepository);
    }
}
