package com.safedeal.domain.user.repository;

import com.safedeal.domain.user.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 엔티티와 같은 이유로 유저 도메인 패키지에 둔다(소유는 유저 도메인, 인증은 사용하는 쪽).
 * 단건 조회·존재 확인처럼 조건이 고정된 쿼리라 JPA Repository로 충분하다(동적 조건은 QueryDSL).
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /** 로그인·인증 조회. 소프트 삭제된 계정은 명시적으로 제외한다. */
    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    /**
     * id 조회에도 같은 규칙을 적용한다(공통 정책 '소프트삭제는 명시적 where'). findById를
     * 그대로 쓰면 탈퇴 직전 발급된 토큰으로 탈퇴한 계정의 비밀번호를 바꿀 수 있다.
     */
    Optional<User> findByIdAndDeletedAtIsNull(Long id);

    /**
     * 삭제된 행까지 포함해서 확인한다 — DB의 email UNIQUE도 삭제 행을 포함하므로, 사전
     * 검사 범위가 다르면 통과 후 INSERT에서 터진다.
     */
    boolean existsByEmail(String email);

    /**
     * 신뢰도 점수 갱신용 — 행을 잠그고 읽는다 (정책 TRS-2, 이벤트 수신 즉시 반영). read-modify-
     * write라 거래완료와 제재 확정이 동시에 오면 마지막 쓰기가 이겨 한쪽이 사라질 수 있다.
     * 누적 계산이라 조건부 UPDATE로 막을 수 없어(기대값을 모른다) 행 잠금으로 직렬화한다 —
     * 점수 변동은 드문 이벤트라 경합 비용은 거의 없다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id and u.deletedAt is null")
    Optional<User> findForTrustScoreUpdate(@Param("id") Long id);

    /**
     * 닉네임도 UNIQUE 제약과 같은 범위로 확인한다.
     * 탈퇴 계정의 닉네임을 풀어줄지(마스킹·해제)는 USR-3 소유라 여기서 정하지 않는다.
     */
    boolean existsByNickname(String nickname);
}
