#!/usr/bin/env node
// Behavioral parity gate: the browser-side xhttp validators in assets/web/app.js
// must agree with core/repo/ProfileValidation.kt on every case in the shared table.
//
// Zero dependencies (node:vm only). The JS functions are pure — no DOM — so we can
// extract them by brace matching and evaluate them in an isolated context instead of
// pulling in jsdom. Exit 1 on any mismatch so it can be wired into CI or a Gradle test.

import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const appJsPath = path.join(root, 'core/src/main/assets/web/app.js');
const casesPath = path.join(root, 'core/src/test/resources/xhttp-validation-cases.json');

const appJs = fs.readFileSync(appJsPath, 'utf8');
const cases = JSON.parse(fs.readFileSync(casesPath, 'utf8'));

/** Extract one top-level `function name(...) { ... }` by balanced braces. */
function extractFunction(src, name) {
  const marker = `function ${name}(`;
  const start = src.indexOf(marker);
  if (start < 0) throw new Error(`app.js: missing function ${name}()`);
  let depth = 0;
  for (let i = src.indexOf('{', start); i < src.length; i++) {
    const c = src[i];
    if (c === '{') depth++;
    else if (c === '}' && --depth === 0) return src.slice(start, i + 1);
  }
  throw new Error(`app.js: unbalanced braces in ${name}()`);
}

const fnNames = [
  'isIntJP', 'int32MaxJP', 'isInt32JP',
  'isXhttpPaddingJP', 'isXhttpWindowSizeJP', 'isXhttpMaxConnsJP',
];
const ctx = vm.createContext({});
vm.runInContext(fnNames.map((n) => extractFunction(appJs, n)).join('\n'), ctx);

const mapping = {
  isXhttpPadding: 'isXhttpPaddingJP',
  isXhttpWindowSizeMB: 'isXhttpWindowSizeJP',
  isXhttpMaxConns: 'isXhttpMaxConnsJP',
};

const failures = [];
let total = 0;

for (const [kotlinName, jsName] of Object.entries(mapping)) {
  const rows = cases[kotlinName];
  if (!Array.isArray(rows) || rows.length < 5) {
    failures.push(`${kotlinName}: case table missing or too small (${rows?.length ?? 0})`);
    continue;
  }
  // Counterfactual guard: a table with only valid cases would let an always-true
  // predicate pass. Both outcomes must be exercised.
  const hasTrue = rows.some(([, expected]) => expected === true);
  const hasFalse = rows.some(([, expected]) => expected === false);
  if (!hasTrue || !hasFalse) {
    failures.push(`${kotlinName}: case table needs both valid and invalid samples`);
  }
  if (typeof ctx[jsName] !== 'function') {
    failures.push(`${kotlinName}: ${jsName} was not evaluated as a function`);
    continue;
  }
  for (const [value, expected] of rows) {
    total++;
    let actual;
    try {
      actual = ctx[jsName](value);
    } catch (e) {
      failures.push(`${kotlinName}(${JSON.stringify(value)}): threw ${e.message}`);
      continue;
    }
    if (Boolean(actual) !== expected) {
      failures.push(
        `${kotlinName}(${JSON.stringify(value)}): expected ${expected}, got ${actual} [${jsName}]`
      );
    }
  }
}

if (failures.length > 0) {
  console.error(`XHTTP parity FAILED (${failures.length} problem(s) over ${total} cases):`);
  for (const f of failures) console.error(`  - ${f}`);
  console.error('Both runtimes must agree with core/src/test/resources/xhttp-validation-cases.json');
  process.exit(1);
}

console.log(`XHTTP parity OK: ${total} cases across ${Object.keys(mapping).length} predicates`);
