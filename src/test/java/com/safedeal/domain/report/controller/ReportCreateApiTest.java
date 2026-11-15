package com.safedeal.domain.report.controller;

import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.report.ReportCreateIntegrationSupport;
import com.safedeal.domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/reports를 시큐리티 필터·컨트롤러·서비스·DB까지 끝까지 통과시킨다. 인증 없음(401)은
 * 필터가 있어야만 확인되므로 여기서 본다. 인증은 로컬 개발 헤더({@code X-Dev-User-Id})로 채운다
 * ({@code ApiContractTest}와 같은 방식).
 */
@AutoConfigureMockMvc
class ReportCreateApiTest extends ReportCreateIntegrationSupport {

    private static final String DEV_USER = "X-Dev-User-Id";

    @Autowired MockMvc mockMvc;

    private User reporter;
    private Listing listing;

    @BeforeEach
    void setUp() {
        reporter = user();
        listing = activeListing(user());
    }

    private MockHttpServletRequestBuilder report(String body) {
        return post("/api/reports").contentType("application/json").content(body);
    }

    private ResultActions reportAs(User user, String body) throws Exception {
        return mockMvc.perform(report(body).header(DEV_USER, String.valueOf(user.getId())));
    }

    private String fraudBody(String extra) {
        return "{\"targetType\":\"LISTING\",\"targetId\":\"" + listing.getPublicId()
                + "\",\"reasonCode\":\"FRAUD\"" + extra + "}";
    }

    @Test
    @DisplayName("RPT-C28 인증 없이 신고하면 401(C005)이고 저장되지 않는다")
    void unauthenticatedIsRejected() throws Exception {
        mockMvc.perform(report(fraudBody("")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("C005"));

        assertThat(reportRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("RPT-C01 RPT-C30 정상 신고는 201이고 data는 reportId·status·receivedAt 세 필드뿐이다")
    void createsReport() throws Exception {
        reportAs(reporter, fraudBody(",\"detail\":\"허위 매물\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data.status").value("RECEIVED"));

        assertThat(reportRepository.findAll()).singleElement()
                .satisfies(r -> assertThat(r.getReporterId()).isEqualTo(reporter.getId()));
    }

    @Test
    @DisplayName("RPT-C15 매물 신고에 evidenceMessageId를 넣으면 400(C001) (Q5)")
    void listingWithEvidenceIsBadRequest() throws Exception {
        reportAs(reporter, fraudBody(",\"evidenceMessageId\":1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C001"));

        assertThat(reportRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("RPT-C29 정지된 신고자는 403(C006) (Q7)")
    void suspendedReporterIsForbidden() throws Exception {
        changeStatus(reporter, "SUSPENDED");

        reportAs(reporter, fraudBody(""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("C006"));

        assertThat(reportRepository.findAll()).isEmpty();
    }
}
