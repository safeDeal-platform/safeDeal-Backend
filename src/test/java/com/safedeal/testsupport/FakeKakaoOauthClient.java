package com.safedeal.testsupport;

import com.safedeal.domain.auth.client.KakaoOauthClient;
import com.safedeal.domain.auth.client.KakaoOauthProperties;
import com.safedeal.domain.auth.client.KakaoProfile;
import com.safedeal.domain.auth.exception.AuthErrorCode;
import com.safedeal.global.exception.BusinessException;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 카카오 서버 대신 미리 정한 사용자 정보를 돌려준다. 인가 코드(code)마다 응답을 등록해 두고 쓴다.
 * 등록 안 된 코드는 카카오 호출 실패(AUTH012)로 본다.
 *
 * {@code @Primary}로 실제 클라이언트를 밀어내는 방식은 RecordingMailSender와 같다 — 테스트 소스에만
 * 있는 빈이라 운영 컨텍스트에는 올라올 수 없다.
 */
@Primary
@Component
public class FakeKakaoOauthClient extends KakaoOauthClient {

    private final Map<String, KakaoProfile> profiles = new ConcurrentHashMap<>();
    private final AtomicInteger fetchCount = new AtomicInteger();

    public FakeKakaoOauthClient(KakaoOauthProperties properties) {
        super(properties);
    }

    @Override
    public KakaoProfile fetchProfile(String code) {
        fetchCount.incrementAndGet();
        KakaoProfile profile = profiles.get(code);
        if (profile == null) {
            throw new BusinessException(AuthErrorCode.OAUTH_PROVIDER_FAILED);
        }
        return profile;
    }

    public void register(String code, KakaoProfile profile) {
        profiles.put(code, profile);
    }

    public int fetchCount() {
        return fetchCount.get();
    }

    public void reset() {
        profiles.clear();
        fetchCount.set(0);
    }

    /** 카카오가 검증한 이메일을 가진 사용자. */
    public static KakaoProfile verified(long id, String email) {
        return new KakaoProfile(id, new KakaoProfile.Account(false, true, true, email));
    }

    public static KakaoProfile account(long id, Boolean needsAgreement, Boolean valid, Boolean verified, String email) {
        return new KakaoProfile(id, new KakaoProfile.Account(needsAgreement, valid, verified, email));
    }
}
