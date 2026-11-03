const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const script = fs.readFileSync('app/src/main/assets/sign-input.js', 'utf8');
let digits = '', pending = null, submitted = [];
const box = {get textContent() {return digits.split('').join(' ');}};
const keys = [...'0123456789', '重置', '确认'].map(key => ({
  childElementCount: 0, textContent: key, getClientRects: () => [1],
  click() { pending = () => {if(key === '重置') digits = ''; else if(key === '确认') submitted.push(digits); else if(digits.length < 4) digits += key;}; }
}));
const context = {location:{origin:'https://attendance.nbu.edu.cn',pathname:'/h5/student/sign/'}, window:{}, document:{
  querySelector: selector => selector === '.code-view' ? box : null,
  querySelectorAll: selector => {
    assert.ok(selector.startsWith('.key-board '), 'must never match an already entered code digit');
    return keys;
  }
}};
vm.createContext(context); vm.runInContext(script,context);
const input = context.window.__nbuSignInput;
for (const key of '1112') {
  const before = input.read();
  assert.equal(input.click(key), true);
  assert.equal(input.read(), before, 'do not optimistically acknowledge a pending DOM update');
  pending(); pending = null;
  assert.equal(input.read(), before + key);
}
assert.equal(input.read(), '1112');
assert.equal(input.click('确认'), true); pending();
assert.deepEqual(submitted, ['1112']);
// School clears after an unsuccessful response; the next read must be empty.
digits = ''; assert.equal(input.read(), '');
input.click('0'); pending(); assert.equal(input.read(), '0');
input.click('重置'); pending(); assert.equal(input.read(), '');
assert.equal(input.click('missing'), false);
context.location.origin = 'https://example.com';
assert.equal(input.click('1'), false); assert.equal(input.read(), null);
console.log('Sign input regression checks passed');
