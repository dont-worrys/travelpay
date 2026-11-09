import { Link } from 'react-router'

export function NotFoundPage() {
  return (
    <section>
      <h1>페이지를 찾을 수 없습니다</h1>
      <Link to="/">처음으로</Link>
    </section>
  )
}
