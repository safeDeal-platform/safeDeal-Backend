package com.safedeal.domain.listing.controller;

import com.safedeal.domain.listing.dto.ListingCreateRequest;
import com.safedeal.domain.listing.dto.ListingCreateResponse;
import com.safedeal.domain.listing.dto.ListingDetailResponse;
import com.safedeal.domain.listing.dto.ListingSummaryResponse;
import com.safedeal.domain.listing.service.ListingCommandService;
import com.safedeal.domain.listing.service.ListingQueryService;
import com.safedeal.global.response.ApiResponse;
import com.safedeal.global.response.CursorResponse;
import com.safedeal.global.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/listings")
@RequiredArgsConstructor
public class ListingController {

    private final ListingCommandService listingCommandService;
    private final ListingQueryService listingQueryService;

    /** 매물 등록. 인증 필요 — 판매자는 토큰에서 꺼내고 요청 본문으로 받지 않는다(위조 방지). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ListingCreateResponse> register(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ListingCreateRequest request) {
        return ApiResponse.success(listingCommandService.register(user.userId(), request));
    }

    /**
     * 매물 목록 — 비로그인 허용. 커서 페이지네이션이며 공개 상태만 나간다.
     *
     * @param categoryCode 대분류를 넘기면 하위 중분류 전체를 포함한다
     */
    @GetMapping
    public ApiResponse<CursorResponse<ListingSummaryResponse>> getListings(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) String regionSido,
            @RequestParam(required = false) String regionSigungu,
            @RequestParam(required = false) Integer minPrice,
            @RequestParam(required = false) Integer maxPrice) {
        return ApiResponse.success(listingQueryService.getListings(
                cursor, size, categoryCode, regionSido, regionSigungu, minPrice, maxPrice));
    }

    /** 매물 상세 — 비로그인 허용. 경로 변수는 내부 id가 아니라 public_id다. */
    @GetMapping("/{publicId}")
    public ApiResponse<ListingDetailResponse> getListing(@PathVariable String publicId) {
        return ApiResponse.success(listingQueryService.getListing(publicId));
    }
}
