package com.safedeal.domain.user.controller;

import com.safedeal.domain.user.dto.MyProfileResponse;
import com.safedeal.domain.user.service.UserQueryService;
import com.safedeal.global.response.ApiResponse;
import com.safedeal.global.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserQueryService userQueryService;

    /** 내 정보. 항상 토큰의 본인만 — 쿼리·헤더로 다른 식별자를 받지 않는다(ME-16). */
    @GetMapping("/api/users/me")
    public ApiResponse<MyProfileResponse> getMe(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(userQueryService.getMyProfile(user.userId()));
    }
}
