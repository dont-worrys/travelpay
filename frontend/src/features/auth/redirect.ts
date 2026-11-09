/**
 * 로그인 후 돌아갈 경로. 외부 주소로 보내는 열린 리다이렉트를 막기 위해
 * `/`로 시작하는 앱 내부 경로만 허용한다(`//host`, `/\host` 거절).
 */
export function safeRedirectPath(value: string | null | undefined, fallback = '/'): string {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\')) {
    return fallback
  }
  return value
}

/** 현재 위치를 로그인 화면의 `redirect` 쿼리로 만든다. */
export function loginPathFor(pathname: string, search: string): string {
  const target = `${pathname}${search}`
  return `/login?redirect=${encodeURIComponent(target)}`
}
