import { ApiError, CLIENT_ERROR_CODES } from './ApiError'
import { apiBaseUrl } from './config'
import { clearCsrfToken, ensureCsrfToken, peekCsrfToken } from './csrf'
import type { ApiErrorBody } from './types'

type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'

export interface RequestOptions {
  method?: HttpMethod
  /** JSON으로 보낼 본문. */
  body?: unknown
  /** 쿼리 파라미터. 값이 undefined·null·빈 문자열이면 뺀다. */
  query?: Record<string, string | number | boolean | null | undefined>
  /** 금전 처리 POST의 `Idempotency-Key`. 재시도할 때는 같은 값을 다시 넘긴다. */
  idempotencyKey?: string
  signal?: AbortSignal
}

/** 성공 응답과 함께 HTTP 상태가 필요할 때(예: 송금 201/202 구분) 쓴다. */
export interface ApiResult<T> {
  status: number
  data: T
}

const CSRF_INVALID = 'COMMON_CSRF_INVALID'
const UNAUTHORIZED_STATUS = 401

let unauthorizedListener: (() => void) | null = null

/** 401 응답을 받았을 때 호출할 함수(로그인 상태 초기화 등). */
export function setUnauthorizedListener(listener: (() => void) | null): void {
  unauthorizedListener = listener
}

/** 공통 요청 함수. 성공이면 `data`만 돌려주고 실패면 `ApiError`를 던진다. 204는 undefined. */
export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  return (await apiRequestWithStatus<T>(path, options)).data
}

export async function apiRequestWithStatus<T>(
  path: string,
  options: RequestOptions = {},
): Promise<ApiResult<T>> {
  const method = options.method ?? 'GET'
  const response = await send(path, method, options)

  // 상태 변경 요청은 CSRF 토큰이 만료·교체됐을 수 있다. CSRF 검사는 업무 처리 전에 거절하므로
  // 토큰을 새로 받아 한 번만 다시 보낸다(멱등성 키도 같은 값이라 중복 처리되지 않는다).
  if (response.status === 403 && isUnsafe(method)) {
    const error = await toApiError(response)
    if (error.code !== CSRF_INVALID) {
      throw error
    }
    clearCsrfToken()
    return parse<T>(await send(path, method, options))
  }
  return parse<T>(response)
}

async function send(path: string, method: HttpMethod, options: RequestOptions): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  if (options.idempotencyKey) {
    headers['Idempotency-Key'] = options.idempotencyKey
  }
  // Gateway·User는 POST·PUT·PATCH·DELETE에서만 CSRF를 검사한다. GET에는 이미 있는 토큰만 붙인다.
  const csrf = isUnsafe(method) ? await ensureCsrfTokenOrThrow() : peekCsrfToken()
  if (csrf) {
    headers[csrf.headerName] = csrf.token
  }

  try {
    return await fetch(buildUrl(path, options.query), {
      method,
      headers,
      credentials: 'include',
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
    })
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') {
      throw cause
    }
    throw new ApiError(0, CLIENT_ERROR_CODES.NETWORK, '서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.')
  }
}

async function ensureCsrfTokenOrThrow() {
  try {
    return await ensureCsrfToken()
  } catch {
    throw new ApiError(0, CLIENT_ERROR_CODES.NETWORK, '보안 토큰을 받지 못했습니다. 잠시 후 다시 시도해 주세요.')
  }
}

async function parse<T>(response: Response): Promise<ApiResult<T>> {
  if (!response.ok) {
    throw await toApiError(response)
  }
  if (response.status === 204) {
    return { status: response.status, data: undefined as T }
  }
  const body = await readJson(response)
  if (!isEnvelope(body) || !body.success) {
    throw new ApiError(
      response.status,
      CLIENT_ERROR_CODES.UNEXPECTED_RESPONSE,
      '서버 응답을 해석하지 못했습니다.',
    )
  }
  return { status: response.status, data: body.data as T }
}

async function toApiError(response: Response): Promise<ApiError> {
  if (response.status === UNAUTHORIZED_STATUS) {
    unauthorizedListener?.()
  }
  const body = await readJson(response)
  const error = isEnvelope(body) ? body.error : null
  if (error && typeof error.code === 'string') {
    return new ApiError(response.status, error.code, error.message, Array.isArray(error.details) ? error.details : [])
  }
  // 공통 오류 형식이 아닌 5xx는 Gateway까지 닿지 못한 경우다.
  // 예: 개발 서버 프록시가 Gateway에 연결하지 못하면 본문 없는 500을 돌려준다(터미널에 ECONNREFUSED).
  if (response.status >= 500) {
    return new ApiError(
      response.status,
      CLIENT_ERROR_CODES.NETWORK,
      '서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.',
    )
  }
  return new ApiError(
    response.status,
    CLIENT_ERROR_CODES.UNEXPECTED_RESPONSE,
    `요청을 처리하지 못했습니다. (HTTP ${response.status})`,
  )
}

async function readJson(response: Response): Promise<unknown> {
  const text = await response.text()
  if (!text) {
    return null
  }
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

function isEnvelope(body: unknown): body is { success: boolean; data: unknown; error: ApiErrorBody | null } {
  return typeof body === 'object' && body !== null && 'success' in body
}

function isUnsafe(method: HttpMethod): boolean {
  return method !== 'GET'
}

function buildUrl(path: string, query: RequestOptions['query']): string {
  const url = `${apiBaseUrl}${path}`
  if (!query) {
    return url
  }
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== '') {
      params.append(key, String(value))
    }
  }
  const qs = params.toString()
  return qs ? `${url}?${qs}` : url
}
