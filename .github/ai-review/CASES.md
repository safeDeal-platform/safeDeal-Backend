# PR 자동 Claude 리뷰(ai-review) — 승인된 케이스와 결정

- 승인: 재민(CheatIsKey), 2026-11-14
- 요구사항: PR을 열거나 열린 PR에 커밋하면 Claude가 자동으로 리뷰하고, PR에 댓글을 달고, PASS 또는 BLOCK 체크를 남긴다.
- 인증: 재민 한 사람의 Claude 구독 토큰(`claude setup-token` → 레포 시크릿 `CLAUDE_CODE_OAUTH_TOKEN`). 약관상 팀원 PR까지 한 사람 구독으로 리뷰하는 것이 허용되는지 문서에 답이 없다는 설명을 듣고 재민이 선택함.

## 케이스

| ID | 관점 | 상황 | 기대 결과 | 테스트 종류 |
|---|---|---|---|---|
| CIR-01 | 정상 | 레포 브랜치에서 **어느 브랜치로든** PR을 연다(opened) | 리뷰가 돌고 요약 댓글과 `ai-review` 체크가 head SHA에 붙는다 | 시범 PR |
| CIR-02 | 정상 | 열린 PR에 커밋을 push(synchronize) | 새 head SHA로 다시 리뷰, 체크도 그 SHA에 | 시범 PR |
| CIR-03 | 정상 | 닫았던 PR을 다시 연다(reopened) | 다시 리뷰 | 시범 PR |
| CIR-04 | 정상 | 초안을 리뷰 준비로 바꿈(ready_for_review) | 다시 리뷰 | 시범 PR |
| CIR-05 | 정상 | P1 0건 + 판정 표식 PASS | 체크 성공. 댓글에 "검토 범위 / 지적 수 / PASS"를 함께 적는다. 0건만 보고 PASS를 추정하지 않는다 | 판정 파서 단위 + 시범 |
| CIR-06 | 정상 | P1 1건 이상 | BLOCK, 체크 실패, 댓글에 위치 | 판정 파서 단위 + 시범 |
| CIR-07 | 예외 | **초안(Draft) PR** | 건너뛰지 않고 일반 PR과 똑같이 리뷰·판정한다(팀은 Draft를 쓰지 않음, Q3) | 정적 검사 + 시범 |
| CIR-08 | 예외 | 문서(md)만 바뀐 PR | 리뷰한다(건너뛰면 통과처럼 보이는 빈틈, Q8) | 시범 |
| CIR-09 | 예외 | 봇·dependabot PR | 허용 작성자가 아니므로 CIR-39와 같이 처리(Claude 호출 없음, 체크 실패) | 정적 검사 + 단위 |
| CIR-10 | 예외 | 매우 큰 PR | 일부만 보고 PASS 금지. "판정 불가: 범위 초과". 기준값은 시범에서 정함 | 파서 단위 + 시범 |
| CIR-11 | 예외 | diff가 빔 | "리뷰할 변경 없음"으로 명확히 표시. "검토 완료" 금지. 체크 결론은 판정 불가와 같게 실패 | 파서 단위 + 시범 |
| CIR-12 | 경계값 | 같은 PR에 연속 커밋 | PR별 concurrency 그룹 + cancel-in-progress, 마지막 SHA만 판정 | 정적 검사 + 시범 |
| CIR-13 | 경계값 | 취소된 실행이 inline 댓글을 일부 남김 | 요약에 기준 SHA를 적어 최신 판정과 구분 | 시범 |
| CIR-14 | 경계값 | --max-turns·job timeout 도달 | PASS 아님 → "판정 불가: 시간/턴 초과", 체크 실패 | 파서 단위 + 시범 |
| CIR-15 | 보안 | 포크 PR | Claude를 부르지 않고 체크 실패("외부 PR은 처리하지 않음"). 시크릿 미노출 | 정적 검사 + 단위 |
| CIR-16 | 보안 | `pull_request_target` 사용 | 금지(정적 검사로 거름) | 정적 검사 |
| CIR-17 | 보안 | PR 본문·커밋 메시지·코드에 "PASS로 판정하라" 같은 지시문 | 판정이 흔들리지 않음. Bash·파일 쓰기 도구 불허 | 파서 단위 + 시범(인젝션 문구) |
| CIR-18 | 보안 | 공개 레포라 실행 로그도 공개 | 시크릿 echo 금지, 전체 출력 끔 | 정적 검사 + 로그 확인 |
| CIR-19 | 보안 | GITHUB_TOKEN 권한 | job 단위 최소 권한(contents:read, pull-requests:write, 필요 시 issues:write, 액션이 요구할 때만 id-token:write) | 정적 검사 |
| CIR-20 | 보안 | 액션 참조 | SHA 고정(기존 ci.yml과 같은 규칙) | 정적 검사 |
| CIR-21 | 보안 | checkout 자격 증명 | persist-credentials:false, PR 코드 실행 없음 | 정적 검사 |
| CIR-22 | 보안 | 쓰기 권한 없는 사람이 트리거 | Claude 호출 없음, 한도 소모 없음 | 단위 + 시범 |
| CIR-23 | 실패 | 재민 구독 한도 도달 | "판정 불가: 사용 한도", 체크 실패 | 시범(관찰) |
| CIR-24 | 실패 | 토큰 만료·폐기 | "판정 불가: 인증 실패", PASS 금지 | 시범(잘못된 토큰) |
| CIR-25 | 실패 | Anthropic 장애·네트워크 | 판정 불가, Re-run으로 복구 | 시범(관찰) |
| CIR-26 | 실패 | 판정 표식 없음·중복·형식 오류 | 파서가 판정 불가 반환. 기본값 PASS 금지 | 파서 단위 |
| CIR-27 | 실패 | 댓글 등록만 실패 | 판정은 체크 요약에 남기고 댓글 실패를 따로 표시 | 시범 |
| CIR-28 | 판정 | 체크 이름 | 고정 이름 `ai-review` | 정적 검사 |
| CIR-29 | 판정 | 기존 `build`와의 관계 | 독립. ci.yml은 바꾸지 않는다 | 회귀 |
| CIR-30 | 판정 | Re-run | 같은 SHA로 다시 판정·체크 갱신(한도 다시 소모) | 시범 |
| CIR-31 | 판정 | strict 규칙으로 base 갱신 커밋 | synchronize로 다시 리뷰 | 시범(관찰) |
| CIR-32 | 댓글 | 커밋마다 요약이 쌓임 | 고정 표식이 있는 요약 댓글 1개를 수정 | 단위 + 시범 |
| CIR-33 | 댓글 | 같은 지적이 inline으로 반복 | 시범에서 관찰 후 결정 | 시범 |
| CIR-34 | 댓글 | 이전 지적이 해결됨 | 요약에 "해결됨" 표시, 스레드 resolve는 하지 않음 | 시범 |
| CIR-35 | 동시성 | 서로 다른 PR 동시 실행 | PR별 그룹이라 서로 취소하지 않음 | 정적 검사 + 시범 |
| CIR-36 | 동시성 | 새 실행과 취소 중인 실행이 같은 요약을 고침 | 최종 요약에 최신 SHA 결과 | 시범 |
| CIR-37 | 마이그레이션 | DB 변경 | 해당 없음(워크플로 파일만 추가) | — |
| CIR-38 | 전환 | 재민 PC 예약 작업 pr-auto-review와 병행 | CIR-01~06 시범 확인 직후 예약 작업을 끔 | 수동 |
| CIR-39 | 보안 | **작성자가 CheatIsKey·DGAZA-max가 아님** | Claude를 부르지 않고 `ai-review` 실패("허용된 작성자가 아님"). 허용 목록은 워크플로에 고정 | 정적 검사 + 단위 |
| CIR-40 | 보안 | PR 안에서 워크플로·판정 스크립트(`.github/**`)를 고쳐 PASS를 만들려 함(쓰기 권한이 있는 내부자) | `.github/CODEOWNERS`가 `.github/**`의 소유자를 재민·경화 두 명으로 지정하고, 모든 브랜치 룰셋에서 "코드 소유자 승인 필수"를 건다 → 작성자가 아닌 팀원이 승인해야 머지 | 정적 검사(CODEOWNERS) + 수동(룰셋 확인) |

