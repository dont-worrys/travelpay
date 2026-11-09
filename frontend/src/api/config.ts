/**
 * API 기준 주소. 개발 서버는 Vite 프록시를 쓰므로 비워 둔다(같은 출처 `/api/...`).
 * 배포처럼 Gateway가 다른 출처에 있으면 `VITE_API_BASE_URL`에 지정한다.
 */
export const apiBaseUrl: string = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/+$/, '')
