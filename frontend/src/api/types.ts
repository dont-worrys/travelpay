/** 서버 공통 응답(common `ApiResponse`). `message` 필드는 없다. */
export interface ApiEnvelope<T> {
  success: boolean
  data: T | null
  error: ApiErrorBody | null
}

/** 서버 공통 오류(common `ErrorResponse`). */
export interface ApiErrorBody {
  code: string
  message: string
  details: FieldErrorDetail[]
}

export interface FieldErrorDetail {
  field: string
  message: string
}

/** 서버 공통 페이지 응답(common `PageResponse`). `page`는 0부터 시작한다. */
export interface PageResponse<T> {
  items: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
