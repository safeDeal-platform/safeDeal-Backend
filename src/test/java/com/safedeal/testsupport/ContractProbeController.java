package com.safedeal.testsupport;

import com.safedeal.global.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 계약 테스트 전용 컨트롤러 (test 소스에만 존재 — 운영 클래스패스에 없다).
 *
 * 도메인 컨트롤러가 아직 없어서, 전역 예외 처리·principal 계약·검증 응답이 "실제 요청을
 * 통과했을 때" 어떤 모양인지 고정할 대상이 없다. 이 컨트롤러가 그 대상 역할만 한다.
 */
@RestController
public class ContractProbeController {

    /** principal 계약 확인용 — 컨트롤러가 AuthenticatedUser로 사용자를 꺼내는 표준 방식. */
    @GetMapping("/__test/me")
    public Map<String, Object> me(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("userId", user.userId(), "role", user.role());
    }

    /** 필수 헤더 누락 → 400 확인용. */
    @GetMapping("/__test/need-header")
    public String needHeader(@RequestHeader("X-Test-Required") String value) {
        return value;
    }

    /** @Valid 실패 → 400 + fieldErrors 확인용. */
    @PostMapping("/__test/validate")
    public ValidatedBody validate(@Valid @RequestBody ValidatedBody body) {
        return body;
    }

    public record ValidatedBody(
            @NotBlank(message = "제목은 비어 있을 수 없습니다.") String title,
            @Min(value = 1000, message = "가격은 1,000원 이상이어야 합니다.") int price
    ) {
    }
}
