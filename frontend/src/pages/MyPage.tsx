import { useAuth } from '../features/auth/useAuth'

/** 로그인 확인용 내 정보 화면. 송금·지갑 화면이 생기면 메뉴에서 이동한다. */
export function MyPage() {
  const { state } = useAuth()
  if (state.status !== 'authenticated') {
    return null
  }
  const { me } = state
  return (
    <section>
      <h1>내 정보</h1>
      <dl className="info">
        <dt>이름</dt>
        <dd>{me.realName}</dd>
        <dt>이메일</dt>
        <dd>{me.loginEmail ?? '-'}</dd>
        <dt>휴대폰</dt>
        <dd>{me.phone}</dd>
        <dt>회원 ID</dt>
        <dd>
          <code>{me.userId}</code>
        </dd>
      </dl>
    </section>
  )
}
