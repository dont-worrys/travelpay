# 로컬 환경 설정 가이드

프로젝트 루트에서 인프라를 Docker Compose로 실행하고, 필요한 애플리케이션은 IntelliJ 또는 Gradle로 실행합니다. 이 문서는 공통 로컬 환경·환경 변수 주입·포트와 실행 순서를 설명합니다.

필요한 도구는 JDK 21과 Docker Compose입니다. Java·Spring Boot·Gradle 버전은 프로젝트의 Gradle 빌드·Wrapper 설정을 기준으로 합니다.

## 구성 선택과 이유

- **인프라는 컨테이너, 앱은 필요한 것만 직접 실행합니다.** 저장소 환경을 함께 준비하면서 IDE에서 담당 코드를 실행·디버깅하기 위한 기본 구성입니다.
- **앱마다 DB 계정과 스키마를 분리합니다.** 자기 데이터를 소유하고 다른 서비스 데이터는 API로 요청하도록 경계를 둡니다.
- **설정은 파일 선택과 환경 변수로 조합합니다.** 같은 Application 클래스를 사용하고, 로컬·배포 주소를 맞추려고 Java 코드를 바꾸지 않습니다.
- **DB 구조는 Flyway로 관리하고 JPA는 검사합니다.** 개발자마다 실행 시점의 자동 테이블 변경으로 구조가 달라지는 것을 줄입니다.

## 개인 환경 파일 준비

처음 설정할 때 프로젝트 루트의 `.env.example`을 `.env`로 복사합니다. 이미 개인 `.env`가 있으면 그 값을 유지합니다.

```bash
cp -n .env.example .env
```

DB·Redis 비밀번호는 개인 값으로 바꾸고 실제 `.env`는 저장소에 올리지 않습니다. `.gitignore`가 `.env`를 제외하며 `.env.example`만 공유합니다. DB URL은 `jdbc:postgresql://...` 형식이어야 합니다.

`.env`가 있는 것과 애플리케이션에 환경 변수가 전달된 것은 서로 다른 단계입니다.

```text
Docker Compose
  → .env의 값을 읽어 compose.yaml의 ${...}를 치환
  → environment·command에 사용한 값을 해당 인프라 컨테이너에 반영

IntelliJ 또는 터미널
  → .env를 읽어 실행할 Java 프로세스의 환경 변수로 전달
  → application.yml의 ${USER_DB_URL} 같은 값을 해석
  → 로컬 포트로 인프라와 다른 애플리케이션에 연결
```

루트 `compose.yaml`에는 PostgreSQL·세션 Redis·캐시 Redis·Kafka·Kafka UI가 정의되어 있습니다. 애플리케이션 실행은 아래 IntelliJ·Gradle 절차를 사용합니다. 모듈 전용 부하 테스트의 Compose 구성은 별도 환경입니다.

## 필요한 인프라 실행

회원·인증만 확인할 때는 다음 두 개를 실행합니다.

```bash
docker compose up -d postgres redis-session
```

충전을 확인할 때는 Kafka도 실행합니다.

```bash
docker compose up -d postgres redis-session kafka
docker compose ps
```

환전을 확인할 때는 캐시 Redis도 **반드시** 실행합니다(견적 저장소). 환전은 충전한 지갑으로 확인하므로 충전에 필요한 인프라와 함께 띄웁니다.

```bash
docker compose up -d postgres redis-session kafka redis-cache
docker compose ps
```

| 인프라 | 호스트의 기본 주소 | 컨테이너 내부 주소 |
|---|---|---|
| PostgreSQL | `localhost:5432` | `postgres:5432` |
| 세션 Redis | `localhost:16379` | `redis-session:6379` |
| 캐시 Redis·환전 | `localhost:16380` | `redis-cache:6379` |
| Kafka | `localhost:9092` | `kafka:29092` |
| Kafka UI·선택 실행 | `localhost:8989` | `kafka-ui:8080` |

Kafka UI가 필요하면 `docker compose up -d kafka-ui`로 추가합니다. 포트를 바꿨다면 `.env`와 해당 앱의 연결 설정도 함께 맞춥니다. 인프라의 호스트 포트는 로컬 루프백에 바인딩됩니다.

캐시 Redis는 Exchange가 사용합니다. 연결 정보는 `.env`의 `REDIS_CACHE_HOST`·`REDIS_CACHE_PORT`·`REDIS_CACHE_PASSWORD`를 씁니다. 두 가지 용도가 있고, 꺼졌을 때 동작이 다릅니다.

