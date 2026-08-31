package com.safedeal.domain.auth.repository;

import com.safedeal.domain.auth.entity.EmailVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {

    /** 링크로 들어온 원문을 해시로 바꿔 조회한다 — 원문은 저장하지 않는다. */
    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);
}
