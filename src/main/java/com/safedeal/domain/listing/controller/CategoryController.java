package com.safedeal.domain.listing.controller;

import com.safedeal.domain.listing.dto.CategoryResponse;
import com.safedeal.domain.listing.service.CategoryQueryService;
import com.safedeal.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryQueryService categoryQueryService;

    /**
     * 카테고리 목록 — 활성 분류 전량. 매물 등록·검색 필터가 쓰므로 비로그인도 조회할 수 있다.
     * 파라미터가 없고 수십 건 고정이라 페이지네이션을 두지 않는다.
     */
    @GetMapping
    public ApiResponse<CategoryResponse> getCategories() {
        return ApiResponse.success(categoryQueryService.getCategories());
    }
}
