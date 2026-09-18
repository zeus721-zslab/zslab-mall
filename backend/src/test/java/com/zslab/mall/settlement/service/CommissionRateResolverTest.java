package com.zslab.mall.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.category.entity.Category;
import com.zslab.mall.category.repository.CategoryRepository;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.repository.SellerRepository;
import com.zslab.mall.settlement.service.CommissionRateResolver.CommissionRateKey;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link CommissionRateResolver} 단위 검증(Mockito·Track 85). 3단 판정(셀러 개별율 → 카테고리율 → 플랫폼 기본율)과 다건 배치 조회(셀러·카테고리
 * 각 1회)를 확인한다. 엔티티는 setter가 없어 mock으로 율을 공급한다.
 */
@ExtendWith(MockitoExtension.class)
class CommissionRateResolverTest {

    private static final int DEFAULT_RATE = 1000;

    @Mock private SellerRepository sellerRepository;
    @Mock private CategoryRepository categoryRepository;

    private CommissionRateResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new CommissionRateResolver(sellerRepository, categoryRepository, DEFAULT_RATE);
    }

    private Seller seller(long id, Integer rate) {
        Seller seller = mock(Seller.class);
        lenient().when(seller.getId()).thenReturn(id);
        lenient().when(seller.getCommissionRate()).thenReturn(rate);
        return seller;
    }

    private Category category(long id, Integer rate) {
        Category category = mock(Category.class);
        lenient().when(category.getId()).thenReturn(id);
        lenient().when(category.getCommissionRate()).thenReturn(rate);
        return category;
    }

    @Test
    @DisplayName("셀러 개별율이 있으면 카테고리율·기본율보다 우선한다")
    void sellerRateWins() {
        List<Seller> sellers = List.of(seller(1L, 700));
        List<Category> categories = List.of(category(10L, 1500));
        when(sellerRepository.findByIdIn(any())).thenReturn(sellers);
        when(categoryRepository.findByIdIn(any())).thenReturn(categories);

        assertThat(resolver.resolve(1L, 10L)).isEqualTo(700);
    }

    @Test
    @DisplayName("셀러 개별율 NULL → 카테고리율 / 둘 다 NULL → 플랫폼 기본율 / 셀러·카테고리 미존재도 기본율")
    void fallbackChain() {
        List<Seller> sellers = List.of(seller(1L, null), seller(2L, null));
        List<Category> categories = List.of(category(10L, 1500), category(11L, null));
        when(sellerRepository.findByIdIn(any())).thenReturn(sellers);
        when(categoryRepository.findByIdIn(any())).thenReturn(categories);

        Map<CommissionRateKey, Integer> resolved = resolver.resolveAll(List.of(
                new CommissionRateKey(1L, 10L),   // 셀러 NULL → 카테고리 1500
                new CommissionRateKey(2L, 11L),   // 둘 다 NULL → 기본율
                new CommissionRateKey(9L, 99L))); // 미존재 → 기본율

        assertThat(resolved.get(new CommissionRateKey(1L, 10L))).isEqualTo(1500);
        assertThat(resolved.get(new CommissionRateKey(2L, 11L))).isEqualTo(DEFAULT_RATE);
        assertThat(resolved.get(new CommissionRateKey(9L, 99L))).isEqualTo(DEFAULT_RATE);
        assertThat(resolved).hasSize(3);
        // 배치 조회 1회씩(N+1 금지)
        verify(sellerRepository).findByIdIn(any());
        verify(categoryRepository).findByIdIn(any());
    }

    @Test
    @DisplayName("categoryId null이면 카테고리 조회를 건너뛰고 셀러율 → 기본율")
    void nullCategorySkipsLookup() {
        List<Seller> sellers = List.of(seller(1L, null));
        when(sellerRepository.findByIdIn(any())).thenReturn(sellers);

        assertThat(resolver.resolve(1L, null)).isEqualTo(DEFAULT_RATE);
        verify(categoryRepository, never()).findByIdIn(any());
        assertThat(resolver.getDefaultCommissionRate()).isEqualTo(DEFAULT_RATE);
    }

    @Test
    @DisplayName("셀러율 음수 → 판정 시 IllegalArgumentException(범위 0~10000 bp)")
    void sellerRateNegativeRejected() {
        // mock 생성(lenient stubbing)을 when() 인자 안에서 하면 UnfinishedStubbing이므로 먼저 만든다
        List<Seller> sellers = List.of(seller(1L, -1));
        when(sellerRepository.findByIdIn(any())).thenReturn(sellers);

        assertThatThrownBy(() -> resolver.resolve(1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seller.commission_rate");
    }

    @Test
    @DisplayName("카테고리율 10001 → 판정 시 IllegalArgumentException")
    void categoryRateAboveMaxRejected() {
        List<Seller> sellers = List.of(seller(1L, null));
        List<Category> categories = List.of(category(10L, 10_001));
        when(sellerRepository.findByIdIn(any())).thenReturn(sellers);
        when(categoryRepository.findByIdIn(any())).thenReturn(categories);

        assertThatThrownBy(() -> resolver.resolve(1L, 10L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("category.commission_rate");
    }

    @Test
    @DisplayName("설정 기본값 이상치(음수·10001) → 생성자(기동 시) IllegalArgumentException·경계값 0·10000은 허용")
    void defaultRateOutOfRangeRejectedAtStartup() {
        assertThatThrownBy(() -> new CommissionRateResolver(sellerRepository, categoryRepository, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("settlement.default-commission-rate");
        assertThatThrownBy(() -> new CommissionRateResolver(sellerRepository, categoryRepository, 10_001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new CommissionRateResolver(sellerRepository, categoryRepository, 0).getDefaultCommissionRate()).isZero();
        assertThat(new CommissionRateResolver(sellerRepository, categoryRepository, 10_000).getDefaultCommissionRate())
                .isEqualTo(10_000);
    }
}