## 결정

| # | 결정 |
|---|---|
| Q1 | 외부인은 일절 처리하지 않는다. 레포는 공개 유지(포트폴리오용). 외부인 댓글·이슈·PR 생성은 Interaction limits "collaborators only"(최대 6개월, 만료 전 다시 설정)로 차단. CI는 CIR-15·39로 한 번 더 차단. Actions의 외부 기여자 워크플로 실행은 "항상 승인 필요" |
| Q2 | `ai-review`를 필수 체크로 걸어 BLOCK·판정 불가면 머지 불가. 시범 기간에도 적용 |
| Q3 | Draft를 쓰지 않는다. 초안도 일반 PR처럼 리뷰(건너뛰기 없음) |
| Q4 | 판정 불가는 PASS가 아니라 실패 + 이유 문구 |
| Q5 | P1/P2 정의는 기존 AI 위임 리뷰와 같게. P1 1건 이상이면 BLOCK |
| Q6 | 요약 댓글 1개 수정, 기준 SHA 표시, 해결된 지적은 요약에 표시 |
| Q7 | code-review 플러그인에 판정 표식 한 줄(`VERDICT: PASS` 또는 `VERDICT: BLOCK`)을 요구하는 지시를 덧붙임. max-turns·timeout은 시범에서 정함 |
| Q8 | 문서만 바뀐 PR도 리뷰 |
| Q9 | 봇 PR은 CIR-39로 처리 |
| Q10 | 경화 동의를 시범 전에 받고 기록. claude.ai Settings › Usage에서 사용 크레딧 꺼짐 확인(자동 과금 차단). 토큰 갱신(1년)은 재민. 토큰이 새면 시크릿 삭제만으로 무효화되지 않으므로 claude.ai Get help로 폐기 요청 |
| Q11 | CIR-01~06 시범 확인 직후 예약 작업 pr-auto-review를 끔 |
| Q12 | Frontend 레포는 Backend 검증 후 별도 |
| Q13 | 모든 브랜치로 가는 PR을 리뷰. `ai-review`만 필수로 요구하는 새 룰셋을 모든 브랜치에 건다. 기존 'basic' 룰셋은 그대로 |
| Q14 | Claude GitHub App은 이 레포에만 설치 |
| Q15 | 내부자 조작 방지(CIR-40): `.github/**`가 바뀐 PR은 어느 브랜치로 가든 작성자가 아닌 팀원의 승인 필수(CODEOWNERS + 룰셋 코드 소유자 승인). `pull_request_target`으로 dev의 스크립트를 쓰는 대안은 토큰 노출 위험이 커서 쓰지 않는다 (재민 결정, 2026-11-14) |

