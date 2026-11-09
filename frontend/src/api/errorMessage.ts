import { isApiError } from './ApiError'

/**
 * 서버 메시지를 그대로 쓰기 어려운 코드만 화면 문구를 바꾼다.
 * 나머지는 서버가 보낸 한국어 `error.message`를 그대로 보여 준다.
 */
const MESSAGES: Record<string, string> = {
  COMMON_UNAUTHORIZED: '로그인이 필요합니다.',
  COMMON_CSRF_INVALID: '보안 확인에 실패했습니다. 페이지를 새로고침한 뒤 다시 시도해 주세요.',
  COMMON_TOO_MANY_REQUESTS: '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.',
  COMMON_SERVICE_UNAVAILABLE: '서비스가 일시적으로 원활하지 않습니다. 잠시 후 다시 시도해 주세요.',
  COMMON_BAD_GATEWAY: '서비스가 일시적으로 원활하지 않습니다. 잠시 후 다시 시도해 주세요.',
  COMMON_GATEWAY_TIMEOUT: '응답이 늦어지고 있습니다. 잠시 후 다시 시도해 주세요.',
}

/** 화면에 보여 줄 오류 문구. */
export function toUserMessage(error: unknown, fallback = '요청을 처리하지 못했습니다.'): string {
  if (isApiError(error)) {
    return MESSAGES[error.code] ?? (error.message || fallback)
  }
  return fallback
}
