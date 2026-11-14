// 요약 댓글 — CIR-05, 06, 10, 11, 13, 14, 15, 23, 24, 26, 32, 34, 36, 39, F1, G2, G4, G6
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { SUMMARY_MARKER, buildSummary, findSummaryComment, pickReason } from './summary.mjs';

const MARKER = '<!-- ai-review-summary -->';
const SHA = '0123456789abcdef0123456789abcdef01234567';
const FILES = ['src/main/java/com/safedeal/A.java', 'README.md'];
const pass = { verdict: 'PASS', sha: SHA, reviewedFiles: FILES, findingCount: 0, resolved: [] };
const unknown = (reason) => ({ verdict: 'UNKNOWN', reason, sha: SHA, reviewedFiles: FILES, findingCount: 0, resolved: [] });

test('CIR-32 요약 표식 값은 고정', () => {
  assert.equal(SUMMARY_MARKER, MARKER);
});

test('CIR-13·CIR-32 모든 판정의 요약에 표식, 정확히 7자리 기준 SHA, 검토 범위가 있다', () => {
  for (const input of [pass, { ...pass, verdict: 'BLOCK', findingCount: 1 }, unknown('no_verdict')]) {
    const text = buildSummary(input);
    assert.ok(text.includes(MARKER), `${input.verdict}: 표식 없음`);
    assert.match(text, /기준 SHA[^\n]*0123456/, `${input.verdict}: 기준 SHA 없음`);
    assert.ok(!text.includes(SHA.slice(0, 8)), `${input.verdict}: SHA는 7자리만 적는다`);
    assert.match(text, /검토 범위/, `${input.verdict}: 검토 범위 없음`);
  }
});

test('CIR-05 PASS 요약은 고정 판정 줄, 검토한 파일, 지적 수를 함께 적는다', () => {
  const text = buildSummary({ ...pass, findingCount: 2 });
  assert.match(text, /^판정: PASS$/m);
  for (const f of FILES) assert.ok(text.includes(f), `검토 파일 ${f} 없음`);
  assert.match(text, /지적 수\D*2/);
});

test('CIR-06 BLOCK 요약은 고정 판정 줄과 지적 수를 적고 PASS라고 쓰지 않는다', () => {
  const text = buildSummary({ ...pass, verdict: 'BLOCK', findingCount: 3 });
  assert.match(text, /^판정: BLOCK$/m);
  assert.match(text, /지적 수\D*3/);
  assert.doesNotMatch(text, /PASS/);
});

const LABELS = {
  usage_limit: '사용 한도', // CIR-23
  auth_failed: '인증 실패', // CIR-24
  timeout: '시간/턴 초과', // CIR-14
  too_large: '범위 초과', // CIR-10
  no_verdict: '판정 표식 없음', // CIR-26
  multiple_verdicts: '판정 표식 중복', // CIR-26
  invalid_value: '판정 표식 형식 오류', // CIR-26
  invalid_diff_stats: '변경 정보 이상', // G6
};

for (const [reason, label] of Object.entries(LABELS)) {
  test(`CIR-10·14·23·24·26 판정 불가(${reason}) 요약은 "판정 불가: ${label}"`, () => {
    const text = buildSummary(unknown(reason));
    assert.match(text, new RegExp(`판정 불가: ${label}`));
    assert.doesNotMatch(text, /PASS/);
  });
}

test('CIR-25·26 한국어 문구가 정해지지 않은 이유도 판정 불가로 적고 PASS라고 쓰지 않는다', () => {
  for (const reason of ['upstream_error', 'unknown', 'empty_output', 'missing_nonce', 'forbidden_tool']) {
    const text = buildSummary(unknown(reason));
    assert.match(text, /판정 불가/, reason);
    assert.doesNotMatch(text, /PASS/, reason);
  }
});

test('CIR-15 포크 PR 요약은 "외부 PR은 처리하지 않음"', () => {
  const text = buildSummary(unknown('fork'));
  assert.match(text, /외부 PR은 처리하지 않음/);
  assert.doesNotMatch(text, /PASS/);
});