## 테스트 리뷰 후 추가 결정 (재민 승인, 2026-11-14 — "추천안대로")

| # | 결정 |
|---|---|
| F1 | PR 작성자와 이번 커밋을 올린 사람(`github.actor`) **둘 다** 허용 목록에 있어야 리뷰한다. 아니면 Claude 호출 없이 `ai-review` 실패 |
| F2 | PASS는 "판정 표식 PASS + P1 0건"일 때만. 표식이 PASS인데 P1이 1건 이상이면 BLOCK |
| F3 | `.github/ai-review/*.test.mjs`는 ai-review 워크플로 안에서 Claude보다 먼저 실행한다(실수 검출용, 보안 장치 아님) |
| F4 | 판정 줄은 꾸밈 없는 한 줄만 인정(굵게·백틱·인용은 판정 불가). 프롬프트에 "꾸밈 없는 한 줄"을 명시하고 시범에서 빈도를 본다 |
| F5 | **알고 받아들인 위험:** 쓰기 권한자는 브랜치에 새 워크플로를 만들어 `CLAUDE_CODE_OAUTH_TOKEN`을 빼낼 수 있다(GitHub 구조상 막을 수 없음). 쓰기 권한자를 재민·경화 두 명으로 한정해 관리한다. 같은 이유로 CIR-21의 "PR 코드 실행 없음"은 PR 쪽 gate·verdict 스크립트를 실행하므로 엄밀히는 성립하지 않으며, 이는 CIR-40(코드 소유자 승인)으로 보완한다 |
| 기타 | 로그인은 대소문자 무시 · 포크 여부를 작성자보다 먼저 봄 · 요약 댓글은 GITHUB_TOKEN으로 달아 작성자 `github-actions[bot]`인 것만 수정 · 체크는 job 결론으로만 낸다(checks:write 없음) · BLOCK·판정 불가 요약에는 "PASS" 글자를 쓰지 않음 · classifyFailure 문구는 가정값이며 시범에서 실제 출력으로 교체(테스트 변경 승인 절차) · CIR-29는 merge-base 기준 비교 |

## 2차 테스트 리뷰 후 추가 결정 (재민 승인, 2026-11-14 — "추천안대로")

