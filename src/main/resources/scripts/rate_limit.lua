-- rate limit: INCR과 최초 1회 EXPIRE를 하나의 스크립트로 묶어 원자적으로 처리한다.
-- KEYS[1] = 카운터 키 (예: rate:v1:{actorId}:{route}:{window})
-- ARGV[1] = TTL(초) — 윈도우 길이
--
-- INCR 직후 별도 EXPIRE 커맨드를 호출하면 그 사이에 다른 요청이 끼어들어
-- TTL이 계속 갱신되거나(레이스), 서버 장애로 EXPIRE가 누락되면 키가 영구히 남는 문제가 생긴다.
-- count == 1(이번 윈도우의 첫 요청)일 때만 EXPIRE를 실행해 이를 방지한다.
--
-- 다만 count == 1 조건만으로는 부족하다. EXPIRE가 한 번이라도 걸리지 않은 키(잘못된 TTL
-- 인자로 거부됐거나 과거 버전이 남긴 키)는 카운트가 2 이상이라 이 분기에 영영 들어오지
-- 못하고, TTL 없는 카운터가 한도를 넘은 채 영구히 남아 해당 주체가 영구 차단된다.
-- 그래서 TTL 부재(-1)도 복구 조건에 포함하고, 애초에 잘못된 TTL이 들어오지 못하게 막는다.
--
-- 반환값: 증가된 이후의 카운트. 호출 측(애플리케이션 코드)이 이 값을 rate limit 한도와 비교해
-- 허용/거부를 판단한다 — 한도 값은 이 스크립트에 두지 않는다(스크립트는 재사용 가능하게 단순 유지).
-- 상한(MAX_TTL)이 필요한 이유: 상한이 없으면 거대한 값이 정수 검증을 통과한 뒤 INCR은
-- 성공하고 EXPIRE만 범위 초과로 실패한다. Lua 스크립트는 원자적이지만 뒤 명령의 실패가
-- 앞의 쓰기를 되돌리지는 않으므로, 결국 TTL 없는 카운터가 남아 이 스크립트가 막으려던
-- 영구 차단이 그대로 재현된다. 2^53을 넘는 값이 부동소수점으로 반올림돼 검증을 우회하는
-- 문제도 같이 막힌다. rate limit 윈도우는 최대가 분 단위라 하루면 충분히 넉넉하다.
local MAX_TTL = 86400

local ttl = tonumber(ARGV[1])
if ttl == nil or ttl < 1 or ttl > MAX_TTL or ttl % 1 ~= 0 then
    return redis.error_reply('rate_limit: TTL(ARGV[1]) must be an integer in [1, ' .. MAX_TTL .. ']')
end

local current = redis.call('INCR', KEYS[1])
if current == 1 or redis.call('TTL', KEYS[1]) < 0 then
    redis.call('EXPIRE', KEYS[1], ttl)
end
return current
