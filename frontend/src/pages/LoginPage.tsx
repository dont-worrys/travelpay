import { useState, type FormEvent } from 'react'
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router'
import { toUserMessage } from '../api'
import { safeRedirectPath } from '../features/auth/redirect'
import { useAuth } from '../features/auth/useAuth'

export function LoginPage() {
  const { state, login } = useAuth()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const redirect = safeRedirectPath(searchParams.get('redirect'))
  const signedUp = searchParams.get('signedUp') === '1'

  const [loginEmail, setLoginEmail] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  if (state.status === 'authenticated') {
    return <Navigate to={redirect} replace />
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      await login({ loginEmail, password })
      navigate(redirect, { replace: true })
    } catch (cause) {
      setError(toUserMessage(cause, '로그인하지 못했습니다.'))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section>
      <h1>로그인</h1>
      {signedUp && <p className="notice">가입이 완료되었습니다. 로그인해 주세요.</p>}
      <form className="form" onSubmit={handleSubmit} noValidate>
        <label>
          이메일
          <input
            type="email"
            autoComplete="username"
            value={loginEmail}
            onChange={(e) => setLoginEmail(e.target.value)}
            required
          />
        </label>
        <label>
          비밀번호
          <input
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </label>
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        <button type="submit" disabled={submitting || !loginEmail || !password}>
          {submitting ? '로그인 중…' : '로그인'}
        </button>
      </form>
      <p>
        계정이 없으신가요? <Link to={`/signup?redirect=${encodeURIComponent(redirect)}`}>회원가입</Link>
      </p>
    </section>
  )
}