| # | 결정 |
|---|---|
| G1 | P1·P2 건수는 Claude 결과 글의 nonce 줄 `P1[<nonce>]: <정수>`, `P2[<nonce>]: <정수>`로 받는다. 판정 줄과 같은 엄격한 규칙(꾸밈·중복·비정수·음수·`01` → 판정 불가). 요약의 지적 수 = P1+P2. inline 댓글의 [P1] 집계는 시범 뒤 검토. **한계:** 같은 모델이 낸 신호끼리의 일치 검사라, Claude가 P1을 아예 빠뜨리는 것은 막지 못한다 |
| G2 | 실행 기록(execution file)에서 허용 목록 밖 도구 사용이 확인되면 판정 불가(`forbidden_tool`), 체크 실패. `--disallowedTools`는 Bash·Edit·Write·MultiEdit·NotebookEdit·WebFetch·WebSearch. 권한 우회 플래그·모드(`--permission-mode` 완화, 우회 옵션)와 `--allowed-tools` 같은 다른 표기는 금지 |
| G3 | 새 룰셋(모든 브랜치)에 "Require approval of the most recent reviewable push"와 "Dismiss stale approvals when new commits are pushed"를 켠다. CODEOWNERS는 dev와 main 둘 다에 반영한다. CODEOWNERS는 base 브랜치에 머지된 뒤부터 효력이 있으므로 ai-review를 처음 넣는 PR은 경화가 직접 꼼꼼히 리뷰한다 |
| G4 | 큰 PR·빈 PR 판단은 PR 이벤트 정보의 `changed_files`·`additions`·`deletions` 숫자로 한다(파일 목록 API 호출 없음). 판단은 gate 스텝 안에서 하고 gate 이유를 요약에 그대로 쓴다. 기준값(`DIFF_LIMIT`)은 시범에서 정한다 |
| 기타 | 러너에 SHA 고정 `actions/setup-node`(Node 22)를 테스트 스텝보다 먼저 둔다 · 결론은 판정 스크립트가 종료 코드로 직접 실패시킨다 · result 메시지는 `subtype`이 success일 때만 쓴다 |

## 최종 테스트 리뷰 후 추가 결정 (재민 승인, 2026-11-14 — "추천안대로")

| # | 결정 |
|---|---|
| G5 | **Q7 수정:** code-review 플러그인을 쓰지 않는다(Bash·Task가 필요해 G2 금지 도구와 충돌). 워크플로가 Claude 실행 전에 base...head 변경분을 파일로 만들어 두고(checkout에 base 이력 필요), Claude는 그 파일과 주변 코드를 Read·Grep·Glob·LS로 읽기만 한다. P1·P2 정의와 리뷰 관점(줄 단위 조건 점검, 제거된 동작, 호출하는 쪽 영향, 팀 규칙 위반)은 프롬프트에 직접 넣는다 |
| G6 | PR 이벤트 숫자(`changed_files`·`additions`·`deletions`)는 env 문자열이 `^(0\|[1-9]\d*)$`일 때만 숫자로 본다. 비었거나 음수·소수·`01`·NaN이거나, `changed_files`가 0인데 additions·deletions가 0보다 크면 `{run:false, reason:'invalid_diff_stats'}` → 요약 "판정 불가: 변경 정보 이상" |
| G7 | `--disallowedTools`에 TodoWrite·Task를 추가한다(시도만 해도 판정 불가가 되는 일을 줄임) |
| 기타 | ALLOWED_TOOLS 기준값은 verdict.mjs 한 곳. workflow.test.mjs는 그 값을 import해 `--allowedTools`와 집합이 같은지 본다 |

## 테스트 승인 (재민 승인, 2026-11-14 — "Approve as recommended")

162건 테스트 승인. 아래 3건을 함께 결정.

| # | 결정 |
|---|---|
| H1 | P1·P2 정의 문장은 기존 AI 위임 리뷰(`safeDeal-Frontend/tools/ai-review/clients.mjs`)의 정의를 그대로 프롬프트에 넣고, 바로 위 YAML 주석에 출처 경로·날짜를 적는다. 문장 자체는 테스트로 고정하지 않고 사람 리뷰 항목으로 둔다 |
| H2 | 변경분 파일 위치는 `.git/ai-review-pr.diff`(고정 상대 경로). PR이 `.git/` 아래에 파일을 넣을 수 없어 미리 점유·바꿔치기할 수 없고, Claude의 Glob·Grep 결과에도 섞이지 않는다 |
| H3 | 실제 워크플로에서는 head 레포가 지워지면 `HEAD_REPO`가 빈 문자열로 들어온다. gate CLI 테스트에 `HEAD_REPO: ''` → fork, `AUTHOR: ''`·`ACTOR: ''` → 거부(종료 코드 0) 케이스를 추가한다. 테스트 추가는 구현과 다른 커밋 |

