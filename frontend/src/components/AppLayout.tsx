import { useState } from 'react'
import { Link, Outlet, useNavigate } from 'react-router'
import { useAuth } from '../features/auth/useAuth'

export function AppLayout() {
  const { state, logout } = useAuth()
  const navigate = useNavigate()
  const [loggingOut, setLoggingOut] = useState(false)

  async function handleLogout() {
    setLoggingOut(true)
    try {
      await logout()
    } catch {
      // 로그아웃 요청이 실패해도 화면은 로그아웃 상태로 둔다(세션은 만료 시간에 끝난다).
    } finally {
      setLoggingOut(false)
      navigate('/', { replace: true })
    }
  }

  return (
    <div className="app">
      <header className="app-header">
        <Link to="/" className="app-title">
          TravelPay
        </Link>
        <nav className="app-nav">
          {state.status === 'authenticated' && (
            <>
              <Link to="/me">{state.me.realName}님</Link>
              <button type="button" className="link-button" onClick={handleLogout} disabled={loggingOut}>
                로그아웃
              </button>
            </>
          )}
          {state.status === 'anonymous' && (
            <>
              <Link to="/login">로그인</Link>
              <Link to="/signup">회원가입</Link>
            </>
          )}
        </nav>
      </header>
      <main className="app-main">
        <Outlet />
      </main>
    </div>
  )
}
