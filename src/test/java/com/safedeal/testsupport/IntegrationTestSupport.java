package com.safedeal.testsupport;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;

/**
 * DB·Redis에 실제로 붙어야 하는 테스트의 공통 부모.
 *
 * <p>컨테이너를 {@code static} 초기화 블록에서 직접 띄우고 <b>끝까지 내리지 않는다.</b> 전체
 * 테스트를 한 번에 돌릴 때 클래스 수만큼 컨테이너가 뜨는 것을 막기 위해서다.
 *
 * <p><b>{@code @Testcontainers}/{@code @Container}를 쓰지 않는 이유</b>(중요 — 편해 보인다고
 * 다시 붙이지 말 것): 그 조합은 컨테이너 수명을 <i>테스트 클래스 단위</i>로 관리한다. 클래스가
 * 끝나면 컨테이너를 내리고 다음 클래스에서 다시 띄우는데, 그때 매핑 포트가 새로 잡힌다. 반면
 * 스프링 컨텍스트는 클래스 사이에 캐시되어 살아남으므로, 캐시된 커넥션 풀이 사라진 옛 포트를
 * 계속 붙잡고 연결 거부가 난다. 클래스마다 컨테이너를 따로 선언할 때는 드러나지 않다가 공유로
 * 바꾸는 순간 터진다.
 *
 * <p>JVM이 끝나면 Testcontainers의 Ryuk 컨테이너가 정리하므로 직접 stop하지 않아도 남지 않는다.
 *
 * <p>{@code @ServiceConnection}이 컨테이너 접속 정보를 스프링에 직접 넘기므로
 * {@code @DynamicPropertySource}로 url·계정·포트를 손수 주입할 필요가 없다.
 *
 * <p>프로파일이 (local, test)인 이유: local은 개발 인증 필터를 켜기 위해(아직 JWT가 없어
 * 인증된 상태를 만들 다른 방법이 없다), test는 logback의 Loki 전송을 끄기 위해.
 *
 * <p><b>상속 대상은 DB가 필요한 테스트뿐이다.</b> 엔티티 단위 테스트, Mockito 서비스 테스트,
 * standaloneSetup MockMvc 테스트는 컨텍스트가 필요 없으므로 상속하지 않는다 — 상속시키면
 * 수 초짜리 테스트가 컨테이너 기동을 기다리게 된다.
 *
 * <p><b>주의:</b> 하위 클래스에 {@code @Transactional}을 붙이면 각 테스트가 롤백된다. 다만
 * 기동 시점에 도는 코드(ApplicationRunner 등)나 별도 스레드를 쓰는 동시성 테스트는 테스트
 * 트랜잭션 밖에서 실행되므로 롤백이 통하지 않는다 — 그런 테스트는 {@code @Transactional}을
 * 붙이지 말고 {@code @AfterEach}에서 직접 정리한다.
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
public abstract class IntegrationTestSupport {

    // 새 패키지(org.testcontainers.mysql)의 MySQLContainer는 자기타입 제네릭이 없다.
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.36");

    /**
     * Redis가 없으면 actuator 종합 health가 DOWN(503)이 된다 — health 지표에 Redis가 포함되기
     * 때문. "health는 공개다"라는 보안 계약을 검증하려면 의존성도 살아 있어야 한다.
     *
     * <p>{@code GenericContainer}는 이미지에서 서비스 종류를 유추할 수 없어 {@code name}으로
     * 알려준다.
     */
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

    static {
        MYSQL.start();
        REDIS.start();
    }
}