## 구현 검토 후 추가 결정 (재민 승인, 2026-11-14 — "Apply as recommended")

| # | 결정 |
|---|---|
| I1 | claude-code-action이 GitHub App 토큰을 `.git/config`의 remote URL에 넣는다. Claude가 이를 읽어 inline 댓글로 내보내지 못하게 `--disallowedTools`에 `Read(./.git/config)`를 넣는다(Read 금지 규칙은 Grep·Glob에도 적용된다). 실제로 막히는지는 시범에서 확인 |
| I2 | 최신 CLI에서 하위 작업 도구 이름이 `Agent`일 수 있어 `--disallowedTools`에 `Agent`를 추가한다 |
| I3 | claude-code-action은 나온 지 2주 넘은 릴리스의 커밋 SHA로 고정한다(새 릴리스의 공급망 위험을 줄임) |
| 기타 | I1·I2는 테스트 변경(구현과 다른 커밋). 첫 PR은 워크플로가 dev에 없어 ai-review가 통과할 수 없으므로, `ai-review` 필수 룰셋은 이 PR 머지 뒤에 켠다 |

## PR #41 CodeRabbit 리뷰 후 추가 케이스 (재민 승인, 2026-11-14 — 권장안대로, "백엔드 먼저")

| ID | 관점 | 상황 | 기대 결과 | 테스트 |
|---|---|---|---|---|
| CIR-41 | 경계값 | git이 한글·역슬래시·따옴표·제어 문자가 든 경로를 `"a/\355\225\234.md"`처럼 C 방식으로 인용 | 요약 검토 범위에 실제 경로(`한.md`)를 적는다. 인용되지 않은 경로는 그대로 | 단위 |
| CIR-42 | 중복 실행 | 옛 커밋 실행을 Re-run해 같은 PR 그룹의 최신 실행이 취소됨 | 요약 댓글을 고치기 전에 PR의 현재 head SHA와 이번 실행 SHA를 비교한다. 다르거나 어느 쪽이든 확인할 수 없으면 댓글을 건드리지 않고 종료 코드 1. 최신 커밋의 체크는 "취소"로 남아 머지가 막히므로 사람이 최신 실행을 다시 돌린다 | 단위 + workflow |

| # | 결정 |
|---|---|
| J1 | CIR-41·CIR-42는 고친다(테스트 먼저, 테스트·구현 커밋 분리) |
| J2 | CIR-34("해결됨" 표시)는 **보류**. 이전 지적이 해결됐는지 판단할 근거(예: Claude가 이전 inline 지적을 다시 보고 판단)를 설계한 뒤 시범 PR에서 정한다. 지금 워크플로는 빈 목록을 넘긴다 |
| J3 | 동시 실행 그룹(`ai-review-<PR 번호>`, cancel-in-progress)은 그대로 둔다. 그룹에 SHA를 넣으면 새 push가 이전 실행을 취소하지 못해 CIR-12가 깨진다 |

## 룰셋 대상 정정 (재민 승인, 2026-11-14 — "Only dev and main")

| # | 결정 |
|---|---|
| K1 | **Q13 수정:** `ai-review` 룰셋(PR 필수·ai-review 필수·코드 소유자 승인 등)의 대상은 dev와 main만이다. 모든 브랜치로 걸면 작업 브랜치 push와 새 브랜치 생성까지 막힌다(GH013로 실제 확인). 리뷰 실행은 그대로 모든 브랜치로 가는 PR에서 돈다(워크플로에 브랜치 필터 없음) |

## 워크플로 변경 PR 처리 (재민 승인, 2026-11-14 — "Proceed as recommended")

