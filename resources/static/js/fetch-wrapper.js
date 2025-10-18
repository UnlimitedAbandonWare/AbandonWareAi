/*───────────────────────────────────────────────────────────────────────────────
 * fetch-wrapper.js – 공통 REST 래퍼
 *   ‣ Promise 기반 fetch + JSON 파싱 + 오류 공통 처리
 *   ‣ 동일 Origin(쿠키) 요청, CSRF 토큰 헤더 자동 첨부(Optional)
 *─────────────────────────────────────────────────────────────────────────────*/

const CSRF_META_SELECTOR = 'meta[name="_csrf"]';
const CSRF_HEADER_META_SELECTOR = 'meta[name="_csrf_header"]';

/**
 * 간편 REST 호출
 * @param {string} url          호출 경로(절대·상대)
 * @param {RequestInit} options fetch 옵션 (method / headers / body …)
 * @returns {Promise<any>}      JSON 파싱된 응답(body)
 * @throws {Error}              상태코드 2xx 외 | JSON 파싱 불가
 */
export async function apiCall(url, options = {}) {

  /* 1) 옵션 기본값 */
  const opts = {
    method : 'GET',
    credentials : 'same-origin',               // 쿠키 포함
    headers : {
      'Accept'       : 'application/json',
      ...options.headers
    },
    ...options
  };

  /* 2) body 객체면 JSON 직렬화 */
  if (opts.body && typeof opts.body === 'object' &&
      !(opts.body instanceof FormData) &&
      !(opts.body instanceof URLSearchParams)) {
    opts.headers['Content-Type'] = opts.headers['Content-Type'] ?? 'application/json';
    opts.body = JSON.stringify(opts.body);
  }

  /* 3) CSRF 토큰 헤더 자동 부착
   *
   * Spring Security exposes the CSRF token and header name via meta tags in
   * the HTML head.  When a POST/PUT/DELETE request is issued we need to
   * include the token in a header so that the server can validate the
   * request.  Some pages (e.g. the chat UI) do not embed the meta tags,
   * instead relying on the `XSRF‑TOKEN` cookie set by Spring Security.
   * Detect both sources and, if a token is found, attach it to the
   * appropriate header for non‑GET requests.  Use the `_csrf_header`
   * meta tag or default to `X‑XSRF‑TOKEN` as the header name.  Do not
   * overwrite an explicit header provided by the caller.
   */
  // Determine the header name from the meta tag or default value
  const metaHeader = document.querySelector(CSRF_HEADER_META_SELECTOR)?.content || 'X-XSRF-TOKEN';
  // Read the token from the meta tag (if present)
  const metaToken = document.querySelector(CSRF_META_SELECTOR)?.content;
  // Read the token from the cookie (if present).  Spring Security stores
  // the CSRF token in a cookie named XSRF-TOKEN.  Cookies are separated by
  // '; ' in document.cookie.
  let cookieToken;
  const cookieString = document.cookie || '';
  const xsrfCookie = cookieString.split('; ').find(p => p.startsWith('XSRF-TOKEN='));
  if (xsrfCookie) {
    cookieToken = xsrfCookie.split('=')[1];
  }
  // Prefer the meta token over the cookie token.  Fall back to cookie when
  // meta is absent.
  const token = metaToken || cookieToken;
  // Only attach the token for state‑changing requests (anything other than GET)
  const method = (opts.method || 'GET').toUpperCase();
  if (token && method !== 'GET' && !opts.headers[metaHeader]) {
    opts.headers[metaHeader] = decodeURIComponent(token);
  }

  /* 4) fetch */
  const res = await fetch(url, opts);

  /* 5) HTTP 오류 처리 */
  if (!res.ok) {
    let message;
    try {
      const errJson = await res.clone().json();
      message = errJson.message || JSON.stringify(errJson);
    } catch {
      message = await res.text();
    }
    throw new Error(`[${res.status}] ${message}`);
  }

  /* 6) 빈 응답(204) ⇒ null */
  if (res.status === 204) return null;

  /* 7) JSON 파싱 or text */
  const ct = res.headers.get('content-type') || '';
  return ct.includes('application/json') ? res.json() : res.text();
}
