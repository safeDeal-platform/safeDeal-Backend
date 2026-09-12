package com.safedeal.domain.listing.service;

import com.safedeal.domain.listing.dto.CategoryResponse;
import com.safedeal.domain.listing.entity.Category;
import com.safedeal.domain.listing.repository.CategoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** 노출 규칙(비활성 제외·부모 없는 중분류 제외)만 본다. DB가 필요 없으므로 목으로 세운다. */
@ExtendWith(MockitoExtension.class)
class CategoryQueryServiceTest {

    @Mock
    CategoryRepository categoryRepository;

    @InjectMocks
    CategoryQueryService categoryQueryService;

    @Test
    @DisplayName("활성 분류만 내려간다")
    void excludesInactive() {
        Category digital = Category.root("DIGITAL", "디지털기기", 1);
        Category phone = Category.child("DIGITAL_PHONE", "스마트폰", digital, 1);
        Category dropped = Category.child("DIGITAL_OLD", "단종", digital, 2);
        dropped.deactivate();
        when(categoryRepository.findAllByOrderByDepthAscSortOrderAsc())
                .thenReturn(List.of(digital, phone, dropped));

        CategoryResponse response = categoryQueryService.getCategories();

        assertThat(response.categories()).extracting(CategoryResponse.Item::code)
                .containsExactly("DIGITAL", "DIGITAL_PHONE");
    }

    @Test
    @DisplayName("부모가 비활성이면 중분류도 빠진다")
    void excludesLeafOfInactiveRoot() {
        Category retired = Category.root("RETIRED", "없어진 대분류", 1);
        Category child = Category.child("RETIRED_ONE", "그 아래", retired, 1);
        retired.deactivate();
        when(categoryRepository.findAllByOrderByDepthAscSortOrderAsc())
                .thenReturn(List.of(retired, child));

        CategoryResponse response = categoryQueryService.getCategories();

        assertThat(response.categories()).isEmpty();
    }

    @Test
    @DisplayName("대분류는 parentCode가 null, 중분류는 부모 code를 갖는다")
    void mapsParentCode() {
        Category digital = Category.root("DIGITAL", "디지털기기", 1);
        Category phone = Category.child("DIGITAL_PHONE", "스마트폰", digital, 1);
        when(categoryRepository.findAllByOrderByDepthAscSortOrderAsc())
                .thenReturn(List.of(digital, phone));

        CategoryResponse response = categoryQueryService.getCategories();

        assertThat(response.categories()).containsExactly(
                new CategoryResponse.Item("DIGITAL", "디지털기기", null, 1, 1),
                new CategoryResponse.Item("DIGITAL_PHONE", "스마트폰", "DIGITAL", 2, 1));
    }

    @Test
    @DisplayName("대분류 뒤에 그 자식들이 바로 붙는다 — 형제가 흩어지지 않는다")
    void groupsLeavesUnderTheirRoot() {
        Category digital = Category.root("DIGITAL", "디지털기기", 1);
        Category fashion = Category.root("FASHION", "의류·잡화", 2);
        Category phone = Category.child("DIGITAL_PHONE", "스마트폰", digital, 1);
        Category tablet = Category.child("DIGITAL_TABLET", "태블릿", digital, 2);
        Category men = Category.child("FASHION_MEN", "남성의류", fashion, 1);
        // 리포지토리는 depth → sortOrder 순으로 준다(형제가 흩어진 상태)
        when(categoryRepository.findAllByOrderByDepthAscSortOrderAsc())
                .thenReturn(List.of(digital, fashion, phone, men, tablet));

        CategoryResponse response = categoryQueryService.getCategories();

        assertThat(response.categories()).extracting(CategoryResponse.Item::code)
                .containsExactly(
                        "DIGITAL", "DIGITAL_PHONE", "DIGITAL_TABLET",
                        "FASHION", "FASHION_MEN");
    }

    @Test
    @DisplayName("대분류 정렬 순서가 뒤바뀌어 있어도 sortOrder대로 묶인다")
    void ordersGroupsByRootSortOrder() {
        Category later = Category.root("BBB", "나중", 2);
        Category first = Category.root("AAA", "먼저", 1);
        Category laterChild = Category.child("BBB_ONE", "나중자식", later, 1);
        Category firstChild = Category.child("AAA_ONE", "먼저자식", first, 1);
        when(categoryRepository.findAllByOrderByDepthAscSortOrderAsc())
                .thenReturn(List.of(later, first, laterChild, firstChild));

        CategoryResponse response = categoryQueryService.getCategories();

        assertThat(response.categories()).extracting(CategoryResponse.Item::code)
                .containsExactly("AAA", "AAA_ONE", "BBB", "BBB_ONE");
    }
}
