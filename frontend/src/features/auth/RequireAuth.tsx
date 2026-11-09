import { Navigate, Outlet, useLocation } from 'react-router'
import { loginPathFor } from './redirect'
import { useAuth } from './useAuth'

/** 로그인한 회원만 하위 경로를 볼 수 있게 한다. 아니면 지금 주소를 기억하고 로그인 화면으로 보낸다. */
export function RequireAuth() {
  const { state } = useAuth()
  const location = useLocation()

  if (state.status === 'loading') {
    return <p>로그인 상태를 확인하는 중입니다…</p>
  }
  if (state.status === 'anonymous') {
    return <Navigate to={loginPathFor(location.pathname, location.search)} replace />
  }
  return <Outlet />
}
