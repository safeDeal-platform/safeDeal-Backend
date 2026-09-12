package com.safedeal.domain.trust.service;

import com.safedeal.domain.trust.TrustScore;
import com.safedeal.domain.trust.dto.TrustScoreChange;
import com.safedeal.domain.trust.entity.TrustReasonCode;
import com.safedeal.domain.trust.entity.TrustScoreLog;
import com.safedeal.domain.trust.repository.TrustScoreLogRepository;
import com.safedeal.domain.user.entity.User;
import com.safedeal.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * 신뢰도 점수 엔진 (정책 TRS-1 ~ TRS-6).
 *
 * 점수를 움직이는 모든 경로가 이 한 곳을 지난다 — Kafka로 들어오는 거래완료·제재 확정도,
 * 같은 프로세스에서 들어올 후기(REV-2)도 마찬가지다. 멱등·하한·어뷰징 규칙이 두 벌이 되면
 * 한쪽만 고쳐진 채로 남는다.
 *
 * <b>이 클래스가 하지 않는 것</b>: 계정 상태를 바꾸지 않는다(정책 TRS-6). 제재 확정으로
 * −300을 반영하더라도 {@code users.status}는 그대로 두고, SUSPENDED 전이는 운영자가 관리자
 * 도구에서 수동으로 한다. 자동 상태 전이는 오탐 시 정상 유저를 부당 차단하는 대가가 커서
 * 데이터가 쌓인 뒤에 판단하기로 한 사항이다(Phase 3).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrustScoreService {

    /** 동일 상대와의 거래완료 가산 한도 (정책 TRS-5). 운영 데이터로 튜닝할 값이다. */
    private static final long SAME_COUNTERPARTY_MONTHLY_LIMIT = 3;

    private final TrustScoreLogRepository logRepository;
    private final UserRepository userRepository;

    /**
     * 점수 변동을 반영한다. 같은 사실이 여러 번 들어와도 한 번만 반영된다.
     *
     * <b>순서가 중요하다</b>: 유저 행을 먼저 잠그고 그다음에 멱등 검사를 한다. 반대로 하면
     * 같은 이벤트 두 개가 나란히 "아직 없다"를 읽고 둘 다 통과한 뒤 INSERT 단계에서 UNIQUE
     * 위반으로 터진다 — 예외를 삼키면 트랜잭션이 이미 롤백 표시된 뒤라 뒷수습이 지저분해진다.
     * 잠금을 먼저 잡으면 두 번째 요청은 첫 번째가 남긴 로그를 보고 조용히 빠진다.
     */
    @Transactional
    public void apply(TrustScoreChange change) {
        validate(change);

        User user = userRepository.findForTrustScoreUpdate(change.userId()).orElse(null);
        if (user == null) {
            // 탈퇴했거나 없는 계정. 점수를 매길 대상이 없으니 조용히 버린다 — 예외로 올리면
            // 재시도를 거쳐 DLT로 가는데, 되살아날 일이 없는 메시지라 쌓이기만 한다.
            log.warn("신뢰도 반영 대상 없음 - 건너뛴다. userId={} reason={}",
                    change.userId(), change.reasonCode());
            return;
        }

        if (alreadyApplied(change)) {
            log.debug("신뢰도 중복 수신 - 무시한다. eventId={}", change.eventId());
            return;
        }

        int delta = resolveDelta(change);
        boolean floorReleased = user.isReportedFlag() || change.reasonCode().releasesFloor();
        int newScore = TrustScore.clamp(user.getTrustScore() + delta, floorReleased);
        boolean floorAfter = resolveFloorState(floorReleased, delta, newScore);

        logRepository.save(TrustScoreLog.record(
                change.userId(), change.reasonCode(), change.refType(), change.refId(),
                // 실제로 반영된 폭을 남긴다 — 하한·상한에 막혀 delta 전부가 들어가지 않을 수 있다.
                newScore - user.getTrustScore(),
                newScore, change.counterpartyId(), change.eventId()));

        user.applyTrustScore(newScore, floorAfter);
    }

    private void validate(TrustScoreChange change) {
        // 거래완료에 상대가 없으면 어뷰징 판정 자체가 불가능하다. 계약(TXN-7)이 counterpartyId를
        // 필수로 못 박았으므로, 없는 채로 들어오면 조용히 넘기지 않고 터뜨려 DLT로 보낸다 —
        // 여기서 넘어가면 셀프 거래 반복이 한도 없이 가산된다.
        if (change.reasonCode().isAbuseCapped() && change.counterpartyId() == null) {
            throw new IllegalArgumentException(
                    "거래완료 이벤트에 counterpartyId가 없습니다 - eventId=" + change.eventId());
        }
    }

    private boolean alreadyApplied(TrustScoreChange change) {
        return logRepository.existsByEventId(change.eventId())
                || logRepository.existsByUserIdAndReasonCodeAndRefTypeAndRefId(
                        change.userId(), change.reasonCode(), change.refType(), change.refId());
    }

    /**
     * 사유별 delta에 어뷰징 한도를 적용한다 (정책 TRS-2 · TRS-5).
     *
     * 한도를 넘으면 0점으로 깎되 <b>기록은 남긴다</b>. 아예 버리면 "왜 이 거래는 점수가 안
     * 올랐나"를 나중에 설명할 수 없고, 어뷰징 판정이 실제로 동작했는지도 확인할 수 없다.
     */
    private int resolveDelta(TrustScoreChange change) {
        TrustReasonCode reason = change.reasonCode();
        if (!reason.isAbuseCapped()) {
            return reason.delta();
        }
        long granted = logRepository.countGrantedSince(
                change.userId(), change.counterpartyId(), reason, currentMonthStart());
        if (granted >= SAME_COUNTERPARTY_MONTHLY_LIMIT) {
            log.info("동일 상대 월 한도 초과 - 가산하지 않는다. userId={} counterpartyId={} 이번달={}",
                    change.userId(), change.counterpartyId(), granted);
            return 0;
        }
        return reason.delta();
    }

    /**
     * 반영 후의 하한 보호 상태 (정책 TRS-3).
     *
     * 복원 조건을 "점수가 300 이상"이 아니라 "<b>가산</b>으로 300 이상"으로 둔 이유: 점수가
     * 높던 유저가 제재를 받아 −300을 맞아도 여전히 300을 넘을 수 있는데, 그 자리에서 바로
     * 보호가 복원되면 제재가 하한을 해제한 의미가 사라진다. 정책 문장도 "이후 정상 거래로
     * 재돌파 시 복원"이라 감점 자체는 복원 계기가 아니다.
     */
    private boolean resolveFloorState(boolean floorReleased, int delta, int newScore) {
        if (!floorReleased) {
            return false;
        }
        boolean recovered = delta > 0 && newScore >= TrustScore.PROTECTED_FLOOR;
        return !recovered;
    }

    /**
     * 이번 달의 시작 시각 (UTC).
     *
     * DB·JVM 모두 UTC로 저장하는 것이 공통 원칙이라 월 경계도 UTC로 자른다. KST 기준으로
     * 자르면 매월 1일 0~9시에 저장값과 판정 기준이 어긋난다.
     */
    private Instant currentMonthStart() {
        return YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    }
}
