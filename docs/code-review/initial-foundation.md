# 초기 개발 기반 구현 설명

## 목적과 범위

팀원이 동일한 방식으로 로컬 DB를 준비하고, 담당 서비스를 독립 실행하며 공통 오류·로그 계약을 사용할 수 있도록 구성합니다.

README는 현재 비워 둡니다. 실행 방법은 `docs/guide/`, 구현 선택과 검증은 `docs/code-review/`, 협업 규칙은 `docs/convention/`에서 관리합니다.

## 구현 순서

- [x] Mock Bank의 실행·DB·로그·지표 기본 구성
- [x] 루트의 기존 단일 앱 샘플 소스 제거
- [x] 환경 변수 예시와 실제 환경 파일 제외 규칙
- [x] 로컬 PostgreSQL Compose와 계정·스키마 초기화 SQL
- [x] 공통 오류·헤더 계약과 MVC 예외·회원 정보 전달·페이징 지원
- [x] 로컬 DB 계정·스키마 권한과 앱 설정의 변수 이름 대조
- [ ] 전체 앱의 실제 DB 연결·Gateway 인증·서비스 호출 연동 검증

## 주요 선택

- Java 21·Spring Boot 4.1.2·Gradle 9.8.2와 기존 루트 BOM·추적 정책을 유지합니다.
- 업무 서비스 경계는 User·Wallet·Payment·Exchange·Ledger입니다. Funding은 Wallet, Transfer는 Payment 내부 기능입니다.
- Mock Bank는 외부 은행을 모의하는 별도 HTTP 앱입니다. 고정한 로컬/배포 URL로 호출하도록 준비하며 Eureka에는 등록하지 않습니다.
- PostgreSQL은 `postgres:18.6-bookworm`으로 고정합니다. 앱마다 계정과 소유 스키마를 분리하고 다른 스키마에 접근 권한을 부여하지 않습니다.
- PostgreSQL 18 공식 이미지에 맞춰 데이터 볼륨을 `/var/lib/postgresql`에 연결합니다. 실제 데이터 디렉터리는 `/var/lib/postgresql/18/docker`입니다. [공식 이미지의 저장 경로 안내](https://github.com/docker-library/docs/blob/master/postgres/README.md#pgdata)를 기준으로 구성합니다.
- 로컬 실행 편의성을 위해 `compose.yaml`을 루트에 배치했습니다.
- 각 앱의 두 YAML은 완결된 설정이며, DB URL·계정은 환경 변수로 전달합니다. 배포 파일로 로컬 DB를 연결해 검사할 수 있습니다.
- 개발 중 테이블 생성을 돕도록 로컬은 `ddl-auto: update`, 배포 DB의 자동 변경을 막도록 배포는 `validate`를 사용합니다. OSIV는 두 환경 모두 끕니다.
- 공통 모듈은 하나로 유지합니다. 오류 계약은 Spring 타입 없이 표현하고, MVC 처리·자동 설정은 common의 별도 패키지에 둡니다. Spring API는 compileOnly로 선언하고 각 앱이 필요한 starter를 선택합니다.
- MVC 오류 처리기는 common에서 제공합니다. CommonWebAutoConfiguration이 Servlet 환경·MVC 클래스 존재·기존 처리기 유무를 확인해 등록합니다. 각 서비스에 별도의 오류 처리기 Java 파일을 추가하지 않습니다.
- MVC 회원 정보 전달도 common에서 제공합니다. 업무 서비스 5개의 로컬·배포 YAML에 travelpay.auth.header-context.enabled=true를 두어 헤더 해석·요청 속성 저장을 켭니다. 로그인·세션 검증을 구현하는 옵션은 아닙니다.
- 현재 common 커밋 범위는 common 코드·테스트·문서, 업무 서비스 YAML, Gateway의 공통 오류 응답 어댑터·대응 테스트입니다. 서비스별 업무 Java 코드는 추가하지 않습니다. Gateway의 세션 검증 호출·인증 필터는 별도 Gateway 작업으로 분리합니다.
- 공통 오류 사용법은 [오류 계약 가이드](../guide/error-contract.md)에 정리했습니다. 업무 오류는 각 서비스가 정의하고 검증 실패에는 필드명·안내 메시지만 반환합니다.
- Git 저장소 생성·브랜치·커밋·외부 배포는 이 작업에 포함하지 않습니다.

## 검증

완료한 확인:

- Docker Compose 설정 검증에서 이미지 버전, PostgreSQL 18의 볼륨 경로, 초기 SQL의 실제 연결 경로와 로컬 포트 바인딩을 확인했습니다.
- 임시 rootless Podman 컨테이너에서 PostgreSQL 18.6을 실행하고 초기 SQL을 적용했습니다. 실제 데이터 경로는 `/var/lib/postgresql/18/docker`입니다.
- 6개 서비스 계정의 기본 스키마와 자기 스키마의 테이블 생성·입력·조회를 확인했습니다.
- 다른 서비스 스키마 조회 30건과 다른 스키마·public·DB에 대한 미허용 생성 작업 18건이 모두 거절됐습니다. 잘못된 비밀번호의 접속도 거절됐습니다.
- 각 서비스의 로컬·배포 YAML, 환경 변수 예시, 초기 SQL에서 계정 변수와 스키마 이름이 일치하는지 확인했습니다.
- Compose 이동 후 루트에서 `-f` 없이 파일을 찾고, 초기 SQL 경로·프로젝트 이름·볼륨 이름을 포함한 최종 설정이 이동 전과 같은지 확인했습니다.

common 커밋의 검증 범위:

- common의 12개 테스트 파일에서 오류 응답·페이징·회원 헤더 파싱·요청 속성 조회·MVC 파라미터 전달·조건부 자동 등록을 검증합니다.
- common은 Gateway 전용 Java 코드에 의존하지 않습니다. 이번 범위만 남긴 작업 트리에서 `./gradlew clean build --console=plain`이 성공했습니다.
- common 테스트 126개와 Gateway 오류 연동 테스트 13개, 총 139개가 모두 통과했습니다. 삭제한 세션 검증 호출 코드가 실행 JAR에 남지 않은 것도 확인했습니다.
- Gateway를 임시 로컬 포트에서 실행해 health UP과 GET·HEAD 404의 공통 오류 응답을 확인했습니다. Eureka 연동은 끄고 검증했으며 시험 프로세스는 종료했습니다.
- 업무 서비스 YAML 10개에는 활성화 블록만 추가합니다. DB 변수·포트·Eureka·로그·OSIV·JPA 설정은 유지합니다.

위 DB 권한 검증은 이전 초기 구성 작업의 기록입니다. common 테스트와 전체 빌드는 실제 DB·Redis·Gateway 인증 연동 검증을 대체하지 않습니다.

전체 실행 가이드와 앱의 DB 연결 검증은 남아 있습니다. 위 DB 실행 검증은 임시 컨테이너에서 수행했으며 Docker Compose로 전체 구성을 기동한 결과는 아닙니다.
