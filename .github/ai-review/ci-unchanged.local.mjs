// CIR-29 회귀 가드 — 로컬 전용.
// 이 브랜치가 ci.yml을 바꾸지 않았는지 merge-base(origin/dev) 기준으로 본다(커밋 + 작업 트리 모두).
// 이름이 *.test.mjs가 아니므로 워크플로의 `node --test ".github/ai-review/*.test.mjs"`(F3)에는 포함되지 않는다:
//   - CI의 얕은 checkout에는 origin/dev가 없고,
//   - 이 PR이 머지된 뒤 정당한 ci.yml 변경까지 막으면 안 되기 때문이다.
// 실행: node --test .github/ai-review/ci-unchanged.local.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const git = (...args) => execFileSync('git', args, { cwd: ROOT, encoding: 'utf8' });

test('CIR-29 이 브랜치는 merge-base 이후 ci.yml을 바꾸지 않았다', () => {
  let base;
  try {
    base = git('merge-base', 'HEAD', 'origin/dev').trim();
  } catch (e) {
    assert.fail(`origin/dev와의 merge-base를 찾을 수 없다(git fetch 필요): ${e.message}`);
  }
  assert.equal(git('diff', base, '--', '.github/workflows/ci.yml'), '', 'ci.yml 변경 금지');
});
