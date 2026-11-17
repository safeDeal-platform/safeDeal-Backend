package com.safedeal.domain.user.dto;

import com.safedeal.domain.trust.TrustScore;
import com.safedeal.domain.user.entity.User;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 내 정보 조회 응답(GET /api/users/me). 필드는 이 6개만 내보낸다(ME-18 화이트리스트) — 내부 id·비밀번호 해시·
 * reportedFlag·로그인 시각·계정 상태·역할은 넣지 않는다(Q4·Q7). 엔티티를 직접 직렬화하지 않으므로 엔티티에 필드가
 * 늘어도 응답은 바뀌지 않는다(ME-19).
 *
 * @param trustScore 화면 표시값(내부 0~1000점 → 소수 1자리, TrustScore.display 한 곳에서만 변환, Q5)
 * @param email      본인 조회라 원문을 보여 준다(Q1). 로그·예외 메시지에는 넣지 않는다.
 */
public record MyProfileResponse(String publicId,
                                String email,
                                String nickname,
                                boolean emailVerified,
                                BigDecimal trustScore,
                                Instant createdAt) {

    public static MyProfileResponse from(User user) {
        return new MyProfileResponse(
                user.getPublicId(),
                user.getEmail(),
                user.getNickname(),
                user.isEmailVerified(),
                TrustScore.display(user.getTrustScore()),
                user.getCreatedAt());
    }
}
