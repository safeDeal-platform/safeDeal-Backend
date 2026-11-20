package com.safedeal.domain.auth.entity;

import com.safedeal.global.entity.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 소셜 계정 연결 (정책 AUTH-5).
 *
 * 재로그인 매칭은 이메일이 아니라 (provider, provider_user_id)로 한다 — 이메일은 공급자 쪽에서
 * 바뀔 수 있어서 그걸 키로 삼으면 같은 사람이 다른 계정으로 갈라진다.
 *
 * 유니크 두 개의 역할:
 * <ul>
 *   <li>(provider, provider_user_id) — 카카오 계정 하나는 우리 유저 한 명에게만 붙는다.</li>
 *   <li>(user_id, provider) — 한 유저에 카카오 계정이 두 개 붙지 않는다. user_id가 앞이라
 *       "이 유저의 연결 목록" 조회 인덱스도 겸한다.</li>
 * </ul>
 * 동시에 같은 연결이 두 번 들어와도 앱 검사가 아니라 이 제약이 마지막에 막는다.
 *
 * {@link CreatedEntity}를 상속하는 이유: 한 번 연결하면 값이 바뀌지 않는 기록이라 updated_at이
 * 필요 없다. 연결 시각은 created_at이 그대로 담는다.
 *
 * user_id를 @ManyToOne이 아니라 Long으로 들고 있는 이유: users는 유저 도메인 소유라 인증 도메인이
 * 그 엔티티 그래프까지 끌고 들어오면 경계가 흐려진다. 필요한 건 식별자 하나뿐이다.
 */
@Entity
@Table(
        name = "oauth_accounts",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_oauth_accounts_provider_user",
                        columnNames = {"provider", "provider_user_id"}),
                @UniqueConstraint(name = "uk_oauth_accounts_user_provider",
                        columnNames = {"user_id", "provider"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OauthAccount extends CreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private OauthProvider provider;

    /** 공급자가 발급한 고유 식별자(카카오 회원번호를 문자열로). 이메일과 달리 바뀌지 않는다. */
    @Column(name = "provider_user_id", nullable = false, length = 255, updatable = false)
    private String providerUserId;

    private OauthAccount(Long userId, OauthProvider provider, String providerUserId) {
        this.userId = userId;
        this.provider = provider;
        this.providerUserId = providerUserId;
    }

    public static OauthAccount link(Long userId, OauthProvider provider, String providerUserId) {
        return new OauthAccount(userId, provider, providerUserId);
    }
}
