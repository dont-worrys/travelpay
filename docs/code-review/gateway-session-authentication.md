# Gateway 세션 인증과 경계 개선

Gateway는 세션 저장소를 공유하는 대신 User의 내부 검증 API를 호출합니다. User가 현재 회원을 확인하면 Gateway가 회원 헤더를 작성하고, 업무 서비스는 공통 MVC 지원으로 그 값을 사용합니다. User 자신의 API는 SecurityContext 기반 Provider를 사용합니다.

## 검토 항목과 반영 결과

아래 9개는 코드·설정으로 반영한 항목입니다. 실제 DNS·TLS·ALB·보안 그룹 배포는 [실행·배포 가이드](../guide/gateway.md)에 따로 구분했습니다.

| 항목 | 이전 문제·미정 사항 | 반영 방식 |
|---|---|---|
| IP 신뢰 | 전달 IP가 연결 주소를 바꿀 수 있음 | 자동 전달 헤더 해석 끔, 신뢰 연결의 CF-Connecting-IP만 사용 |
| Connection | 새 내부 헤더가 전송 직전 삭제 가능 | 내부 헤더 이름을 hop-by-hop 제거 목록에서 정리 |
| 관리 엔드포인트 | API 포트에서 지표 접근 가능 | API·관리 포트 분리, 로컬·배포 YAML 반영 |
| 큰 쿠키 | 내부 헤더 추가 후 User 수신 한도 초과 | User 수신 한도 16KiB, 로컬·배포 일치 |
| 외부 추적 정보 | 부모 trace·baggage 수용 정책 미정 | Gateway에서 새 trace, 외부 baggage 차단 |
| 405 | 허용 메서드 안내 누락 | Spring 예외와 기존 처리기로 Allow 보존 |
| CORS | 프런트 출처·사전 요청 규칙 미정 | 두 HTTPS 프런트 출처, 환경변수·정상 preflight 지원 |
| 역할값 파싱 | 숫자 값이 enum 순서로 변환 가능 | 검증 클라이언트의 JSON 자동 형 변환 제한 |
| 오류 캐시 | 인증 거절·Gateway 오류 저장 금지 누락 | 두 경로의 no-store, CORS 거절도 동일 처리 |

추가로 세션 검증의 연결 2초·읽기 3초·전체 5초를 독립 설정으로 관리합니다. 전체 대기는 `verify()` 결과를 기다리는 부분에 적용해, 검증이 끝난 후의 업무 호출을 인증 장애로 오인하지 않게 했습니다.

## 파일의 책임

| 묶음 | 파일 | 역할 |
|---|---|---|
| 경로 | `NormalizedPathFilter` | 우회 해석될 경로를 먼저 거절 |
| 헤더 | `InternalHeaderResetFilter` | 외부 내부용·추적 헤더 정리, 요청 ID·IP 작성 |
| IP | `ClientIpResolver`, `ClientIpProperties` | 신뢰 연결 대역과 유효한 IP 검사 |
| 인증 | `PublicEndpoints` | 메서드·경로별 공개 목록 |
| 인증 | `SessionAuthenticationFilter` | I01 결과로 전달·거절, 전체 대기 상한 |
| 호출 | `SessionVerificationClient` | 필요한 헤더만 전달, 응답 해석·503 구분 |
| 호출 | `SessionVerificationConfiguration`, `SessionVerificationProperties` | WebClient·관측·제한시간 설정 |
| 오류 | `GatewayErrorHandler` | 공통 JSON·HTTP 상태·프레임워크 헤더·no-store |
| CORS | `GatewayCorsConfiguration` | 출처 설정 검사, 프레임워크 거절을 공통 오류에 연결 |
| 환경 | 로컬·배포 YAML, `.env.example` | 라우트·추적·CORS·관리 포트·실행 변수 |

## 왜 이렇게 구성했나

**외부 입력과 내부 인증 결과를 구분합니다.** 브라우저가 보낸 User-Id·User-Role을 지운 뒤 User가 확인한 결과로 다시 설정합니다. Request-Id와 Client-Ip도 Gateway가 정합니다. 값의 형식 검사와 호출자 인증은 서로 다른 책임이므로, 내부 서비스 포트의 접근 제한은 배포에서 적용해야 합니다.

**부모 trace는 관측 시작 전에 차단합니다.** GlobalFilter가 실행될 때는 부모 trace가 이미 추출됐을 수 있습니다. Gateway의 `consume: []`, `produce: [W3C]`, baggage 비활성화와 전달 헤더 정리를 함께 사용합니다. 내부 호출의 traceId는 같고 spanId는 호출마다 다릅니다. 프런트 trace의 부모 관계를 상속하지 않는 것이 현재 정책입니다.

**CORS는 프레임워크가 검사합니다.** 별도 OPTIONS 허용 필터나 포트별 API 차단 필터를 만들지 않았습니다. Gateway globalcors가 Origin·메서드·헤더를 검사하며, HandlerMapping의 CorsProcessor 확장점에서 거절만 기존 오류 처리기로 전달합니다. 정상 preflight는 본문 없이 끝나고, 실제 보호 API는 계속 세션·CSRF 검증을 받습니다.

**출처는 정확한 주소로 제한합니다.** 운영 기본값은 HTTPS 루트·www 주소이고, localhost 포트는 실행 환경변수로 정합니다. 와일드카드·패턴·경로·인증 정보가 들어간 출처는 시작 때 거절합니다. 같은 사이트의 프런트와 API이므로 SESSION의 host-only·HttpOnly·SameSite=Lax 정책을 유지할 수 있습니다.

**제한시간은 실패 구간에 맞춥니다.** 네트워크 읽기 제한만으로는 응답이 조금씩 오는 상황을 묶어 제한하지 못합니다. 전체 Mono에 제한을 적용하고, 시간 초과를 503으로 바꾸며 업무 서비스로 전달하지 않습니다. 연결·읽기·전체 값은 각각 환경변수로 조정합니다.

## 검증 범위와 배포 경계

Gateway HTTP 시험은 실제 Gateway 서버와 User·업무 서비스 대역 사이의 상태·본문·헤더·호출 여부를 확인합니다. User 시험은 PostgreSQL·Redis의 실제 동작을 사용합니다. 전체 연결 시험에서는 실제 Gateway·User와 PostgreSQL·Redis를 연결하고 업무 서비스만 대역을 사용합니다.

로컬 연결 시험은 실제 브라우저의 DNS·HTTPS·SameSite 동작이나 AWS 보안 그룹을 대신하지 않습니다. 배포할 때는 팀 도메인, 인증서, Cloudflare·ALB 설정, 내부망 접근, 실제 서비스 등록 주소를 연결한 뒤 브라우저 로그인·CSRF·로그아웃을 확인해야 합니다. 구체적인 실행 순서와 변수는 [Gateway 가이드](../guide/gateway.md)에 있습니다.
