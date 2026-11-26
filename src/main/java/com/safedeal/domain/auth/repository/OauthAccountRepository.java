package com.safedeal.domain.auth.repository;

import com.safedeal.domain.auth.entity.OauthAccount;
import com.safedeal.domain.auth.entity.OauthProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OauthAccountRepository extends JpaRepository<OauthAccount, Long> {

    /**
     * 재로그인 매칭 키다. 이메일이 아니라 (provider, providerUserId)로 찾는다 —
     * 공급자 쪽 이메일은 바뀔 수 있어 그걸 키로 삼으면 같은 사람이 다른 계정으로 갈라진다.
     */
    Optional<OauthAccount> findByProviderAndProviderUserId(OauthProvider provider, String providerUserId);

    boolean existsByUserIdAndProvider(Long userId, OauthProvider provider);
}
