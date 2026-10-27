# 초기 개발 기반 구현 설명

## 목적과 범위

팀원이 동일한 방식으로 로컬 DB를 준비하고, 담당 서비스를 독립 실행하며 공통 오류·로그 계약을 사용할 수 있도록 구성합니다.

README는 현재 비워 둡니다. 실행 방법은 `docs/guide/`, 구현 선택과 검증은 `docs/code-review/`, 협업 규칙은 `docs/convention/`에서 관리합니다.

## 구현 순서

- [x] Mock Bank의 실행·DB·로그·지표 기본 구성
- [x] 루트의 기존 단일 앱 샘플 소스 제거
- [x] 환경 변수 예시와 실제 환경 파일 제외 규칙
- [x] 로컬 PostgreSQL Compose와 계정·스키마 초기화 SQL
- [ ] 공통 오류·헤더 계약과 기본 MVC·WebFlux 예외 처리
- [ ] DB 권한·앱 연결·오류 응답·로컬/배포 설정 검증
- [ ] 가이드와 구현 설명의 실제 검증 결과 반영

## 주요 선택

- Java 21·Spring Boot 4.1.2·Gradle 9.8.2와 기존 루트 BOM·추적 정책을 유지합니다.
- 업무 서비스 경계는 User·Wallet·Payment·Exchange·Ledger입니다. Funding은 Wallet, Transfer는 Payment 내부 기능입니다.
- Mock Bank는 외부 은행을 모의하는 별도 HTTP 앱입니다. 고정한 로컬/배포 URL로 호출하도록 준비하며 Eureka에는 등록하지 않습니다.
- PostgreSQL은 `postgres:18.6-bookworm`으로 고정합니다. 앱마다 계정과 소유 스키마를 분리하고 다른 스키마에 접근 권한을 부여하지 않습니다.
- PostgreSQL 18 공식 이미지에 맞춰 데이터 볼륨을 `/var/lib/postgresql`에 연결합니다. 실제 데이터 디렉터리는 `/var/lib/postgresql/18/docker`입니다. [공식 이미지의 저장 경로 안내](https://github.com/docker-library/docs/blob/master/postgres/README.md#pgdata)를 기준으로 구성합니다.
- 로컬 실행 편의성을 위해 `compose.yaml`을 루트에 배치했습니다.
- 각 앱의 두 YAML은 완결된 설정이며, DB URL·계정은 환경 변수로 전달합니다. 배포 파일로 로컬 DB를 연결해 검사할 수 있습니다.
- 개발 중 테이블 생성을 돕도록 로컬은 `ddl-auto: update`, 배포 DB의 자동 변경을 막도록 배포는 `validate`를 사용합니다. OSIV는 두 환경 모두 끕니다.
- `common`은 Spring 의존성이 없는 Java 라이브러리입니다. 실제 HTTP 응답 처리는 각 MVC 앱과 Gateway에 둡니다.
- Git 저장소 생성·브랜치·커밋·외부 배포는 이 작업에 포함하지 않습니다.

## 검증

완료한 확인:

- Docker Compose 설정 검증에서 이미지 버전, PostgreSQL 18의 볼륨 경로, 초기 SQL의 실제 연결 경로와 로컬 포트 바인딩을 확인했습니다.
- 임시 rootless Podman 컨테이너에서 PostgreSQL 18.6을 실행하고 초기 SQL을 적용했습니다. 실제 데이터 경로는 `/var/lib/postgresql/18/docker`입니다.
- 6개 서비스 계정의 기본 스키마와 자기 스키마의 테이블 생성·입력·조회를 확인했습니다.
- 다른 서비스 스키마 조회 30건과 다른 스키마·public·DB에 대한 미허용 생성 작업 18건이 모두 거절됐습니다. 잘못된 비밀번호의 접속도 거절됐습니다.
- 각 서비스의 로컬·배포 YAML, 환경 변수 예시, 초기 SQL에서 계정 변수와 스키마 이름이 일치하는지 확인했습니다.
- Compose 이동 후 루트에서 `-f` 없이 파일을 찾고, 초기 SQL 경로·프로젝트 이름·볼륨 이름을 포함한 최종 설정이 이동 전과 같은지 확인했습니다.
- `./gradlew build --console=plain` 전체 빌드가 성공했습니다. 현재 테스트 코드는 없어 테스트 작업은 `NO-SOURCE`이며 JaCoCo 보고서는 생성되지 않았습니다.

공통 오류·헤더·예외 처리, 실행 가이드와 앱의 DB 연결 검증은 남아 있습니다. 위 DB 실행 검증은 임시 컨테이너에서 수행했으며 Docker Compose로 전체 구성을 기동한 결과는 아닙니다.
