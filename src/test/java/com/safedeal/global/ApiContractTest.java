package com.safedeal.global;

import com.safedeal.testsupport.IntegrationTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공통 베이스의 대외 계약을 고정하는 회귀 테스트.
 *
 * 여기서 검증하는 것들(응답 형식·에러 코드·보안 경로·requestId·principal)은 프론트와
 * 세 도메인 담당자가 전부 의존하는 계약이다. 이 테스트가 깨진다 = 계약이 바뀌었다는 뜻이므로,
 * 고치기 전에 반드시 팀에 공유할 것.
 *
 * 컨테이너·프로파일 설정은 IntegrationTestSupport에 있다.
 */
@AutoConfigureMockMvc
class ApiContractTest extends IntegrationTestSupport {

    private static final String DEV_USER = "X-Dev-User-Id";


    @Autowired
    MockMvc mockMvc;

    // ── 보안 경로 ──────────────────────────────────────────────

    @Test
    @DisplayName("health는 인증 없이 열려 있다")
    void health_isPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("prometheus는 인증돼 있어도 노출되지 않는다")
    void prometheus_isNotExposed() throws Exception {
        mockMvc.perform(get("/actuator/prometheus").header(DEV_USER, "1"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("보호 경로는 인증 없이 401 + C005 + requestId")
    void protectedPath_withoutAuth_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("C005"))
                .andExpect(jsonPath("$.error.requestId", not(emptyString())));
    }

    @Test
    @DisplayName("매물 목록은 화이트리스트 — 인증 없이 보안을 통과한다(컨트롤러가 없어 404)")
    void listings_isWhitelisted() throws Exception {
        mockMvc.perform(get("/api/v1/listings"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("C002"));
    }

    // ── requestId ─────────────────────────────────────────────

    @Test
    @DisplayName("인바운드 X-Request-Id는 응답 헤더와 에러 본문에 그대로 이어진다")
    void inboundRequestId_isPropagated() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").header("X-Request-Id", "trace-abc-123"))
                .andExpect(header().string("X-Request-Id", "trace-abc-123"))
                .andExpect(jsonPath("$.error.requestId").value("trace-abc-123"));
    }

    @Test
    @DisplayName("형식이 틀린 X-Request-Id는 무시하고 새로 발급한다 (로그 인젝션 방어)")
    void invalidInboundRequestId_isReplaced() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").header("X-Request-Id", "bad value!!<script>"))
                .andExpect(header().string("X-Request-Id", not("bad value!!<script>")));
    }

    // ── principal 계약 ─────────────────────────────────────────

    @Test
    @DisplayName("컨트롤러는 @AuthenticationPrincipal AuthenticatedUser로 사용자를 꺼낸다")
    void principalContract_isAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/__test/me").header(DEV_USER, "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(42))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    @DisplayName("개발 인증으로 ADMIN을 요청해도 USER로 강등된다")
    void devAuth_cannotEscalateToAdmin() throws Exception {
        mockMvc.perform(get("/__test/me").header(DEV_USER, "42").header("X-Dev-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    @DisplayName("숫자가 아닌 개발 인증 ID는 인증되지 않는다")
    void devAuth_nonNumericId_isNotAuthenticated() throws Exception {
        mockMvc.perform(get("/__test/me").header(DEV_USER, "not-a-number"))
                .andExpect(status().isUnauthorized());
    }

    // ── 전역 예외 응답 형식 ─────────────────────────────────────

    @Test
    @DisplayName("필수 헤더 누락은 500이 아니라 400 + C001")
    void missingRequiredHeader_isBadRequest() throws Exception {
        mockMvc.perform(get("/__test/need-header").header(DEV_USER, "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C001"));
    }

    @Test
    @DisplayName("@Valid 실패는 400 + fieldErrors 목록")
    void validationFailure_returnsFieldErrors() throws Exception {
        mockMvc.perform(post("/__test/validate")
                        .header(DEV_USER, "1")
                        .contentType("application/json")
                        .content("{\"title\":\"\",\"price\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C001"))
                .andExpect(jsonPath("$.error.fieldErrors.length()").value(2));
    }

    @Test
    @DisplayName("본문이 JSON이 아니면 400")
    void malformedJson_isBadRequest() throws Exception {
        mockMvc.perform(post("/__test/validate")
                        .header(DEV_USER, "1")
                        .contentType("application/json")
                        .content("not-json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("없는 경로는 404 + C002 (인증된 상태)")
    void unknownPath_isNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/definitely-not-here").header(DEV_USER, "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("C002"));
    }

    @Test
    @DisplayName("허용되지 않은 메서드는 405 + C003")
    void methodNotAllowed_returns405() throws Exception {
        mockMvc.perform(post("/__test/me").header(DEV_USER, "1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("C003"));
    }
}
