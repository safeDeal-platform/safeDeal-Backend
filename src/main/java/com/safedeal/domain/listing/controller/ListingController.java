package com.safedeal.domain.listing.controller;

import com.safedeal.domain.listing.dto.ListingCreateRequest;
import com.safedeal.domain.listing.dto.ListingCreateResponse;
import com.safedeal.domain.listing.dto.ListingDetailResponse;
import com.safedeal.domain.listing.dto.ListingStatusChangeRequest;
import com.safedeal.domain.listing.dto.ListingStatusChangeResponse;
import com.safedeal.domain.listing.dto.ListingUpdateRequest;
import com.safedeal.domain.listing.dto.ListingUpdateResponse;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    /**
     * 매물 상세 — 비로그인 허용. 경로 변수는 내부 id가 아니라 public_id다.
     *
     * <p>조회수는 상세를 찾은 뒤에 올린다. 먼저 올리면 없는 매물·비공개 매물을 찔러보는
     * 요청으로도 숫자가 오른다. 응답의 조회수는 이번 조회가 반영되기 직전 값이다.
     *
     * <p>{@code user}는 비로그인이면 null이다 — 이 경로는 화이트리스트라 인증이 없어도 통과한다.
     */
    @GetMapping("/{publicId}")
    public ApiResponse<ListingDetailResponse> getListing(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String publicId) {
        ListingDetailResponse detail = listingQueryService.getListing(publicId);
        listingCommandService.increaseViewCount(
                publicId,
                user == null ? null : user.userId(),
                user != null && ADMIN_ROLE.equals(user.role()));
        return ApiResponse.success(detail);
    }

    /** 관리자 조회는 조회수에 세지 않는다(정책). */
    private static final String ADMIN_ROLE = "ADMIN";

    /** 매물 수정 — 판매자 본인만. 낙관적 락 충돌 시 409를 돌려주므로 재조회 후 재시도한다. */
    @PatchMapping("/{publicId}")
    public ApiResponse<ListingUpdateResponse> update(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String publicId,
            @Valid @RequestBody ListingUpdateRequest request) {
        return ApiResponse.success(listingCommandService.update(user.userId(), publicId, request));
    }

    /** 매물 삭제 — 소프트 삭제. 제재된 매물은 지울 수 없다. */
    @DeleteMapping("/{publicId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String publicId) {
        listingCommandService.delete(user.userId(), publicId);
        return ApiResponse.success();
    }

    /** 판매자 수동 상태 전이 — 판매완료 처리와 24시간 내 되돌리기. */
    @PatchMapping("/{publicId}/status")
    public ApiResponse<ListingStatusChangeResponse> changeStatus(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String publicId,
            @Valid @RequestBody ListingStatusChangeRequest request) {
        return ApiResponse.success(
                listingCommandService.changeStatus(user.userId(), publicId, request));
    }
}