test('CIR-39 허용되지 않은 작성자 요약은 "허용된 작성자가 아님"', () => {
  const text = buildSummary(unknown('author_not_allowed'));
  assert.match(text, /허용된 작성자가 아님/);
  assert.doesNotMatch(text, /PASS/);
});

// 문구는 CASES.md에 없어 테스트 작성자가 정했다(사람 검토 필요).
test('F1 커밋한 사람이 허용되지 않으면 "커밋한 사람이 허용 목록에 없음"', () => {
  const text = buildSummary(unknown('actor_not_allowed'));
  assert.match(text, /커밋한 사람이 허용 목록에 없음/);
  assert.doesNotMatch(text, /PASS/);
});

test('CIR-11 diff가 비면 "리뷰할 변경 없음", "검토 완료"·PASS 금지', () => {
  const text = buildSummary({ ...unknown('empty_diff'), reviewedFiles: [] });
  assert.match(text, /리뷰할 변경 없음/);
  assert.doesNotMatch(text, /검토 완료/);
  assert.doesNotMatch(text, /PASS/);
});

test('CIR-34 해결된 이전 지적은 "해결됨"으로 표시', () => {
  const text = buildSummary({ ...pass, resolved: ['A.java:10 널 체크 누락'] });
  assert.match(text, /해결됨[\s\S]*A\.java:10 널 체크 누락/);
});

const bot = (id, body) => ({ id, body, user: { login: 'github-actions[bot]', type: 'Bot' } });
const claudeBot = (id, body) => ({ id, body, user: { login: 'claude[bot]', type: 'Bot' } });
const human = (id, body) => ({ id, body, user: { login: 'CheatIsKey', type: 'User' } });

test('CIR-32 표식이 있는 github-actions[bot] 댓글을 찾아 수정 대상으로 돌려준다', () => {
  const comments = [human(1, '좋아요'), bot(2, `${MARKER}\n판정: PASS`), bot(3, '다른 봇 메시지')];
  assert.equal(findSummaryComment(comments), 2);
});

test('CIR-32 사람이 표식을 붙여 쓴 댓글은 무시', () => {
  assert.equal(findSummaryComment([human(1, `> ${MARKER} 인용`)]), null);
});

test('CIR-32 claude[bot]이 단 표식 댓글은 수정 대상이 아니다(GITHUB_TOKEN 댓글만)', () => {
  assert.equal(findSummaryComment([claudeBot(4, MARKER)]), null);
  assert.equal(findSummaryComment([claudeBot(4, MARKER), bot(6, MARKER)]), 6);
});

test('CIR-32 요약 댓글이 없으면 null', () => {
  assert.equal(findSummaryComment([]), null);
  assert.equal(findSummaryComment([bot(5, '표식 없는 봇 댓글')]), null);
});

test('CIR-36 표식 댓글이 여럿이면 가장 먼저 단 것 하나만 수정한다', () => {
  assert.equal(findSummaryComment([bot(7, MARKER), bot(9, MARKER)]), 7);
});

// ── pickReason (G4) ── gate가 거른 이유가 있으면 그것을 쓴다(Claude를 부르지 않았으므로 판정 이유는 부수 결과다).
test('G4 gate 이유가 판정 이유보다 우선', () => {
  assert.equal(pickReason({ gateReason: 'too_large', verdictReason: 'empty_output' }), 'too_large');
  assert.equal(pickReason({ gateReason: 'fork', verdictReason: 'forbidden_tool' }), 'fork');
});

test('G4 gate 이유가 비면 판정 이유', () => {
  assert.equal(pickReason({ gateReason: '', verdictReason: 'no_verdict' }), 'no_verdict');
  assert.equal(pickReason({ gateReason: undefined, verdictReason: 'forbidden_tool' }), 'forbidden_tool');
});

test('G4 둘 다 비면 unknown', () => {
  assert.equal(pickReason({ gateReason: '', verdictReason: '' }), 'unknown');
  assert.equal(pickReason({}), 'unknown');
});
