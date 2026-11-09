import type { FieldErrorDetail } from './types'

/** 서버에 닿지 못했거나(연결 실패) 응답을 해석할 수 없을 때 쓰는 클라이언트 쪽 오류 코드. */
export const CLIENT_ERROR_CODES = {
  NETWORK: 'CLIENT_NETWORK_ERROR',
  UNEXPECTED_RESPONSE: 'CLIENT_UNEXPECTED_RESPONSE',
} as const

/**
 * API 실패를 나타낸다. 서버가 보낸 공통 오류면 `code`·`message`·`details`를 그대로 담고,
 * 서버에 닿지 못했으면 `status`가 0이다.
 */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly details: FieldErrorDetail[]

  constructor(status: number, code: string, message: string, details: FieldErrorDetail[] = []) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.details = details
  }

  /** 필드 이름별 첫 오류 메시지. 폼 입력 옆에 표시할 때 쓴다. */
  fieldMessages(): Record<string, string> {
    const result: Record<string, string> = {}
    for (const detail of this.details) {
      if (!(detail.field in result)) {
        result[detail.field] = detail.message
      }
    }
    return result
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError
}
