import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { isApiError, toUserMessage } from '../api'
import { fetchTerms, signup, type TermItem } from '../features/auth/api'
import { safeRedirectPath } from '../features/auth/redirect'

const termKey = (term: TermItem) => `${term.termsCode}:${term.termsVersion}`

export function SignupPage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const redirect = safeRedirectPath(searchParams.get('redirect'))

  const [terms, setTerms] = useState<TermItem[] | null>(null)
  const [termsError, setTermsError] = useState<string | null>(null)
  const [agreed, setAgreed] = useState<Record<string, boolean>>({})
  const [form, setForm] = useState({ loginEmail: '', password: '', realName: '', phone: '' })
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    let active = true
    fetchTerms()
      .then((items) => active && setTerms(items))
      .catch((cause) => active && setTermsError(toUserMessage(cause, '약관을 불러오지 못했습니다.')))
    return () => {
      active = false
    }
  }, [])

  const requiredAgreed = (terms ?? []).filter((t) => t.isRequired).every((t) => agreed[termKey(t)])

  function update(field: keyof typeof form, value: string) {
    setForm((prev) => ({ ...prev, [field]: value }))
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (!terms) {
      return
    }
    setSubmitting(true)
    setError(null)
    setFieldErrors({})
    try {
      await signup({
        ...form,
        consents: terms
          .filter((t) => agreed[termKey(t)])
          .map((t) => ({ termsCode: t.termsCode, termsVersion: t.termsVersion })),
      })
      const params = new URLSearchParams({ signedUp: '1', redirect })
      navigate(`/login?${params.toString()}`, { replace: true })
    } catch (cause) {
      if (isApiError(cause)) {
        setFieldErrors(cause.fieldMessages())
      }
      setError(toUserMessage(cause, '가입하지 못했습니다.'))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section>
      <h1>회원가입</h1>
      <form className="form" onSubmit={handleSubmit} noValidate>
        <label>
          이메일
          <input
            type="email"
            autoComplete="username"
            value={form.loginEmail}
            onChange={(e) => update('loginEmail', e.target.value)}
          />
          {fieldErrors.loginEmail && <span className="field-error">{fieldErrors.loginEmail}</span>}
        </label>
        <label>
          비밀번호
          <input
            type="password"
            autoComplete="new-password"
            value={form.password}
            onChange={(e) => update('password', e.target.value)}
          />
          <span className="hint">공백 없이 영문·숫자·특수문자를 모두 포함해 10~72자</span>
          {fieldErrors.password && <span className="field-error">{fieldErrors.password}</span>}
        </label>
        <label>
          성함
          <input
            autoComplete="name"
            value={form.realName}
            onChange={(e) => update('realName', e.target.value)}
          />
          {fieldErrors.realName && <span className="field-error">{fieldErrors.realName}</span>}
        </label>
        <label>
          휴대폰 번호
          <input
            type="tel"
            autoComplete="tel"
            placeholder="010-1234-5678"
            value={form.phone}
            onChange={(e) => update('phone', e.target.value)}
          />
          {fieldErrors.phone && <span className="field-error">{fieldErrors.phone}</span>}
        </label>

        <fieldset className="terms">
          <legend>약관 동의</legend>
          {termsError && <p className="error">{termsError}</p>}
          {!terms && !termsError && <p>약관을 불러오는 중입니다…</p>}
          {terms?.map((term) => (
            <div key={termKey(term)} className="term">
              <label className="inline">
                <input
                  type="checkbox"
                  checked={agreed[termKey(term)] ?? false}
                  onChange={(e) => setAgreed((prev) => ({ ...prev, [termKey(term)]: e.target.checked }))}
                />
                {term.isRequired ? '[필수] ' : '[선택] '}
                {term.title}
              </label>
              <details>
                <summary>내용 보기</summary>
                <pre className="terms-content">{term.content}</pre>
              </details>
            </div>
          ))}
        </fieldset>

        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        <button type="submit" disabled={submitting || !terms || !requiredAgreed}>
          {submitting ? '가입 중…' : '가입하기'}
        </button>
      </form>
      <p>
        이미 계정이 있으신가요? <Link to={`/login?redirect=${encodeURIComponent(redirect)}`}>로그인</Link>
      </p>
    </section>
  )
}
