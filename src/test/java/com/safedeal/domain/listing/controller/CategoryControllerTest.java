package com.safedeal.domain.listing.controller;

import com.safedeal.domain.listing.dto.CategoryResponse;
import com.safedeal.domain.listing.service.CategoryQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 응답 형식 계약만 본다 — 프론트가 이 모양에 맞춰 붙으므로 바뀌면 즉시 알아야 한다. */
class CategoryControllerTest {

    private final CategoryQueryService categoryQueryService = mock(CategoryQueryService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new CategoryController(categoryQueryService))
            .build();

    @Test
    @DisplayName("공통 응답 형식으로 감싸서 내려준다")
    void wrapsInApiResponse() throws Exception {
        when(categoryQueryService.getCategories()).thenReturn(new CategoryResponse(List.of(
                new CategoryResponse.Item("DIGITAL", "디지털기기", null, 1, 1),
                new CategoryResponse.Item("DIGITAL_PHONE", "스마트폰", "DIGITAL", 2, 1))));

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.categories.length()").value(2))
                .andExpect(jsonPath("$.data.categories[0].code").value("DIGITAL"))
                .andExpect(jsonPath("$.data.categories[0].parentCode").doesNotExist())
                .andExpect(jsonPath("$.data.categories[1].parentCode").value("DIGITAL"));
    }

    @Test
    @DisplayName("숫자 id는 응답에 없다")
    void doesNotExposeId() throws Exception {
        when(categoryQueryService.getCategories()).thenReturn(new CategoryResponse(List.of(
                new CategoryResponse.Item("DIGITAL", "디지털기기", null, 1, 1))));

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories[0].id").doesNotExist());
    }

    @Test
    @DisplayName("분류가 하나도 없어도 빈 배열로 응답한다")
    void emptyList() throws Exception {
        when(categoryQueryService.getCategories()).thenReturn(new CategoryResponse(List.of()));

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.categories.length()").value(0));
    }
}