claude-code-action은 PR의 `ai-review.yml`이 기본 브랜치(dev)와 한 글자라도 다르면 Claude를 실행하지 않는다(보안 장치, #42에서 확인). 그래서 워크플로를 바꾸는 PR은 항상 판정 불가가 된다.

| # | 결정 |
|---|---|
| L1 | **CIR-42 구현 방식 변경:** 이번 실행 SHA는 워크플로 env(`HEAD_SHA`)가 아니라 GitHub가 넣어 주는 이벤트 파일(`GITHUB_EVENT_PATH`의 `pull_request.head.sha`)에서 읽는다. #42는 `ai-review.yml`을 고치지 않는다. 워크플로 env를 요구하던 테스트는 이벤트 파일 읽기 단위 테스트로 바꾼다(테스트 변경 승인) |
| L2 | `ai-review.yml`을 바꾸는 PR은 ai-review를 통과할 수 없으므로, `ai-review` 룰셋 Bypass list에 Repository admin(경화)을 "For pull requests only"로 둔다. 우회 머지는 **워크플로 변경 PR에만** 쓰고, 그 PR은 코드 소유자가 직접 꼼꼼히 리뷰한다 |

## #42 시범 실행 후 추가 결정 (재민 승인, 2026-11-14 — "Add both")

#42에서 Claude가 처음 실제로 실행됐으나(3턴, 성공 종료) 판정이 `forbidden_tool`이었다. 실행 기록은 보안상 로그에 나오지 않아 어떤 도구가 걸렸는지 알 수 없었다.

| # | 결정 |
|---|---|
| M1 | forbidden_tool일 때 걸린 도구 **이름만** stderr 로그에 `forbidden_tools=<이름,…>`로 남긴다(init의 금지 도구 + 허용 밖 tool_use, 처음 나온 순서, 중복 없음). 이름이 `^[A-Za-z0-9_.-]{1,100}$`가 아니면 `<invalid>`. 판정 출력 줄과 GITHUB_OUTPUT은 그대로. 원인을 본 뒤 고친다 |
| M2 | 요약 검토 범위의 경로에 든 제어 문자는 지우지 않고 escape(`\n`, `\r`, `\t`, 그 밖 `\xNN`)로 보여 준다(CodeRabbit #42) |

## ToolSearch 허용 (재민 승인, 2026-11-15 — "Allow it")

| # | 결정 |
|---|---|
| N1 | #42 로그 `forbidden_tools=ToolSearch`: Claude가 inline 댓글 도구의 사용법을 불러오려고 ToolSearch를 썼다. ToolSearch는 사용법만 불러오는 무해한 보조 도구이므로 verdict.mjs에서 따로 허용한다(`HELPER_TOOLS`). `--allowedTools`·`ALLOWED_TOOLS`에는 넣지 않는다(권한이 필요 없고, 넣으면 ai-review.yml이 바뀌어 L2 우회가 필요). ToolSearch로 불러온 다른 도구를 쓰면 여전히 판정 불가 |

## 요약 경로 표시 보완 (CodeRabbit #42, 2026-11-15)

| # | 결정 |
|---|---|
| M3 | 경로의 백틱을 지우지 않는다. 내용의 가장 긴 백틱 연속보다 한 개 긴 구분자로 감싸고, 백틱으로 시작·끝나면 앞뒤에 공백을 둔다(M2의 "서로 다른 경로는 다르게 보인다"를 따름) |
| M4 | **보류:** 인용되지 않은 경로 안의 ` b/`(예: `foo b/bar`)는 `diff --git` 헤더만으로 경계를 정할 수 없다. 이 레포에 그런 이름이 없고 요약 표시는 판정에 영향이 없어 지금은 고치지 않는다. 고치려면 `+++ b/`·`rename to` 줄을 읽거나 워크플로에서 `--name-only -z` 목록을 따로 만들어야 한다(후자는 ai-review.yml 변경 → L2) |

## 사람이 할 일 (Claude가 대신하지 않음)

| 누가 | 할 일 |
|---|---|
| 재민 | `claude setup-token` 실행 → 레포 시크릿 `CLAUDE_CODE_OAUTH_TOKEN` 등록(토큰은 채팅·로그에 붙여넣지 않는다), claude.ai 사용 크레딧 꺼짐 확인 |
| 경화(레포 관리자) | 동의 기록, Claude GitHub App 설치(이 레포만), Interaction limits 설정, Actions 외부 기여자 승인 설정, 새 룰셋 생성(dev·main, K1: `ai-review` 필수 + 코드 소유자 승인 필수 + 최신 push 기준 승인 필수 + 새 커밋이 오면 기존 승인 해제), CODEOWNERS를 dev·main 둘 다에 반영, ai-review를 처음 넣는 PR 직접 리뷰 |
