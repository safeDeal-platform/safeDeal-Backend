package com.safedeal.domain.listing.controller;

import com.safedeal.domain.listing.dto.FavoriteSummaryResponse;
import com.safedeal.domain.listing.dto.FavoriteToggleResponse;
import com.safedeal.domain.listing.entity.ListingStatus;
import com.safedeal.domain.listing.service.FavoriteCommandService;
import com.safedeal.domain.listing.service.FavoriteQueryService;
import com.safedeal.global.response.CursorResponse;
import com.safedeal.global.security.AuthenticatedUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 응답 형식 계약만 본다 — 프론트가 이 모양에 맞춰 붙는다. */
class FavoriteControllerTest {

    private static final Long USER_ID = 7L;

    private final FavoriteCommandService commandService = mock(FavoriteCommandService.class);
    private final FavoriteQueryService queryService = mock(FavoriteQueryService.class);

    /** standalone MockMvc에는 시큐리티가 없어 principal이 비므로 고정 사용자를 꽂아준다. */
    private static final HandlerMethodArgumentResolver PRINCIPAL = new HandlerMethodArgumentResolver() {
        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                      NativeWebRequest request, WebDataBinderFactory binder) {
            return new AuthenticatedUser(USER_ID, "USER");
        }
    };

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new FavoriteController(commandService, queryService))
            .setCustomArgumentResolvers(PRINCIPAL)
            .build();

    @Test
    @DisplayName("찜 등록은 201과 favorited=true를 준다")
    void addReturnsCreated() throws Exception {
        when(commandService.add(eq(USER_ID), eq("01J3A"))).thenReturn(FavoriteToggleResponse.on());

        mockMvc.perform(post("/api/listings/01J3A/favorite"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.favorited").value(true));
    }

    @Test
    @DisplayName("찜 해제는 200과 favorited=false를 준다")
    void removeReturnsOk() throws Exception {
        when(commandService.remove(eq(USER_ID), eq("01J3A"))).thenReturn(FavoriteToggleResponse.off());

        mockMvc.perform(delete("/api/listings/01J3A/favorite"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorited").value(false));
    }

    @Test
    @DisplayName("목록은 매물 요약·기준가·찜한 시각을 담고 내부 id를 노출하지 않는다")
    void listShape() throws Exception {
        when(queryService.getMyFavorites(eq(USER_ID), any(), any()))
                .thenReturn(new CursorResponse<>(List.of(new FavoriteSummaryResponse(
                        new FavoriteSummaryResponse.ListingRef(
                                "01J3A", "아이폰", 900_000, ListingStatus.SOLD),
                        950_000, Instant.parse("2026-08-30T00:00:00Z"))),
                        "eyJ2IjoxfQ", true));

        mockMvc.perform(get("/api/users/me/favorites"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].listing.publicId").value("01J3A"))
                .andExpect(jsonPath("$.data.items[0].listing.id").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].listing.status").value("SOLD"))
                .andExpect(jsonPath("$.data.items[0].notifyBasePrice").value(950_000))
                .andExpect(jsonPath("$.data.items[0].favoritedAt").exists())
                .andExpect(jsonPath("$.data.nextCursor").value("eyJ2IjoxfQ"))
                .andExpect(jsonPath("$.data.hasNext").value(true));
    }
}
