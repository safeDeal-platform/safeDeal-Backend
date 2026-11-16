package com.safedeal.domain.report.repository;

import com.safedeal.domain.report.entity.Report;

/** 파생 쿼리로 표현할 수 없는 잠금 조회. 구현은 {@link ReportLockingRepositoryImpl}. */
public interface ReportLockingRepository {

    /**
     * 행 잠금(PESSIMISTIC_WRITE)을 걸고 DB 값으로 다시 읽은 같은 객체를 돌려준다. 같은 트랜잭션의 앞선 조회로
     * 영속성 컨텍스트에 올라간 옛 상태는 잠금 조회만으로는 덮이지 않아서, 다른 요청이 커밋한 최신 값을 보려면 refresh가 필요하다.
     */
    Report refreshForUpdate(Report report);
}
