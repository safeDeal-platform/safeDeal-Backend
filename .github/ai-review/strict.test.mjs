// 엄격 리뷰 — STR-01~18 (S1~S5, QS-1~4). CASES.md "엄격 리뷰" 섹션.
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseYaml } from './test-support/mini-yaml.mjs';
import {
  checkReviewEvidence, filesNeedingReview, finalVerdict, parseQuestionCount, parseReviewed, readFiles,
} from './verdict.mjs';
import { buildSummary } from './summary.mjs';

const N = 'a1b2c3d4';
const WS = '/home/runner/work/repo/repo';
const unknown = (reason) => ({ verdict: 'UNKNOWN', reason });

// ── 변경분 픽스처 ──
const diffOf = (...blocks) => blocks.join('\n');
const modified = (p) => `diff --git a/${p} b/${p}\nindex 1..2 100644\n--- a/${p}\n+++ b/${p}\n@@ -1 +1 @@\n-a\n+b`;
const added = (p) => `diff --git a/${p} b/${p}\nnew file mode 100644\nindex 0..2\n--- /dev/null\n+++ b/${p}\n@@ -0,0 +1 @@\n+b`;
const deleted = (p) => `diff --git a/${p} b/${p}\ndeleted file mode 100644\nindex 1..0\n--- a/${p}\n+++ /dev/null\n@@ -1 +0,0 @@\n-a`;
const binary = (p) => `diff --git a/${p} b/${p}\nnew file mode 100644\nindex 0..2\nBinary files /dev/null and b/${p} differ`;
const renamed = (from, to) => `diff --git a/${from} b/${to}\nsimilarity index 90%\nrename from ${from}\nrename to ${to}`;

// ── 실행 기록 픽스처 ──
const init = (tools) => ({ type: 'system', subtype: 'init', tools });
const read = (filePath, extra = {}) => ({
  type: 'assistant', message: { content: [{ type: 'tool_use', id: 'r', name: 'Read', input: { file_path: filePath, ...extra } }] },
});
const grep = (path) => ({ type: 'assistant', message: { content: [{ type: 'tool_use', id: 'g', name: 'Grep', input: { pattern: 'x', path } }] } });
const result = (text) => ({ type: 'result', subtype: 'success', result: text });
const execution = (...messages) => JSON.stringify(messages);
const reviewed = (...paths) => paths.map((p) => `REVIEWED[${N}]: ${p}`);
const counts = (p1, p2, p3) => [`P1[${N}]: ${p1}`, `P2[${N}]: ${p2}`, `P3[${N}]: ${p3}`];

// ── STR-03·04·05·06 finalVerdict: P1 또는 P2가 1건 이상이면 BLOCK ──

test('STR-03 표식 PASS, P1 0, P2 0이면 PASS', () => {
  assert.equal(finalVerdict({ verdict: 'PASS', p1Count: 0, p2Count: 0 }), 'PASS');
});

test('STR-04 표식 PASS여도 P2가 1건 이상이면 BLOCK', () => {
  assert.equal(finalVerdict({ verdict: 'PASS', p1Count: 0, p2Count: 1 }), 'BLOCK');
  assert.equal(finalVerdict({ verdict: 'PASS', p1Count: 0, p2Count: 7 }), 'BLOCK');
});

test('STR-05 표식 PASS여도 P1이 1건 이상이면 BLOCK (유지)', () => {
  assert.equal(finalVerdict({ verdict: 'PASS', p1Count: 1, p2Count: 0 }), 'BLOCK');
});

test('STR-06 P2 수를 알 수 없으면 PASS로 보지 않는다', () => {
  for (const p2Count of [undefined, null, '0', -1, NaN, 1.5]) {
    assert.equal(finalVerdict({ verdict: 'PASS', p1Count: 0, p2Count }), 'UNKNOWN', String(p2Count));
  }
});

// ── STR-08·09 P3(질문) 건수 줄: P1·P2와 같은 엄격한 규칙 (QS-1 필수) ──

