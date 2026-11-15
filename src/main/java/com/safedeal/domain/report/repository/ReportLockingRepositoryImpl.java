package com.safedeal.domain.report.repository;

import com.safedeal.domain.report.entity.Report;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

/**
 * {@link ReportLockingRepository} 구현. {@code EntityManager.refresh}에 잠금 모드를 함께 넘겨
 * {@code SELECT ... FOR UPDATE} 한 번으로 잠금과 최신 값 반영을 같이 한다.
 */
public class ReportLockingRepositoryImpl implements ReportLockingRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Report refreshForUpdate(Report report) {
        entityManager.refresh(report, LockModeType.PESSIMISTIC_WRITE);
        return report;
    }
}
