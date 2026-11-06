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

환전을 확인할 때는 캐시 Redis도 실행합니다. 환전은 충전한 지갑으로 확인하므로 충전에 필요한 인프라와 함께 띄웁니다.

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

캐시 Redis는 Exchange가 사용합니다. 환율 조회와 견적 발급에서 최신 환율을 캐시 Redis에서 먼저 읽고, 연결 정보는 `.env`의 `REDIS_CACHE_HOST`·`REDIS_CACHE_PORT`·`REDIS_CACHE_PASSWORD`를 씁니다. 견적은 지금처럼 PostgreSQL에 저장합니다. 캐시 Redis가 꺼져 있어도 환전은 DB에서 환율을 읽어 정상 동작합니다. 대신 시작할 때 한 번, 이후 환율 조회·수집이 캐시를 쓰려다 실패하면 1분에 한 번 경고 로그가 남습니다.

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
| Exchange | 8084 | PostgreSQL, 환율 수집 모드와 기능별 연동 |
| Gateway | 8080 | Eureka·User 및 호출할 업무 서비스 |

Gateway 관리 포트는 별도 `18080`이며 헬스체크 경로는 `/actuator/health`입니다. 표는 기본 설정이며 실제 값은 각 모듈의 실행 설정과 인자를 기준으로 합니다.

회원·인증 확인은 **인프라 → Eureka → User → Gateway**, 충전 확인은 **인프라 → Eureka → User → Ledger → Mock Bank → Wallet** 순서로 준비하면 의존 서비스를 먼저 확인하기 쉽습니다. 브라우저 요청은 Gateway까지 실행한 뒤 보냅니다. 이 순서는 권장 준비 순서이며 앱 사이에 자동 실행을 연결한 설정은 아닙니다. 필요한 앱만 실행합니다.

서비스별 추가 설정도 확인합니다.

- Wallet의 `WALLET_ACCOUNT_NUMBER_KEY`는 Base64로 표현한 32바이트 키이며, `.env.example`의 빈 값을 준비하지 않으면 기동할 수 없습니다. 개인 키 생성 방법은 `.env.example`의 설명을 따릅니다.
- 로컬 충전에서 Mock Bank를 호출할 때는 `MOCK_BANK_URL=http://localhost:8090`을 Wallet 실행 환경에 전달합니다. Mock Bank는 Eureka에 등록하지 않으므로 직접 주소가 필요합니다.
- Exchange는 `.env.example`의 `EXCHANGE_RATE_MODE`·`KOREAEXIM_API_KEY` 설명을 확인합니다. 로컬의 mock 환율 수집 모드는 API 키 없이 사용할 수 있으며 운영에서는 사용하지 않습니다.
- 프런트를 연결할 때는 Gateway의 `GATEWAY_CORS_ALLOWED_ORIGINS`에 실제 프런트 출처를 지정합니다.

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