test('STR-08 P3[<nonce>]: <정수> 한 줄을 읽는다', () => {
  assert.equal(parseQuestionCount(['리뷰', `P3[${N}]: 2`].join('\n'), N), 2);
  assert.equal(parseQuestionCount(`  P3[${N}]: 0  \r\n`, N), 0);
});

test('STR-09 P3 줄이 없거나·중복이거나·정수가 아니면 undefined', () => {
  assert.equal(parseQuestionCount('리뷰만 있음', N), undefined);
  assert.equal(parseQuestionCount(`P3[${N}]: 1\nP3[${N}]: 1`, N), undefined);
  for (const v of ['01', '-1', '1.0', 'two', '']) assert.equal(parseQuestionCount(`P3[${N}]: ${v}`, N), undefined, v);
});

test('STR-09 다른 nonce·꾸민 줄은 P3 줄이 아니다', () => {
  assert.equal(parseQuestionCount('P3[fake]: 1', N), undefined);
  assert.equal(parseQuestionCount(`**P3[${N}]: 1**`, N), undefined);
  assert.equal(parseQuestionCount(`P3[${N}]: 1`, ''), undefined);
});

// ── STR-13 REVIEWED 줄 ──

test('STR-13 REVIEWED[<nonce>]: <경로> 줄을 순서대로 읽는다', () => {
  const text = ['요약', ...reviewed('src/A.java', 'docs/b.md'), 'REVIEWED[fake]: src/Z.java', `- REVIEWED[${N}]: src/C.java`].join('\n');
  assert.deepEqual(parseReviewed(text, N), ['src/A.java', 'docs/b.md']);
});

test('STR-13 REVIEWED 줄이 하나도 없으면 빈 목록', () => {
  assert.deepEqual(parseReviewed('요약', N), []);
});

test('STR-14 같은 경로가 두 번이거나 경로가 비었으면 undefined(형식 오류)', () => {
  assert.equal(parseReviewed(reviewed('src/A.java', 'src/A.java').join('\n'), N), undefined);
  assert.equal(parseReviewed(`REVIEWED[${N}]: `, N), undefined);
  assert.equal(parseReviewed(`REVIEWED[${N}]:src/A.java`, N), undefined);
});

// ── STR-16 검토해야 할 파일: 삭제·바이너리 제외 ──

test('STR-16 수정·추가 파일은 대상, 삭제·바이너리 파일은 제외', () => {
  const diff = diffOf(modified('src/A.java'), added('src/B.java'), deleted('src/Old.java'), binary('img/logo.png'));
  assert.deepEqual(filesNeedingReview(diff), ['src/A.java', 'src/B.java']);
});

test('STR-16 이름만 바뀐 파일은 새 경로가 대상', () => {
  assert.deepEqual(filesNeedingReview(renamed('src/Old.java', 'src/New.java')), ['src/New.java']);
});

test('STR-16 git이 인용한 경로("…")는 실제 경로로 되돌린다', () => {
  const diff = 'diff --git "a/docs/\\355\\225\\234.md" "b/docs/\\355\\225\\234.md"\n--- "a/docs/\\355\\225\\234.md"\n+++ "b/docs/\\355\\225\\234.md"\n@@ -1 +1 @@\n-a\n+b';
  assert.deepEqual(filesNeedingReview(diff), ['docs/한.md']);
});

test('STR-16 변경분이 비었으면 빈 목록', () => {
  assert.deepEqual(filesNeedingReview(''), []);
});

// ── STR-17 실행 기록의 Read: 작업 폴더 기준 상대 경로 (QS-4 일부만 읽어도 인정) ──

test('STR-17 Read의 절대 경로를 작업 폴더 기준 상대 경로로 바꾼다', () => {
  const json = execution(init(['Read']), read(`${WS}/src/A.java`), read('src/B.java'), read(`./src/C.java`), result('끝'));
  assert.deepEqual([...readFiles(json, WS)].sort(), ['src/A.java', 'src/B.java', 'src/C.java']);
});

