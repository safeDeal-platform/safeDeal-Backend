package com.safedeal.domain.listing.controller;

import com.safedeal.domain.listing.dto.FavoriteSummaryResponse;
import com.safedeal.domain.listing.dto.FavoriteToggleResponse;
import com.safedeal.domain.listing.service.FavoriteCommandService;
import com.safedeal.domain.listing.service.FavoriteQueryService;
import com.safedeal.global.response.ApiResponse;
import com.safedeal.global.response.CursorResponse;
import com.safedeal.global.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 찜.
 *
 * <p>등록·해제는 매물 하위 경로이고 목록은 내 정보 하위 경로라 매핑 루트가 갈린다 —
 * {@code ListingController}에 끼워 넣으면 클래스 레벨 {@code @RequestMapping}과 어긋나므로
 * 도메인 기능 단위로 컨트롤러를 따로 둔다. 세 API 모두 인증 필수다.
 */
@RestController
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteCommandService favoriteCommandService;
    private final FavoriteQueryService favoriteQueryService;

    /** 찜 등록. 이미 찜한 상태여도 201로 같은 응답을 준다(멱등). */
    @PostMapping("/api/listings/{publicId}/favorite")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FavoriteToggleResponse> add(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String publicId) {
        return ApiResponse.success(favoriteCommandService.add(user.userId(), publicId));
    }

    /** 찜 해제. 찜하지 않은 상태여도 200으로 같은 응답을 준다(멱등). */
    @DeleteMapping("/api/listings/{publicId}/favorite")
    public ApiResponse<FavoriteToggleResponse> remove(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String publicId) {
        return ApiResponse.success(favoriteCommandService.remove(user.userId(), publicId));
    }

    /** 내 찜 목록. 찜한 시각 역순이며, 팔렸거나 내려간 매물도 상태를 달고 그대로 남는다. */
    @GetMapping("/api/users/me/favorites")
    public ApiResponse<CursorResponse<FavoriteSummaryResponse>> getMyFavorites(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.success(
                favoriteQueryService.getMyFavorites(user.userId(), cursor, size));
    }
}
