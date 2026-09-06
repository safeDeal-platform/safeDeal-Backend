# 운영 배포용 이미지 (ECS Fargate 대상).
#
# 이 Dockerfile은 이미 빌드된 JAR을 받는다 — 이미지 안에서 gradle을 다시 돌리지 않는다.
# CI가 ./gradlew build로 테스트까지 통과시킨 바로 그 JAR을 이미지에 넣어야 "검증한 산출물"과
# "배포한 산출물"이 같아진다. 이미지 안에서 재빌드하면 둘이 달라질 수 있고, Testcontainers
# 테스트가 빌드 컨테이너 안에서 Docker 데몬을 못 찾아 깨지는 문제도 생긴다.
#
# 빌드:  ./gradlew build && docker build -t safedeal-backend .
#
# 실행 시 필수 환경변수는 RequiredPropertyGuard 참고 (prod 프로파일이 기동 시점에 검증한다).

# ── 1단계: layered JAR 해체 ─────────────────────────────────────────────
# Boot 3.3부터 layertools는 deprecated, Boot 4는 -Djarmode=tools를 쓴다.
# --layers를 붙여야 의존성/로더/스냅샷/애플리케이션이 분리돼 Docker 레이어 캐시가 산다
# — 코드만 바뀌면 마지막 레이어만 다시 만들어진다.
FROM eclipse-temurin:21-jre-jammy AS extract
WORKDIR /work
# 이름이 build.gradle에서 application.jar로 고정돼 있다 — 글롭을 쓰면 잔재 jar가 섞인다.
COPY build/libs/application.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ── 2단계: 런타임 ──────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# 변경 빈도가 낮은 순서로 복사해야 캐시 적중률이 높다.
COPY --from=extract /work/extracted/dependencies/ ./
COPY --from=extract /work/extracted/spring-boot-loader/ ./
COPY --from=extract /work/extracted/snapshot-dependencies/ ./
COPY --from=extract /work/extracted/application/ ./

# 루트로 돌리면 컨테이너 탈출 시 피해가 커진다.
RUN useradd --system --uid 1001 appuser
USER appuser

# 힙을 고정값으로 주면 태스크 메모리를 바꿀 때마다 이미지를 다시 만들어야 한다.
# 컨테이너 메모리의 75%를 쓰게 해서 태스크 정의만 바꾸면 되게 한다.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

# exec 형식이어야 Java가 PID 1로 떠서 ECS의 SIGTERM을 직접 받는다 —
# 셸을 거치면 graceful shutdown이 동작하지 않을 수 있다.
ENTRYPOINT ["java", "-jar", "application.jar"]