test('STR-17 offset·limit으로 일부만 읽어도 읽은 것으로 본다', () => {
  const json = execution(read(`${WS}/src/A.java`, { offset: 900, limit: 300 }), result('끝'));
  assert.deepEqual([...readFiles(json, WS)], ['src/A.java']);
});

test('STR-17 Grep·작업 폴더 밖 Read·.git 아래 diff 파일은 파일을 읽은 것으로 보지 않는다', () => {
  const json = execution(grep(`${WS}/src/A.java`), read('/etc/passwd'), read(`${WS}/../other/src/A.java`), read(`${WS}/.git/ai-review-pr.diff`), result('끝'));
  assert.deepEqual([...readFiles(json, WS)], []);
});

// ── STR-14·15 검토 증거 (QS-2: 목록 + 실제 Read 대조) ──

const DIFF = diffOf(modified('src/A.java'), added('src/ATest.java'), deleted('src/Old.java'));
const evidence = ({ text, json = execution(read(`${WS}/src/A.java`), read(`${WS}/src/ATest.java`), result('끝')), diff = DIFF } = {}) =>
  checkReviewEvidence({ text, nonce: N, diffText: diff, executionJson: json, workspace: WS });

test('STR-14 바뀐 파일 전부를 REVIEWED에 적고 전부 Read했으면 문제 없음(null). 삭제 파일은 요구하지 않는다', () => {
  assert.equal(evidence({ text: reviewed('src/A.java', 'src/ATest.java').join('\n') }), null);
});

test('STR-14 REVIEWED 순서는 상관없다', () => {
  assert.equal(evidence({ text: reviewed('src/ATest.java', 'src/A.java').join('\n') }), null);
});

test('STR-14 바뀐 파일이 REVIEWED에서 빠지면 incomplete_review', () => {
  assert.deepEqual(evidence({ text: reviewed('src/A.java').join('\n') }), unknown('incomplete_review'));
});

test('STR-14 변경분에 없는 경로를 REVIEWED에 적으면 incomplete_review', () => {
  assert.deepEqual(evidence({ text: reviewed('src/A.java', 'src/ATest.java', 'src/Ghost.java').join('\n') }), unknown('incomplete_review'));
});

test('STR-14 REVIEWED 줄이 형식 오류(중복)면 incomplete_review', () => {
  assert.deepEqual(evidence({ text: reviewed('src/A.java', 'src/ATest.java', 'src/A.java').join('\n') }), unknown('incomplete_review'));
});

test('STR-15 REVIEWED에 적었지만 실행 기록에 그 파일 Read가 없으면 incomplete_review', () => {
  const json = execution(read(`${WS}/src/A.java`), grep(`${WS}/src/ATest.java`), result('끝'));
  assert.deepEqual(evidence({ text: reviewed('src/A.java', 'src/ATest.java').join('\n'), json }), unknown('incomplete_review'));
});

test('STR-18 변경분을 읽을 수 없으면(빈 값) incomplete_review', () => {
  for (const diff of ['', undefined]) {
    assert.deepEqual(evidence({ text: reviewed('src/A.java').join('\n'), diff }), unknown('incomplete_review'), String(diff));
  }
});

// ── CLI conclude: 판정·증거·P3를 함께 본다 ──
const CLI = fileURLToPath(new URL('./verdict.mjs', import.meta.url));
const dir = mkdtempSync(join(tmpdir(), 'ai-review-strict-'));
after(() => rmSync(dir, { recursive: true, force: true }));
const DIFF_PATH = join(dir, 'pr.diff');
writeFileSync(DIFF_PATH, diffOf(modified('src/A.java')));

