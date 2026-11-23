import Cookies from "js-cookie";
const psl = require("psl");

const isClient = typeof window !== "undefined";

function withCookieDomain(options = {}) {
  if (!isClient) {
    return options;
  }
  const pPsl = psl.parse(document.domain);
  let domain = pPsl.domain;
  if (/\d+\.\d+\.\d+\.\d+/.test(pPsl.input)) domain = pPsl.input;
  return { domain, ...options };
}

// 调用方直接存对象并在读时 JSON.parse；js-cookie 3 不再自动序列化，这里沿用 2.x 的规则
function toCookieValue(value) {
  try {
    const json = JSON.stringify(value);
    if (/^[{[]/.test(json)) return json;
  } catch (e) {}
  return value;
}

export default {
  setItem: (key, value, options = {}) => {
    Cookies.set(key, toCookieValue(value), withCookieDomain(options));
  },
  getItem: key => {
    return Cookies.get(key);
  },
  removeItem: (key, options = {}) => {
    Cookies.remove(key, withCookieDomain(options));
  }
};
