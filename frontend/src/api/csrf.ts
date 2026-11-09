import { apiBaseUrl } from './config'

/** `GET /api/v1/auth/csrf` 응답. 헤더 이름은 서버가 정한 값을 그대로 쓴다. */
export interface CsrfToken {
  headerName: string
  token: string
}

let current: CsrfToken | null = null
let pending: Promise<CsrfToken> | null = null

/**
 * 저장된 CSRF 토큰을 돌려주고, 없으면 서버에서 받는다.
 * 동시에 여러 요청이 와도 토큰 요청은 한 번만 보낸다.
 */
export function ensureCsrfToken(): Promise<CsrfToken> {
  if (current) {
    return Promise.resolve(current)
  }
  if (!pending) {
    pending = requestCsrfToken().finally(() => {
      pending = null
    })
  }
  return pending
}

/** 현재 토큰(없으면 null). GET 요청에 함께 붙일 때 쓴다. */
export function peekCsrfToken(): CsrfToken | null {
  return current
}

/**
 * 토큰을 버린다. 로그인·로그아웃·비밀번호 변경 뒤에는 세션 ID가 바뀌어 이전 토큰이 무효가 되므로
 * 반드시 호출하고, 다음 변경 요청 전에 새로 받는다.
 */
export function clearCsrfToken(): void {
  current = null
}

async function requestCsrfToken(): Promise<CsrfToken> {
  // 순환 의존을 피하려고 공통 요청 함수 대신 fetch를 직접 쓴다.
  const response = await fetch(`${apiBaseUrl}/api/v1/auth/csrf`, {
    method: 'GET',
    credentials: 'include',
    headers: { Accept: 'application/json' },
  })
  const body: unknown = await response.json().catch(() => null)
  const token = readToken(body)
  if (!response.ok || !token) {
    throw new Error(`CSRF 토큰을 받지 못했습니다. (HTTP ${response.status})`)
  }
  current = token
  return token
}

function readToken(body: unknown): CsrfToken | null {
  if (typeof body !== 'object' || body === null || !('data' in body)) {
    return null
  }
  const data = (body as { data: unknown }).data
  if (typeof data !== 'object' || data === null) {
    return null
  }
  const { headerName, token } = data as Record<string, unknown>
  if (typeof headerName !== 'string' || typeof token !== 'string' || !headerName || !token) {
    return null
  }
  return { headerName, token }
}
