/*───────────────────────────────────────────────────────────────────────────────
 * fetch-wrapper.js – 공통 REST 래퍼
 *   ‣ Promise 기반 fetch + JSON 파싱 + 오류 공통 처리
 *   ‣ 동일 Origin(쿠키) 요청, CSRF 토큰 헤더 자동 첨부(Optional)
 *─────────────────────────────────────────────────────────────────────────────*/

const CSRF_HEADER = 'X-CSRF-TOKEN';          // 백엔드 설정에 맞춰 필요 시 수정
const CSRF_META_SELECTOR = 'meta[name="_csrf"]';

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

  /* 3) CSRF 토큰 헤더 자동 부착 (meta 태그가 존재할 때) */
  if (!opts.headers[CSRF_HEADER]) {
    const meta = document.querySelector(CSRF_META_SELECTOR);
    if (meta) opts.headers[CSRF_HEADER] = meta.content;
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
