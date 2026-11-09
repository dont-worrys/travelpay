import { apiRequest } from '../../api'

export type UserRole = 'USER' | 'MASTER'
export type UserStatus = 'ACTIVE' | 'SUSPENDED' | 'CLOSED'

/** `GET /api/v1/users/me` 응답. 개인정보이므로 로그·콘솔에 남기지 않는다. */
export interface MyInfo {
  userId: string
  realName: string
  phone: string
  loginEmail: string | null
  status: UserStatus
  role: UserRole
  loginMethods: string[]
  createdAt: string
}

export interface TermItem {
  termsCode: string
  termsVersion: string
  title: string
  content: string
  isRequired: boolean
  effectiveAt: string
}

export interface SignupInput {
  loginEmail: string
  password: string
  realName: string
  phone: string
  consents: { termsCode: string; termsVersion: string }[]
}

export interface SignupResult {
  userId: string
  status: UserStatus
  loginRequired: boolean
}

export interface LoginInput {
  loginEmail: string
  password: string
}

export function fetchTerms(): Promise<TermItem[]> {
  return apiRequest<{ terms: TermItem[] }>('/api/v1/terms').then((data) => data.terms)
}

export function signup(input: SignupInput): Promise<SignupResult> {
  return apiRequest<SignupResult>('/api/v1/users', { method: 'POST', body: input })
}

/** 성공하면 서버가 세션 ID를 바꾸므로 호출한 쪽에서 CSRF 토큰을 버려야 한다. */
export function login(input: LoginInput): Promise<{ userId: string; role: UserRole }> {
  return apiRequest('/api/v1/auth/login', { method: 'POST', body: input })
}

export function logout(): Promise<void> {
  return apiRequest<void>('/api/v1/auth/logout', { method: 'POST' })
}

export function fetchMe(signal?: AbortSignal): Promise<MyInfo> {
  return apiRequest<MyInfo>('/api/v1/users/me', { signal })
}
