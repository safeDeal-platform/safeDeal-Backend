package com.safedeal.domain.listing.controller;

import com.safedeal.domain.listing.dto.ListingDetailResponse;
import com.safedeal.domain.listing.dto.ListingSummaryResponse;
import com.safedeal.domain.listing.entity.ItemCondition;
import com.safedeal.domain.listing.entity.ListingStatus;
import com.safedeal.domain.listing.service.ListingCommandService;
import com.safedeal.domain.listing.service.ListingQueryService;
import com.safedeal.global.response.CursorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 응답 형식 계약만 본다 — 프론트가 이 모양에 맞춰 붙는다. */
class ListingControllerTest {

    private final ListingCommandService commandService = mock(ListingCommandService.class);
    private final ListingQueryService queryService = mock(ListingQueryService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ListingController(commandService, queryService))
            .build();

    @Test
    @DisplayName("목록은 items·nextCursor·hasNext 형태로 내려간다")
    void listShape() throws Exception {
        when(queryService.getListings(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new CursorResponse<>(List.of(new ListingSummaryResponse(
                        "01J3A", "아이폰", 950_000, "DIGITAL_PHONE", "서울특별시", "강남구",
                        ListingStatus.ACTIVE, Instant.parse("2026-08-30T00:00:00Z"))),
                        "eyJ2IjoxfQ", true));

        mockMvc.perform(get("/api/listings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].publicId").value("01J3A"))
                .andExpect(jsonPath("$.data.items[0].id").doesNotExist())
                .andExpect(jsonPath("$.data.nextCursor").value("eyJ2IjoxfQ"))
                .andExpect(jsonPath("$.data.hasNext").value(true));
    }

    @Test
    @DisplayName("마지막 페이지면 nextCursor가 없다")
    void lastPageHasNoCursor() throws Exception {
        when(queryService.getListings(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new CursorResponse<>(List.of(), null, false));

        mockMvc.perform(get("/api/listings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("상세는 public_id로 조회하고 내부 id를 노출하지 않는다")
    void detailShape() throws Exception {
        when(queryService.getListing("01J3A")).thenReturn(new ListingDetailResponse(
                "01J3A", "아이폰", "설명", 950_000, "DIGITAL_PHONE", "스마트폰",
                ItemCondition.LIKE_NEW, "서울특별시", "강남구", ListingStatus.ACTIVE, 0,
                Instant.parse("2026-08-30T00:00:00Z"), Instant.parse("2026-08-30T00:00:00Z")));

        mockMvc.perform(get("/api/listings/01J3A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicId").value("01J3A"))
                .andExpect(jsonPath("$.data.categoryCode").value("DIGITAL_PHONE"))
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andExpect(jsonPath("$.data.sellerId").doesNotExist());
    }
}
