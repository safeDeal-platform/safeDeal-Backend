package com.safedeal.domain.user.repository;

import com.safedeal.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 엔티티와 같은 이유로 유저 도메인 패키지에 둔다(소유는 유저 도메인, 인증은 사용하는 쪽).
 *
 * 단건 조회·존재 확인처럼 조건이 고정된 쿼리라 JPA Repository로 충분하다
 * (동적 조건이 붙는 조회는 QueryDSL - 데이터 접근 전략 참고).
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /** 로그인·인증 조회. 소프트 삭제된 계정은 명시적으로 제외한다. */
    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    /**
     * id 조회에도 같은 규칙을 적용한다(공통 정책 '소프트삭제는 명시적 where').
     *
     * findById를 그대로 쓰면 탈퇴 직전에 발급된 재설정 링크나 access 토큰(최대 30분 생존)으로
     * 탈퇴한 계정의 비밀번호를 바꾸거나 메일을 다시 받을 수 있다.
     */
    Optional<User> findByIdAndDeletedAtIsNull(Long id);

    /**
     * 삭제된 행까지 포함해서 확인한다 - 동일 이메일 재가입 1차 불허가 정책이고,
     * DB의 email UNIQUE도 삭제 행을 포함하므로 사전 검사도 같은 범위여야 한다.
     * (범위가 다르면 사전 검사는 통과하고 INSERT에서 터진다.)
     */
    boolean existsByEmail(String email);

    /**
     * 닉네임도 UNIQUE 제약과 같은 범위로 확인한다.
     * 탈퇴 계정의 닉네임을 풀어줄지(마스킹·해제)는 USR-3 소유라 여기서 정하지 않는다.
     */
    boolean existsByNickname(String nickname);
}
