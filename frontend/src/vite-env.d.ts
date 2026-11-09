/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Gateway 기준 주소. 비우면 같은 출처(개발 서버 프록시)로 요청한다. */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
