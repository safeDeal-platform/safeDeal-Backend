package com.safedeal.domain.listing.service;

import com.safedeal.domain.listing.dto.CategoryResponse;
import com.safedeal.domain.listing.entity.Category;
import com.safedeal.domain.listing.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryQueryService {

    private final CategoryRepository categoryRepository;

    /**
     * 등록·필터 드롭다운에 쓸 활성 분류 전량.
     *
     * <p>비활성은 응답에서 뺀다 — 새 매물을 비활성 분류에 달면 안 된다. 다만 이미 그 분류를
     * 쓰는 매물의 표시명은 매물 조회 쪽에서 조인으로 가져오므로 여기서 빠져도 화면이 비지 않는다.
     *
     * <p>부모가 비활성이면 자식도 뺀다. 시더가 부모를 내릴 때 자식도 같이 내리지만, 데이터를
     * 손으로 고친 경우에도 소속 없는 중분류가 노출되지 않도록 조회 시점에도 막는다.
     *
     * <p><b>정렬은 대분류별로 묶는다</b> — 대분류가 나오면 그 자식들이 바로 뒤에 붙는다.
     * DB 정렬(depth → sortOrder)만 쓰면 "각 대분류의 1번째 자식"들이 한 덩어리로 묶여
     * 형제가 흩어진다. 클라이언트가 parentCode로 조립하면 화면은 같지만, 응답을 그대로 읽거나
     * 조립 없이 쓸 때 순서가 의미를 갖지 못한다. 수십 건짜리 고정 목록이라 재정렬 비용은 없다.
     */
    public CategoryResponse getCategories() {
        List<Category> all = categoryRepository.findAllByOrderByDepthAscSortOrderAsc();

        Set<String> activeRootCodes = all.stream()
                .filter(c -> !c.isLeaf() && c.isActive())
                .map(Category::getCode)
                .collect(Collectors.toSet());

        Map<String, Integer> rootOrder = all.stream()
                .filter(c -> !c.isLeaf())
                .collect(Collectors.toMap(Category::getCode, Category::getSortOrder));

        List<Category> visible = all.stream()
                .filter(Category::isActive)
                .filter(c -> !c.isLeaf() || activeRootCodes.contains(c.parentCode()))
                .sorted(Comparator
                        // 자기가 속한 대분류 순서 — 중분류는 부모의 순서를 따라간다
                        .comparingInt((Category c) -> rootOrder.getOrDefault(
                                c.isLeaf() ? c.parentCode() : c.getCode(), Integer.MAX_VALUE))
                        // 같은 묶음 안에서는 대분류가 먼저
                        .thenComparingInt(Category::getDepth)
                        .thenComparingInt(Category::getSortOrder))
                .toList();

        return CategoryResponse.from(visible);
    }
}
