# TravelPay 프런트엔드

Vite + React + TypeScript. 모든 API는 Gateway(`/api/v1/**`)를 거쳐 호출합니다.

## 실행

Node 20.19 이상이 필요합니다.

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173
npm run build      # 타입 검사 + 빌드
```

### Gateway 설정

개발 서버는 `/api` 요청을 Gateway(기본 `http://127.0.0.1:8080`)로 넘깁니다. 주소를 바꾸려면 `.env.example`을 `.env.local`로 복사해 `API_PROXY_TARGET`을 고칩니다.

프록시는 브라우저의 `Origin: http://localhost:5173`을 그대로 넘깁니다. Gateway는 목록에 없는 출처의 요청을 `403 COMMON_FORBIDDEN`으로 거절하므로, Gateway를 띄울 때 아래 환경 변수를 지정합니다.

```text
GATEWAY_CORS_ALLOWED_ORIGINS=http://localhost:5173
```

`gradlew bootRun`을 Gradle 데몬으로 실행하면 터미널 환경 변수가 전달되지 않을 수 있습니다. IntelliJ 실행 구성의 Environment variables에 넣거나 `--no-daemon`으로 실행하세요.

## 폴더 구조

```text
src/
  main.tsx            진입점 (AuthProvider + RouterProvider)
  router.tsx          경로 정의. 로그인이 필요한 화면은 RequireAuth 아래에 둔다
  api/                공통 요청 함수
  features/auth/      로그인 상태, 인증 API, 로그인 필요 경로 보호
  components/         공통 레이아웃
  pages/              화면 단위 컴포넌트
```

### 공통 요청 함수 (`src/api`)

- `apiRequest(path, options)`: 성공이면 `data`만 돌려주고, 실패면 `ApiError(status, code, message, details)`를 던진다. 204는 `undefined`
- `apiRequestWithStatus`: HTTP 상태가 필요할 때(예: 송금 201/202 구분)
- 세션 쿠키: 모든 요청에 `credentials: 'include'`
- CSRF: `GET /api/v1/auth/csrf`로 받은 토큰을 메모리에 두고, POST·PUT·PATCH·DELETE 전에 없으면 받아서 서버가 알려 준 헤더 이름(`headerName`)으로 붙인다. `403 COMMON_CSRF_INVALID`면 토큰을 새로 받아 한 번만 다시 보낸다(CSRF 검사는 업무 처리 전에 거절하므로 안전)
- 로그인·로그아웃 뒤에는 세션 ID가 바뀌므로 토큰을 버리고 다시 받는다(`AuthContext`가 처리)
- `Idempotency-Key`: `options.idempotencyKey`로 넘긴다. 재시도할 때는 같은 값을 쓴다
- 401을 받으면 로그인 상태를 지우고, 보호된 화면은 로그인 화면(`/login?redirect=원래경로`)으로 보낸다. `redirect`는 앱 내부 경로만 허용한다
- 화면 문구: `toUserMessage(error)` — 공통 장애 코드만 바꾸고 나머지는 서버의 한국어 `error.message`를 쓴다

### 화면 (3단계까지)

| 경로 | 화면 | 로그인 |
|---|---|---|
| `/` | 홈 | 불필요 |
| `/signup` | 회원가입(약관 조회·동의, 필드별 오류 표시) → 로그인 화면으로 이동 | 불필요 |
| `/login` | 로그인 → `redirect` 경로로 이동 | 불필요 |
| `/me` | 내 정보 | 필요 |

이후 단계에서 추가: `features/transfer`, `features/wallet`, `lib/`(금액 최소 단위 변환, 멱등성 키 생성)