function conclude(lines, { messages, env = {} } = {}) {
  const file = join(dir, `exec-${Math.random().toString(16).slice(2)}.json`);
  const msgs = messages ?? [init(['Read']), read(`${WS}/src/A.java`), result(lines.join('\n'))];
  writeFileSync(file, JSON.stringify(msgs));
  const r = spawnSync(process.execPath, [CLI, 'conclude'], {
    env: { PATH: process.env.PATH, NONCE: N, EXECUTION_FILE: file, DIFF_FILE: DIFF_PATH, GITHUB_WORKSPACE: WS, ...env },
    encoding: 'utf8',
  });
  return { status: r.status, out: r.stdout };
}
const full = (p1, p2, p3, verdict) => [...reviewed('src/A.java'), ...counts(p1, p2, p3), `VERDICT[${N}]: ${verdict}`];

test('STR-03 CLI: 증거가 온전하고 P1·P2 0이면 PASS, 종료 코드 0, questions 출력', () => {
  const r = conclude(full(0, 0, 0, 'PASS'));
  assert.match(r.out, /^verdict=PASS$/m);
  assert.match(r.out, /^questions=0$/m);
  assert.equal(r.status, 0);
});

test('STR-04 CLI: 표식 PASS여도 P2 1건이면 BLOCK, 종료 코드 0 아님', () => {
  const r = conclude(full(0, 1, 0, 'PASS'));
  assert.match(r.out, /^verdict=BLOCK$/m);
  assert.match(r.out, /^findings=1$/m);
  assert.notEqual(r.status, 0);
});

test('STR-10 CLI: P3만 있으면 PASS, questions는 P3 수, findings는 P1+P2', () => {
  const r = conclude(full(0, 0, 2, 'PASS'));
  assert.match(r.out, /^verdict=PASS$/m);
  assert.match(r.out, /^findings=0$/m);
  assert.match(r.out, /^questions=2$/m);
  assert.equal(r.status, 0);
});

test('STR-09 CLI: P3 줄이 없으면 invalid_counts', () => {
  const r = conclude([...reviewed('src/A.java'), `P1[${N}]: 0`, `P2[${N}]: 0`, `VERDICT[${N}]: PASS`]);
  assert.match(r.out, /^verdict=UNKNOWN$/m);
  assert.match(r.out, /^reason=invalid_counts$/m);
  assert.notEqual(r.status, 0);
});

test('STR-14 CLI: REVIEWED 줄이 없으면 incomplete_review', () => {
  const r = conclude([...counts(0, 0, 0), `VERDICT[${N}]: PASS`]);
  assert.match(r.out, /^verdict=UNKNOWN$/m);
  assert.match(r.out, /^reason=incomplete_review$/m);
  assert.notEqual(r.status, 0);
});

test('STR-15 CLI: REVIEWED는 있지만 Read를 안 했으면 incomplete_review', () => {
  const r = conclude([], { messages: [init(['Read']), result(full(0, 0, 0, 'PASS').join('\n'))] });
  assert.match(r.out, /^reason=incomplete_review$/m);
  assert.notEqual(r.status, 0);
});

test('STR-18 CLI: DIFF_FILE이 없거나 읽을 수 없으면 incomplete_review', () => {
  for (const DIFF_FILE of ['', join(dir, 'missing.diff')]) {
    const r = conclude(full(0, 0, 0, 'PASS'), { env: { DIFF_FILE } });
    assert.match(r.out, /^reason=incomplete_review$/m, DIFF_FILE);
    assert.notEqual(r.status, 0);
  }
});

test('STR-14 CLI: BLOCK 표식이어도 증거가 없으면 판정 불가(대충 본 BLOCK도 믿지 않는다)', () => {
  const r = conclude([...counts(1, 0, 0), `VERDICT[${N}]: BLOCK`]);
  assert.match(r.out, /^verdict=UNKNOWN$/m);
  assert.match(r.out, /^reason=incomplete_review$/m);
});

// ── STR-10 요약: 질문 수 ──

