package com.safedeal.domain.user.entity;

import com.safedeal.domain.trust.TrustScore;
import com.safedeal.global.entity.MutableEntity;
import com.safedeal.global.util.PublicId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 사용자 계정.
 *
 * <b>소유·범위 안내</b> — 이 엔티티의 최종 소유는 유저 도메인(중현)이다. 인증 착수 시점에
 * users 없이는 로그인을 한 줄도 만들 수 없어 인증 담당자가 <b>인증·신뢰도에 필요한 최소
 * 필드만</b> 먼저 만들었다. 아직 없는 필드는 유저 도메인 착수 시 추가한다:
 * phone / previous_nickname / nickname_changed_at.
 * (reported_flag는 신뢰도 TRS-3이 쓰는 값이라 인증 착수 시점에 함께 넣었다.)
 * (MVP 동안 스키마 진실은 엔티티이고 로컬은 ddl-auto=create-drop이라 필드 추가 비용은 없다.)
 *
 * 자세한 배경은 docs/V1-decisions.md 1장 참고.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends MutableEntity {

    // 내부 1000점 척도의 시작값(표시 50.0). 척도·delta의 소유는 신뢰도 도메인이고
    // 여기서는 "가입 시 어떤 값으로 만들지"만 정한다 (정책 TRS-1).
    public static final int INITIAL_TRUST_SCORE = TrustScore.INITIAL;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 외부 노출용 정본 식별자. 내부 PK는 API로 나가지 않는다.
    @Column(nullable = false, unique = true, length = PublicId.LENGTH, updatable = false)
    private String publicId;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    // OAuth 전용 계정은 설정할 비밀번호가 없어 null을 허용한다(정책 'OAuth-only 계정').
    // BCrypt 해시는 항상 60자다.
    @Column(length = 60)
    private String passwordHash;

    // UNIQUE는 사칭 방지 목적이다 - 거래 직후 닉네임을 갈아타 잠적하는 것을 막는다.
    @Column(nullable = false, unique = true, length = 20)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    // 가입 시 false. true로 만드는 인증 메일 흐름은 AUTH-6에서 붙인다.
    // MVP는 미인증도 로그인·열람이 가능하되 OAuth 자동연결 대상에서만 제외된다.
    @Column(nullable = false)
    private boolean emailVerified;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Column(nullable = false)
    private int trustScore;

    /**
     * 신뢰도 조건부 하한(floor) 보호가 풀렸는지 (정책 TRS-3).
     *
     * false면 점수가 300(표시 30.0) 밑으로 떨어지지 않고, true면 0까지 하락할 수 있다.
     * 신고 확정(제재) 이벤트를 받으면 true가 되고, 이후 정상 거래로 300을 재돌파하면
     * 다시 false로 돌아온다 — 즉 "이력이 있느냐"가 아니라 "지금 보호가 풀려 있느냐"다.
     * (ERD 컬럼명 reported_flag는 이력처럼 읽히지만 정책에 복원 규칙이 있어 실제 의미는 현재 상태다.)
     *
     * 값을 바꾸는 것은 신뢰도 도메인이고, 여기서는 보관만 한다.
     */
    @Column(nullable = false)
    private boolean reportedFlag;

    private Instant lastLoginAt;

    // 소프트 삭제. 조회 시 명시적 where로 걸러야 한다(공통 정책).
    // 삭제된 행이 email UNIQUE를 계속 점유하므로 동일 이메일 재가입이 자동으로 막힌다.
    private Instant deletedAt;

    private User(String email, String passwordHash, String nickname) {
        this.publicId = PublicId.generate();
        this.email = email;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.role = UserRole.USER;
        this.emailVerified = false;
        this.status = UserStatus.ACTIVE;
        this.trustScore = INITIAL_TRUST_SCORE;
        this.reportedFlag = false;
    }

    /** 이메일+비밀번호 로컬 가입. OAuth 가입은 AUTH-5에서 별도 팩토리로 추가한다. */
    public static User createLocal(String email, String encodedPassword, String nickname) {
        return new User(email, encodedPassword, nickname);
    }

    public void markLoggedIn(Instant at) {
        this.lastLoginAt = at;
    }

    /** 이메일 인증 완료 (AUTH-6). 이미 인증된 계정에 다시 불러도 무해하다. */
    public void verifyEmail() {
        this.emailVerified = true;
    }

    /** 로그인·토큰 발급을 허용해도 되는 상태인지. 제재·탈퇴 계정은 여기서 걸린다. */
    public boolean isLoginAllowed() {
        return status == UserStatus.ACTIVE && deletedAt == null;
    }

    /**
     * 비밀번호 변경·재설정 (AUTH-7).
     *
     * 호출하는 쪽이 refresh 전체 무효화를 반드시 함께 처리해야 한다 - 정책상 비밀번호가
     * 바뀌면 기존 세션이 전부 끊겨야 하는데, 엔티티는 Redis를 모르므로 여기서 할 수 없다.
     */
    public void changePassword(String encodedPassword) {
        this.passwordHash = encodedPassword;
    }

    /** OAuth 전용 계정은 대조할 비밀번호가 없다. */
    /**
     * 신뢰도 점수를 갈아끼운다 (정책 TRS-1·TRS-3).
     *
     * 증감폭 계산·하한 적용·어뷰징 판정은 전부 신뢰도 도메인이 하고 엔티티는 결과만 받는다 —
     * 여기서 계산하면 점수 규칙이 유저 엔티티로 새어 나가고, 규칙이 바뀔 때마다 남의 도메인
     * 파일을 고쳐야 한다.
     *
     * @param floorReleased 조건부 하한 보호가 풀린 상태인지(reported_flag). "이력이 있느냐"가
     *                      아니라 "지금 보호가 풀려 있느냐"다 — 정책에 복원 규칙이 있어
     *                      true에서 false로 돌아올 수 있다.
     */
    public void applyTrustScore(int newScore, boolean floorReleased) {
        this.trustScore = newScore;
        this.reportedFlag = floorReleased;
    }

    public boolean hasPassword() {
        return passwordHash != null;
    }
}