- 최신 환율 캐시: 환율 조회와 견적 발급이 최신 환율을 먼저 읽습니다. 꺼져 있으면 DB에서 읽어 정상 동작하고, 1분에 한 번 경고 로그가 남습니다.
- 견적 저장소: 견적은 캐시 Redis에만 저장합니다(#223). **꺼져 있으면 견적 발급·조회·환전 신청이 503 `EXCHANGE_QUOTE_STORE_UNAVAILABLE`입니다.** 환율 조회는 정상입니다.

캐시 Redis는 메모리가 차도 키를 지우지 않는 `noeviction` 정책입니다(`infra/redis/cache/redis.conf`). 자세한 내용은 [환전 서비스 가이드](exchange-service.md)를 봅니다.

## IntelliJ에서 애플리케이션 실행

실행할 애플리케이션마다 최초 한 번 설정합니다. 개인 IDE 설정은 저장소에서 제외되므로 팀원도 각자 연결합니다.

1. 프로젝트의 Gradle 설정을 불러오고 JDK 21을 선택합니다.
2. 상단 실행 목록의 **Edit Configurations**에서 해당 `...Application`을 선택합니다.
3. 작업 디렉터리는 프로젝트 루트로 두고, **Environment variables**의 환경 파일 선택 기능에서 프로젝트 루트의 `.env`를 지정합니다.
4. 로컬 설정을 명시하려면 **Program arguments**에 다음을 넣습니다.

```text
--spring.config.name=application --spring.profiles.active=default
```

5. Run으로 실행하고 `Started ...Application` 및 해당 포트의 기동 완료를 확인합니다.

같은 `.env`를 사용해도 각 앱은 자기 `application.yml`의 포트·DB 변수·클라이언트 설정을 읽습니다. 별도의 `Application-local` 클래스를 만들 필요는 없습니다. 환경 파일 선택 기능을 사용할 수 없으면 같은 변수들을 실행 설정에 직접 전달하거나 아래 Gradle 방식을 사용합니다.

```text
Run
→ 선택한 .env를 Java 프로세스 환경에 전달
→ 해당 모듈의 application.yml 사용
→ ${...} 값 해석
→ 해당 모듈의 로컬 포트로 실행
```

## 터미널에서 Gradle로 실행

프로젝트 루트에서 환경 파일을 읽고 서비스를 실행합니다. 새 터미널을 열면 그 터미널에서도 다시 불러옵니다.

```bash
set -a
source .env
set +a

./gradlew :user-service:bootRun --args='--spring.config.name=application --spring.profiles.active=default'
```

위 환경 파일 로딩은 Bash 기준입니다. `.env`는 프로젝트에서 관리하는 신뢰할 수 있는 파일을 사용합니다. 다른 앱은 Gradle 작업의 모듈 이름을 바꿉니다. JDK 선택이 필요하면 `JAVA_HOME`에 개인 JDK 21 설치 경로를 지정합니다.

빌드와 실행은 구분합니다. 다음 빌드는 User 테스트를 포함하며 HTTP 서버를 계속 실행해 두는 명령이 아닙니다.

```bash
./gradlew --no-daemon --max-workers=1 :user-service:build
```

로컬 앱은 `application.yml`, 배포용 독립 설정은 `SPRING_CONFIG_NAME=application-prod`·`SPRING_PROFILES_ACTIVE=prod`로 선택합니다. 로컬에서 배포용 설정을 기본값으로 활성화하지 않습니다. 실행 프로세스의 환경이 바뀌면 앱을 다시 실행해 적용합니다.

## 애플리케이션 포트와 실행 순서

| 애플리케이션 | 로컬 포트 | 기본 준비 사항 |
|---|---:|---|
| Eureka | 8761 | 서비스 등록·조회에 사용할 서버 |
| User | 8081 | PostgreSQL·세션 Redis |
| Ledger | 8085 | PostgreSQL |
| Mock Bank | 8090 | PostgreSQL, 직접 URL로 호출하며 Eureka에 등록하지 않음 |
| Wallet | 8082 | PostgreSQL·Kafka, 실행에 필요한 암호화 키 |
| Payment | 8083 | PostgreSQL, 기능별 User·Wallet·Ledger 연동 |
| Exchange | 8084 | PostgreSQL·캐시 Redis(견적 저장소라 필수), 환율 수집 모드와 기능별 연동 |
| Gateway | 8080 | Eureka·User 및 호출할 업무 서비스 |

Gateway 관리 포트는 별도 `18080`이며 헬스체크 경로는 `/actuator/health`입니다. 표는 기본 설정이며 실제 값은 각 모듈의 실행 설정과 인자를 기준으로 합니다.

회원·인증 확인은 **인프라 → Eureka → User → Gateway**, 충전 확인은 **인프라 → Eureka → User → Ledger → Mock Bank → Wallet** 순서로 준비하면 의존 서비스를 먼저 확인하기 쉽습니다. 브라우저 요청은 Gateway까지 실행한 뒤 보냅니다. 이 순서는 권장 준비 순서이며 앱 사이에 자동 실행을 연결한 설정은 아닙니다. 필요한 앱만 실행합니다.

서비스별 추가 설정도 확인합니다.

- Wallet의 `WALLET_ACCOUNT_NUMBER_KEY`는 Base64로 표현한 32바이트 키이며, `.env.example`의 빈 값을 준비하지 않으면 기동할 수 없습니다. 개인 키 생성 방법은 `.env.example`의 설명을 따릅니다.
- 로컬 충전에서 Mock Bank를 호출할 때는 `MOCK_BANK_URL=http://localhost:8090`을 Wallet 실행 환경에 전달합니다. Mock Bank는 Eureka에 등록하지 않으므로 직접 주소가 필요합니다.
- Exchange는 `.env.example`의 `EXCHANGE_RATE_MODE`·`KOREAEXIM_API_KEY` 설명을 확인합니다. 로컬의 mock 환율 수집 모드는 API 키 없이 사용할 수 있으며 운영에서는 사용하지 않습니다.
- 프런트를 연결할 때는 Gateway의 `GATEWAY_CORS_ALLOWED_ORIGINS`에 실제 프런트 출처를 지정합니다.

## 로컬 모니터링

앱은 기존처럼 IntelliJ에서 실행하고, Prometheus·Loki·Alloy·Grafana는 별도 Compose 프로젝트로 실행합니다. 앱용 환경 변수나 실행 인자를 추가할 필요는 없습니다. **IntelliJ 작업 디렉터리는 프로젝트 루트**로 둡니다.

개인 `.env`의 `GRAFANA_ADMIN_PASSWORD`는 Grafana 최초 관리자 비밀번호입니다. 기존 `.env`에 이 항목이 없다면 개인 비밀번호로 추가합니다. 이 값은 애플리케이션의 실행 설정이 아니라 모니터링 Compose가 사용합니다. 이미 Grafana 데이터 볼륨이 있다면 환경 변수 변경만으로 기존 비밀번호가 바뀌지는 않습니다.

프로젝트 루트에서 실행합니다. 로그 폴더는 `.gitkeep`을 저장소에 포함해 체크아웃할 때 생성되므로 별도 폴더 생성 명령이 필요하지 않습니다. 실제 로그 파일은 Git에서 제외합니다. Compose는 폴더가 없을 때 root 소유로 자동 생성하지 않고 오류로 알립니다.

```bash
docker compose -f compose.monitoring.yaml up -d
docker compose -f compose.monitoring.yaml ps
```

그다음 필요한 앱을 IntelliJ에서 Run합니다. 이미 실행 중이었다면 YAML 변경을 반영하도록 해당 앱을 다시 실행합니다.

```text
메트릭: Prometheus → host.docker.internal:앱 포트/actuator/prometheus
로그: 앱의 JSON 파일 → Alloy → Loki
조회: Grafana → Prometheus·Loki
```

| 주소 | 용도 |
|---|---|
| `http://localhost:3000` | Grafana, 최초 계정 `admin`·개인 Grafana 비밀번호 |
| `http://localhost:9090/targets` | Prometheus 수집 대상과 오류 확인 |
| `http://localhost:12345` | Alloy 수집 구성 확인 |

### 메트릭 연결과 해석

앱의 로컬 YAML이 HTTP 히스토그램을 활성화합니다. Grafana의 `TRAVELPAY / TRAVELPAY \| Local Performance`에서 서비스 수집 상태, 요청 처리량·서버 장애율(5xx)·평균·p95·p99, 메모리 사용량(Heap)·CPU, DB 커넥션 풀 대기를 확인합니다. HTTP 성능 패널은 `/actuator`와 하위 경로를 제외합니다. 여기에는 업무 API 외에 내부 API 요청도 포함되며, 서비스별 수치를 합산해 외부 사용자 요청 수로 해석하지 않습니다.

실행하지 않은 앱은 빨간 상태 카드로 표시됩니다. 초록은 정상, 빨강은 연결 중단입니다. 앱 종료뿐 아니라 네트워크·수집 오류도 포함하며 중단돼도 서비스명은 유지합니다. 카드에 마우스를 올리면 상태 값을 확인할 수 있습니다. 앱이 실행 중이어도 첫 수집과 변화율 계산까지 시간이 필요합니다. 요청이 없으면 응답 시간이나 p95·p99가 비어 있을 수 있습니다. 이를 측정하려면 해당 앱의 API를 호출하고 수집 주기인 15초가 여러 번 지나도록 기다립니다.

응답 시간은 요청이 없는 구간의 값을 만들지 않습니다. 요청이 드물면 평균·p95·p99가 점이나 짧은 선으로 보일 수 있으며, 결측을 0ms로 바꾸거나 빈 구간을 임의로 연결하지 않습니다.

상단의 **메트릭 환경**은 모든 메트릭 패널에 적용됩니다. 기본값 `local`은 로컬 개발 앱, `load-test`는 별도로 준비한 전용 부하 시험 앱입니다. 기존 Wallet·Payment 부하 시험은 개발 앱을 재사용하므로 `local`에서 봅니다. 메트릭 환경은 한 번에 하나만 선택하며 `All`은 제공하지 않습니다. 카드·그래프의 환경 접두어는 생략합니다. 서비스·API·HTTP 상태의 `All`은 유지합니다. 환경 레이블을 붙이기 전의 표본과 이전 `dev` 기록은 삭제하지 않고 새 선택 목록에서 제외합니다. 갱신 뒤 수집된 값부터 확인하며, 기존 URL의 `var-environment=dev`는 `var-environment=local`로 바꿉니다.

`Service`는 메트릭과 로그에 적용되고, `API`·`HTTP 상태`는 위쪽 HTTP 처리량·오류율·응답 시간 패널에만 적용됩니다. 예를 들어 `local`·`user-service`·`/api/v1/terms`·`200`을 선택하면 약관 조회 성공 응답만 비교합니다. 여러 API·상태를 All로 선택하면 선택 범위의 요청이 합산되므로, 성공·거절 요청의 시간을 구분할 때는 상태를 따로 선택합니다. API 목록은 Spring이 기록하는 URI 템플릿이며, UUID가 들어간 원래 URL을 새 레이블로 만들지 않습니다. Gateway에서 URI가 `UNKNOWN`이면 아래의 라우트별 패널을 함께 봅니다.

환경·서비스·API를 바꾼 뒤 데이터가 없다면 이전 API·HTTP 상태 선택이 새 대상과 맞는지 확인하고, 두 필터를 All로 되돌린 뒤 다시 선택합니다. 메트릭 환경은 로그에 적용되지 않으며 현재 로그 패널은 개발 앱·개발 인프라 범위입니다.

HTTP 요청 처리량은 RPS입니다. 업무 완료 TPS나 DB의 SQL 실행 QPS와는 다른 지표입니다. TPS는 업무 완료·DB 트랜잭션 중 기준을 정한 뒤 계측하고, SQL QPS는 별도 DB 지표가 필요합니다. 현재 패널에 같은 HTTP 요청 수를 TPS·QPS로 중복 표시하지 않습니다.

### 병목 진단 패널

대시보드는 **서비스 → API 성능 → JVM 리소스 → DB 커넥션 풀 → 환율 캐시(Exchange) → 서비스 연동(Gateway) → 비동기 처리 → 로그** 순서로 묶었습니다. 각 구역 제목 왼쪽 화살표로 접고 펼칠 수 있습니다. 상태는 서비스명과 배경색 카드, 시간에 따른 변화는 시계열 그래프, 업무 지연·관리자 확인 건수는 현황표로 표시합니다.

세션 검증은 User가 Redis·회원 DB를 통해 수행합니다. Gateway 구역의 세션 API 지표는 Gateway에서 측정한 HTTP 호출 시간이며, 검증 로직 자체의 실행 시간과 구분합니다. DB 커넥션 풀 구역은 DB 서버 전체 통계가 아니라 앱의 HikariCP 지표입니다.

기존에 수집되는 지표로 다음을 확인합니다. 이 패널들은 `메트릭 환경`·`Service`를 적용하므로 위쪽 HTTP API·상태 선택과 독립적으로 저장소·연결 서비스의 상태를 비교할 수 있습니다.

| 패널 | 확인할 내용 |
|---|---|
| HikariCP 연결 수(사용/최대)·대기 스레드 수 | 풀이 상한에 닿았는지와 연결 대기가 생기는지 |
| HikariCP 평균 연결 획득 시간·타임아웃(회/초) | SQL 실행 전 연결을 빌리는 단계에서 지연·실패하는지 |
| HikariCP 평균 연결 점유 시간 | 연결을 얻은 뒤 반환할 때까지 사용한 시간. 연결 획득 시간과 구분하며, 반환된 연결의 풀 전체 표본으로 계산 |
| 환율 캐시 조회 결과 (Exchange) | Exchange 전용 구역에서 통화 키별 초당 조회수 확인. 유효한 캐시 반환·캐시 없음/값 해석 불가·Redis 읽기 실패를 구분 |
| GC 최대 정지 시간 | GC는 더 이상 사용하지 않는 자바 객체의 메모리를 정리하는 작업. 일부 단계의 실행 정지가 응답 지연 시점과 겹치는지 확인하며, 최근 관측 창의 값임 |
| User 세션 호출 시간 | Gateway가 I01 응답을 기다린 시간. Gateway를 선택해야 표시되며 Redis 자체 처리 시간과는 다름 |
| 라우트 평균 처리 시간 | 목적 서비스·메서드·상태별 Gateway 기본 계측값. 인증 거절 요청은 이 지표에 없을 수 있음 |
| Kafka 소비 지연 (건) | 소비자가 발행한 토픽별 최대 lag. 미측정 NaN은 0으로 바꾸지 않으며 업무 완료 시간과 구분 |
| 서비스 처리 지연 현황 | 서비스·업무·구분·건수를 표로 표시. 30분 초과는 아직 확정되지 않은 처리, 관리자 확인 대기는 담당자의 판단을 기다리는 상태 |

DB 획득 시간만으로 느린 SQL을 판정하지 않습니다. 연결 사용량·잠금·SQL 실행 정보를 함께 확인합니다. GC 정지와 Heap 증가만으로 메모리 누수를 단정하지 않습니다. Gateway 인증 확인 시간과 라우트 시간도 단순히 빼서 나머지 작업 시간을 계산하지 않습니다. HTTP 202 이후 업무가 최종 완료될 때까지 걸린 시간은 별도 계측이 필요하며, 이번 패널이 대신 측정하지 않습니다.

견적 DB/Redis 버전을 비교할 때는 연결 획득 시간과 점유 시간을 함께 봅니다. 점유 시간에는 SQL·커밋 외에 연결을 보유한 동안의 작업도 포함되므로, 평균이 길다는 사실만으로 원인을 확정하지 않습니다. 아직 반환하지 않은 연결은 사용 시간 표본에 들어가지 않으므로 현재 사용·대기 수도 함께 확인합니다.

**환율 캐시(Exchange) 구역은 기본으로 접혀 있습니다.** `Service=All` 또는 `exchange-service`를 포함해 선택하고 구역을 펼칩니다. User 등 다른 서비스만 선택하면 캐시 그래프는 `No data`이며 정상입니다. 구역 자체가 선택에 따라 자동으로 숨겨지는 구성은 아닙니다. 현재 배치를 유지하면서 필요할 때 펼치는 방식을 사용합니다.

캐시 결과는 API 요청 수가 아니라 조회한 통화 키 수입니다. 두 비교 버전의 계측 유무·캐시 준비 상태·측정 구간을 맞추고 API 성공률도 확인합니다. `error`는 환율 캐시 읽기 오류이며 견적 저장 오류나 캐시 쓰기 실패 전체를 나타내지 않습니다. Exchange를 선택했는데 비어 있다면 계측·수집 여부와 호출 기록을 확인하고, 빈 값을 0건으로 해석하지 않습니다.

**Redis Session은 User만 직접 연결합니다.** Gateway는 User의 I01을 통해 세션을 확인합니다. 이 Redis에는 세션뿐 아니라 로그인 실패 횟수·회원 이름 조회 제한 정보도 저장되므로, 환율 캐시의 Hit/Miss 지표를 복제해 세션 상태로 표시하지 않습니다. User의 HTTP 상태·응답 시간과 오류 로그로 인증 경로를 확인하며, Gateway의 I01 호출 시간에는 네트워크·User의 DB 작업도 포함됩니다. Redis 자체의 메모리·연결 수·명령 지연은 현재 수집 범위에 없고 전용 수집기가 필요합니다.

### 부하 시험 환경 연결 — 선택

`compose.monitoring.yaml`은 기본 모니터링입니다. 시험할 때만 **`compose.monitoring.load-test.yaml`을 함께 읽어** Prometheus를 기존 측정 네트워크에 추가로 연결합니다. 모니터링 도구를 중복 실행하거나 시험용 앱·DB를 새로 만드는 설정이 아닙니다. 각 서비스의 측정 도구가 앱·인프라·데이터를 준비합니다.

연결에는 아래 두 값이 필요합니다. 루트 `.env` 또는 측정 도구의 실행 환경으로 전달합니다. 일반 모니터링만 사용할 때는 설정하지 않아도 됩니다. 아래는 현재 Exchange 시험 환경의 예시입니다.

```dotenv
MONITORING_LOAD_TEST_NETWORK=travelpay-load-test_default
MONITORING_LOAD_TEST_TARGETS_FILE=./infra/monitoring/prometheus/load-test/targets.local.yml
```

대상 예시를 개인 파일로 복사한 뒤 앱 주소·메트릭 포트·서비스 이름을 맞춥니다. `*.local.yml`은 Git에서 제외되고 예시 파일만 공유합니다.

```bash
cp infra/monitoring/prometheus/load-test/targets.example.yml infra/monitoring/prometheus/load-test/targets.local.yml
```

```yaml
- targets:
    - app:18085
  labels:
    service: exchange-service
    environment: load-test
```

하나의 측정 네트워크에 여러 서비스가 있다면 대상 파일에 위 형식의 그룹을 추가합니다. `service`는 각 앱의 실제 이름, `environment`는 `load-test`로 지정합니다. 다른 서비스의 시험 환경으로 전환할 때는 공용 Compose를 수정하지 않고 **네트워크 이름과 대상 파일 경로만 변경**합니다. 입력값이 비어 있으면 Compose가 오류로 알리며, 없는 파일 경로를 폴더로 자동 생성하지 않습니다.

현재 Exchange 시험 앱은 저장소 루트에서 다음처럼 준비할 수 있습니다. 다른 버전 JAR 비교 시에는 기존 `APP_JAR` 설정을 사용합니다. API `18084`·메트릭 `18085`와 기존 시험 실행 방식은 유지합니다.

```bash
./gradlew :exchange-service:bootJar
docker compose -f exchange-service/load-test/compose.yaml --profile app up -d --wait app
```

측정 네트워크와 대상 파일이 준비되면 공용 연결 명령을 실행합니다. 두 파일을 합쳐 Prometheus만 재생성하며 저장 볼륨은 유지합니다. 선택값·대상 파일을 바꾼 뒤에도 같은 명령으로 다시 반영합니다.

```bash
docker compose -f compose.monitoring.yaml -f compose.monitoring.load-test.yaml up -d --no-deps --force-recreate prometheus
```

- Prometheus는 선택한 Docker 네트워크 안의 컨테이너 이름과 메트릭 포트로 수집합니다. 호스트에 공개된 루프백 포트를 경유하지 않습니다. `targets/load-test.yml`의 기본 빈 목록은 선택한 파일의 마운트로 대체됩니다.
- Grafana에서 `메트릭 환경=load-test`와 대상 서비스를 선택합니다. `API`·`HTTP 상태`는 우선 All로 두고 Prometheus Targets에서 시험 대상이 UP인지 확인합니다. HTTP bucket 등 계측이 없는 이전 JAR의 패널은 비어 있을 수 있습니다.
- Prometheus의 15초 수집은 시험 스크립트의 1초 표본과 별개입니다. 비교 실행의 계측·데이터·모니터링 실행 조건을 맞추고, 서로 다른 시험 회차는 시간 범위로 구분합니다. 시험 컨테이너 로그는 이번 수집 범위에 포함하지 않습니다.

시험을 종료하기 **전에** Prometheus를 기본 구성으로 되돌려 시험 네트워크와 수집 대상을 분리합니다. 그다음 서비스 담당자의 기존 절차로 시험 환경을 종료합니다. 연결한 채로 시험 환경을 내리면 네트워크가 사용 중이라 제거되지 않을 수 있습니다.

```bash
docker compose -f compose.monitoring.yaml up -d --no-deps --force-recreate prometheus
```

기본 앱의 빠른 병목 확인은 기존 주소로 부하를 보내고 `local`에서 보면 됩니다. 예를 들어 PC에서 실행한 Exchange `8084`, Wallet `8082`, Payment `8083`은 기본 수집 대상이며 위 시험 연결 설정이 필요하지 않습니다.

Prometheus는 `172.17.0.1` 같은 고정 IP 대신 `host.docker.internal`을 사용합니다. Compose의 `host-gateway` 매핑으로 Linux 호스트도 연결합니다. Mac·Windows는 Linux 컨테이너를 실행하는 Docker Desktop을 기준으로 합니다. VPN·회사 방화벽·WSL의 별도 네트워크 구성에 따라 접근 허용이 필요할 수 있습니다. Mac·Windows 실기 검증은 별도로 진행합니다. [Docker Desktop 네트워크](https://docs.docker.com/desktop/features/networking/), [Docker host-gateway](https://docs.docker.com/reference/cli/dockerd/#configure-host-gateway-ip)

Gateway 관리 포트는 `0.0.0.0:18080`에서 수신해 Docker의 접근을 받습니다. 이 주소는 수신 범위이며 브라우저나 Prometheus에 입력할 목적지 주소가 아닙니다. 앱의 `127.0.0.1` 수신은 호스트의 루프백에만 한정되고, 컨테이너 안의 `127.0.0.1`은 해당 컨테이너를 가리킵니다. 관리 포트에는 health·prometheus만 노출하고 API 라우트는 차단합니다. 모든 인터페이스에서 수신하므로, 신뢰하지 않는 네트워크에서 개발할 때는 호스트 방화벽으로 관리 포트 접근을 제한합니다. Grafana 등 모니터링 컨테이너의 공개 포트는 `127.0.0.1`에 바인딩됩니다.

### 로그 연결과 보관

앱은 `infra/monitoring/.local-logs/<서비스명>.log`에 콘솔과 동일한 Logstash JSON을 기록합니다. 예외의 스택도 한 JSON 줄에 포함됩니다. Alloy는 이 파일의 `service`를 `service_name` 레이블로 옮기고 로그의 기록 시각을 사용합니다. `Service=All`이면 Redis·Kafka·PostgreSQL 등 개발용 Docker 인프라 로그도 함께 표시하며, 특정 앱을 선택하면 해당 앱 로그만 표시합니다. 선택한 시간 범위에 새 로그가 없으면 표시되지 않습니다.

로그 상세의 `traceId`로 요청 흐름을 찾을 수 있습니다. 예를 들어 Grafana Explore의 Loki에서 `{job="spring-local",service_name="user-service"} | json | traceId="찾을 traceId"`로 검색합니다. 매 요청마다 값이 다른 traceId는 Loki 인덱스 레이블로 만들지 않습니다.

대시보드의 `Trace ID`에는 로그에서 찾은 32자리 16진수 ID를 넣습니다. 비우면 전체 로그이며, 이 필터는 로그 원문에만 적용됩니다. 여러 서비스의 로그를 함께 찾을 때는 Service를 All 또는 필요한 서비스 조합으로 선택합니다. spanId는 Explore에서 `| json | spanId="찾을 spanId"` 조건으로 검색할 수 있습니다.

**로그** 구역의 **통합 로그** 패널에서 검색 버튼을 누르면 현재 불러온 기록 안에서 문자를 찾을 수 있습니다. 로그 등급 필터와 JSON 펼쳐보기도 사용할 수 있습니다. 전체 시간 범위에서 내용을 검색하려면 Explore의 Loki 쿼리에 `|= "검색어"` 줄 필터를 사용합니다. 현재 불러온 결과에서 찾는 검색과 Loki 전체 선택 구간을 조회하는 검색의 범위는 다릅니다.

통합 로그는 `Service=All`일 때 수집된 앱·개발 인프라 전체를, 개별 서비스를 고르면 해당 서비스만 표시합니다. 메트릭 환경 필터는 로그에 적용하지 않으며 별도 시험 컨테이너 로그는 포함하지 않습니다.

Trace ID가 있는 로그 행을 펼치면 **같은 요청 로그 보기** 링크가 나타납니다. 이 링크는 같은 대시보드를 전체 서비스·API·상태와 선택한 시간 범위로 열고 Trace ID를 자동 입력합니다. 원문에서 긴 ID를 직접 찾아 복사하는 작업을 줄이기 위한 링크이며, 분산 추적 타임라인으로 이동하는 기능은 아닙니다.

`traceparent`는 HTTP 호출에 추적 문맥을 전달하는 헤더입니다. traceId는 같은 추적 흐름을, spanId는 그 안의 작업을 식별합니다. 현재는 로그에 기록된 ID를 이용한 연결에 활용합니다. 정상 요청마다 업무 로그가 생기는 것은 아니므로 로그가 없는 구간은 이 ID만으로 복원할 수 없습니다. 각 span의 시작·종료·부모 관계를 저장하는 추적 수집·저장소는 이번 구성에 추가하지 않았으며, 구간별 타임라인과 처리 시간 통계를 대신하는 기능은 아닙니다.

앱 로그 파일은 10MB 단위로 회전하며, 보관 파일은 3일·합계 50MB를 기준으로 정리합니다. 현재 쓰는 파일은 보관 파일 합계와 별도입니다. Prometheus는 72시간·2GB 보관 정책을 사용하고 Loki는 72시간 보관 정책을 사용합니다. 이 정책들이 컨테이너의 RAM 사용량까지 제한하는 것은 아닙니다. 로그 폴더의 `.gitkeep`만 Git에 포함하고 로그 내용은 제외합니다.

파일 출력은 IntelliJ에서 실행하는 앱의 로그를 Docker의 Alloy가 읽도록 로컬에만 추가했습니다. 배포용 YAML은 기존 Logstash JSON 콘솔 출력을 유지합니다. 컨테이너 배포에서는 표준 출력·표준 오류를 수집하는 Docker 로깅 드라이버나 수집기를 연결할 수 있습니다. 앱 내부 파일에도 중복 저장하려면 별도 영속 볼륨·권한·회전 정책을 정해야 하므로, 개발 PC용 파일 경로를 배포 설정에 그대로 넣지 않습니다. [Docker 로그 수집](https://docs.docker.com/engine/logging/)

현재 저장소에서 배포 로그의 외부 저장·수집 연결까지 구성한 것은 아닙니다. 실제 배포가 컨테이너인지 JVM 직접 실행인지에 맞춰 수집 경로와 보관 정책을 확정해야 합니다. HTTP 히스토그램도 이번에는 로컬 성능 확인용으로 활성화했으며, 운영 적용은 필요한 지표와 수집 비용을 검토해 별도로 정합니다.

이 구성은 JVM 메트릭·기존 앱 계측 지표·로그 수집 범위입니다. Kafka 소비자 lag는 앱이 이미 발행하는 지표를 사용하며, 브로커 전체 소비 그룹을 별도로 조회하는 수집기는 추가하지 않았습니다. Redis 메모리·PostgreSQL 내부 통계 전용 수집기와 분산 추적 저장소도 포함하지 않습니다. Alloy의 Docker 소켓 마운트는 컨테이너 로그 발견에 사용하며, `:ro`만으로 Docker API 권한이 읽기 전용이 되는 것은 아니므로 로컬 개발용으로 사용합니다.

### 종료와 문제 확인

```bash
docker compose -f compose.monitoring.yaml down
```

이 명령은 모니터링 컨테이너만 종료하고 데이터 볼륨은 유지합니다. 앱과 기존 개발 인프라는 별도로 종료합니다.

| 현상 | 확인할 것 |
|---|---|
| 로그 경로가 없다는 Compose 오류 | `.gitkeep`이 포함된 버전인지 확인. 폴더를 직접 삭제했다면 `mkdir -p infra/monitoring/.local-logs`로 복구 |
| Docker 로그만 보이고 앱 로그가 없음 | 앱 재실행, 작업 디렉터리, `.local-logs` 파일 생성·쓰기 권한 |
| 실행 중인 앱의 메트릭이 DOWN | Prometheus Targets의 오류, 앱 포트, 호스트 방화벽 |
| p95·p99가 비어 있음 | 변경된 YAML로 앱 재실행, API 호출 여부, bucket 수집·시간 범위 |

## DB 계정과 스키마

PostgreSQL의 새 데이터 볼륨을 처음 준비할 때 컨테이너 초기화 SQL이 서비스별 로그인 계정·소유 스키마·접속 권한·기본 `search_path`를 만듭니다. 각 앱은 `USER_DB_*`, `WALLET_DB_*` 등 자기 서비스 변수를 사용하며 다른 서비스의 표를 직접 읽지 않습니다.

앱의 표와 데이터 변경은 각 모듈의 Flyway 마이그레이션이 담당하고, 현재 로컬 JPA 설정은 `ddl-auto: validate`로 엔티티와 DB 구조를 검사합니다. `currentSchema`와 계정의 `search_path`는 앱이 접근할 스키마를 선택하는 설정입니다.

기존 볼륨에는 컨테이너 초기화 SQL을 자동 재실행하지 않습니다. `.env`에서 DB 비밀번호를 바꿔도 이미 생성된 DB 계정의 비밀번호가 바뀌는 것은 아닙니다. 기존 데이터·계정 변경은 별도 절차로 처리합니다. 앱에서 표를 바꿀 때는 이미 적용된 Flyway 파일을 수정하지 않고 새 마이그레이션을 추가합니다.

## 연결이 되지 않을 때

| 현상 | 먼저 확인할 것 |
|---|---|
| `'url' must start with "jdbc"` | 앱에 `.env`가 전달됐는지, 해당 `*_DB_URL`이 JDBC 주소인지 |
| DB·Redis 연결 거부 | `docker compose ps`, 호스트 주소·포트, 필요한 인프라의 상태 |
| DB 인증 실패·Redis `NOAUTH`/`WRONGPASS` | 앱 환경 변수와 실행 중인 저장소의 계정·비밀번호가 일치하는지 |
| `Port ... was already in use` | 같은 앱이나 이전 실행이 포트를 사용 중인지. IDE의 해당 실행을 종료한 뒤 다시 시작 |
| 다른 서비스를 찾지 못함 | Eureka와 대상 앱이 실행됐는지, 직접 URL이 필요한 클라이언트인지 |
| Wallet의 Kafka 오류 반복 | Kafka가 실행 중이며 앱의 접속 주소가 `localhost:9092`인지 |

IDE에서 Stop한 앱의 프로세스가 종료되면 그 프로세스가 사용하던 포트가 해제됩니다. Docker로 실행한 인프라의 수명은 별개이므로 `docker compose ps`로 상태를 확인합니다.
