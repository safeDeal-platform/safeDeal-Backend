-- rate limit: INCR과 최초 1회 EXPIRE를 하나의 스크립트로 묶어 원자적으로 처리한다.
-- KEYS[1] = 카운터 키 (예: rate:v1:{actorId}:{route}:{window})
-- ARGV[1] = TTL(초) — 윈도우 길이
--
-- INCR 직후 별도 EXPIRE 커맨드를 호출하면 그 사이에 다른 요청이 끼어들어
-- TTL이 계속 갱신되거나(레이스), 서버 장애로 EXPIRE가 누락되면 키가 영구히 남는 문제가 생긴다.
-- count == 1(이번 윈도우의 첫 요청)일 때만 EXPIRE를 실행해 이를 방지한다.
--
-- 반환값: 증가된 이후의 카운트. 호출 측(애플리케이션 코드)이 이 값을 rate limit 한도와 비교해
-- 허용/거부를 판단한다 — 한도 값은 이 스크립트에 두지 않는다(스크립트는 재사용 가능하게 단순 유지).
local current = redis.call('INCR', KEYS[1])
if tonumber(current) == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
return current
