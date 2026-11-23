/**
 * 买家端 Cookie 存储回归：调用方直接存对象（userInfo、navList 等），读出须仍是可 JSON.parse 的串。
 */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Cookies = require('js-cookie');

/** 用真实 js-cookie 加载 storage.js，document.cookie 用内存实现。 */
function harness() {
  const jar = new Map();
  const document = {
    domain: 'localhost',
    get cookie() { return [...jar].map(([k, v]) => `${k}=${v}`).join('; '); },
    set cookie(raw) {
      const [pair, ...attrs] = raw.split(';');
      const i = pair.indexOf('=');
      const expired = attrs.some(a => /^\s*expires=/i.test(a) && new Date(a.split('=')[1]) < new Date());
      if (expired) jar.delete(pair.slice(0, i)); else jar.set(pair.slice(0, i), pair.slice(i + 1));
    },
  };
  global.document = document;
  const source = fs.readFileSync(path.join(__dirname, '../src/plugins/storage.js'), 'utf8')
    .replace(/^import Cookies from .*;\r?\n/m, '').replace(/export default/, 'return');
  return new Function('Cookies', 'require', 'window', 'document', source)(Cookies, require, {}, document);
}

test('存对象读回 JSON 串，存字符串和数字原样读回', () => {
  const storage = harness();
  storage.setItem('userInfo', { nickName: '轻游用户', id: '1' });
  assert.deepEqual(JSON.parse(storage.getItem('userInfo')), { nickName: '轻游用户', id: '1' });
  storage.setItem('navList', [{ title: '首页' }]);
  assert.deepEqual(JSON.parse(storage.getItem('navList')), [{ title: '首页' }]);
  storage.setItem('accessToken', 'a.b.c');
  assert.equal(storage.getItem('accessToken'), 'a.b.c');
  storage.setItem('cartNum', 3);
  assert.equal(storage.getItem('cartNum'), '3');
  storage.removeItem('accessToken');
  assert.equal(storage.getItem('accessToken'), undefined);
});