test('STR-10 요약에 질문(P3) 수를 적는다', () => {
  const text = buildSummary({ verdict: 'PASS', sha: 'a'.repeat(40), reviewedFiles: ['src/A.java'], findingCount: 0, questionCount: 2, resolved: [] });
  assert.match(text, /질문\(P3\): 2건/);
});

test('STR-10 질문 수를 모르면 "알 수 없음"', () => {
  const text = buildSummary({ verdict: 'PASS', sha: 'a'.repeat(40), reviewedFiles: ['src/A.java'], findingCount: 0, resolved: [] });
  assert.match(text, /질문\(P3\): 알 수 없음/);
});

test('STR-14 incomplete_review에는 사람이 읽을 이유 문구가 있다', () => {
  const text = buildSummary({ verdict: 'UNKNOWN', reason: 'incomplete_review', sha: 'a'.repeat(40), reviewedFiles: [], resolved: [] });
  assert.match(text, /판정 불가: 검토 증거 부족/);
});

// ── 워크플로 (STR-01·02·07·08·11·12·13·18) ──
const WF_PATH = fileURLToPath(new URL('../workflows/ai-review.yml', import.meta.url));
const wf = () => parseYaml(readFileSync(WF_PATH, 'utf8'));
const job = (w) => w.jobs?.['ai-review'];
const stepNamed = (w, name) => job(w)?.steps?.find((s) => s.name === name);
const claudeStep = (w) => job(w)?.steps?.find((s) => String(s.uses ?? '').startsWith('anthropics/claude-code-action@'));
const prompt = (w) => String(claudeStep(w)?.with?.prompt ?? '');
const NONCE_EXPR = '${{ steps.nonce.outputs.value }}';

test('STR-01 Claude 스텝은 Opus 모델을 쓴다', () => {
  assert.match(String(claudeStep(wf())?.with?.claude_args ?? ''), /--model[ =]+opus\b/);
});

test('STR-02 max-turns 80, Claude 스텝 timeout 35분, job timeout 45분', () => {
  const w = wf();
  assert.match(String(claudeStep(w)?.with?.claude_args ?? ''), /--max-turns[ =]+80\b/);
  assert.equal(claudeStep(w)?.['timeout-minutes'], 35);
  assert.equal(job(w)?.['timeout-minutes'], 45);
});

test('STR-07 프롬프트: P1 또는 P2가 1건 이상이면 BLOCK', () => {
  assert.ok(prompt(wf()).includes('P1 또는 P2가 1건 이상이면 BLOCK, 둘 다 0건이면 PASS'));
});

test('STR-08 프롬프트: P3 정의 줄, [P3] 댓글, P3 건수 줄', () => {
  const p = prompt(wf());
  assert.match(p, /^\s*-\s*P3\s*:/m, '줄 맨 앞 "- P3:" 정의 줄');
  assert.ok(p.includes('[P3]'), '[P3] 댓글 머리표');
  assert.ok(p.includes(`P3[${NONCE_EXPR}]: <정수>`), 'P3 건수 줄 형식');
});

test('STR-12 P1·P2 정의 문장은 그대로 둔다(H1)', () => {
  const p = prompt(wf());
  assert.ok(p.includes('- P1: P1 means a serious security, data integrity, or core flow defect that must block.'));
  assert.ok(p.includes('- P2: P2 means a concrete non-blocking defect. Do not report stylistic or speculative issues.'));
});

test('STR-13 프롬프트: 바뀐 파일마다 REVIEWED 줄', () => {
  assert.ok(prompt(wf()).includes(`REVIEWED[${NONCE_EXPR}]: <경로>`));
});

test('STR-18 Conclude 스텝에 DIFF_FILE, Summary 스텝에 QUESTIONS를 넘긴다', () => {
  const w = wf();
  assert.equal(stepNamed(w, 'Conclude')?.env?.DIFF_FILE, '.git/ai-review-pr.diff');
  assert.equal(stepNamed(w, 'Summary')?.env?.QUESTIONS, '${{ steps.conclude.outputs.questions }}');
});
