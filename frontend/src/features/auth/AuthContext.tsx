import { createContext, useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { clearCsrfToken, ensureCsrfToken, isApiError, setUnauthorizedListener } from '../../api'
import * as authApi from './api'
import type { LoginInput, MyInfo } from './api'

export type AuthState =
  | { status: 'loading'; me: null }
  | { status: 'authenticated'; me: MyInfo }
  | { status: 'anonymous'; me: null }

export interface AuthContextValue {
  state: AuthState
  login: (input: LoginInput) => Promise<void>
  logout: () => Promise<void>
  /** 내 정보를 다시 읽는다. 401이면 로그아웃 상태가 된다. */
  refresh: () => Promise<void>
}

export const AuthContext = createContext<AuthContextValue | null>(null)

const ANONYMOUS: AuthState = { status: 'anonymous', me: null }

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: 'loading', me: null })

  const loadMe = useCallback(async (signal?: AbortSignal) => {
    try {
      const me = await authApi.fetchMe(signal)
      setState({ status: 'authenticated', me })
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        return
      }
      if (isApiError(error) && (error.status === 401 || error.status === 403)) {
        // 세션 없음(401), 정지·종료 회원(403)은 로그인 안 된 상태로 본다.
        setState(ANONYMOUS)
        return
      }
      throw error
    }
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    loadMe(controller.signal).catch(() => setState(ANONYMOUS))
    return () => controller.abort()
  }, [loadMe])

  useEffect(() => {
    // 어떤 요청이든 401이면 세션이 끝난 것이다. 보호된 화면은 RequireAuth가 로그인으로 보낸다.
    setUnauthorizedListener(() => {
      clearCsrfToken()
      setState(ANONYMOUS)
    })
    return () => setUnauthorizedListener(null)
  }, [])

  const login = useCallback(
    async (input: LoginInput) => {
      await authApi.login(input)
      // 로그인하면 세션 ID가 바뀌고 이전 CSRF 토큰은 버려진다. 다음 변경 요청 전에 새로 받는다.
      clearCsrfToken()
      await ensureCsrfToken()
      await loadMe()
    },
    [loadMe],
  )

  const logout = useCallback(async () => {
    try {
      await authApi.logout()
    } finally {
      clearCsrfToken()
      setState(ANONYMOUS)
    }
  }, [])

  const refresh = useCallback(() => loadMe(), [loadMe])

  const value = useMemo(() => ({ state, login, logout, refresh }), [state, login, logout, refresh])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
