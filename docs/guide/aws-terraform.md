# AWS Terraform 실행 가이드

Terraform 코드는 `infra/aws/`에서 관리합니다. Spring Boot Run과 별개이며 실제 리소스 생성은 Terraform의 `apply`로 실행합니다.

현재 첫 PR의 구현 범위는 **State S3 부트스트랩, VPC·서브넷·라우팅, 역할별 SG**입니다. **S3와 네트워크·SG의 실제 apply·AWS 설정 대조·재실행 plan 검증을 완료**했습니다. 데이터·앱·AWS 모니터링 배치와 실제 업무 통신 검증은 후속 단계입니다.

설계·구현 기준은 [Terraform 설계·구현 원칙](https://app.notion.com/p/413530b4fe5980cf9edbc37ec93b957e)에 모으고, 이 문서는 인프라 담당자의 실행·이전 절차만 다룹니다.

## 코드로 관리하는 이유

VPC·서브넷·라우팅·SG를 Terraform 코드로 관리하는 이유는 다음과 같습니다.

- **재현성:** 같은 구성과 명시한 입력으로 인프라를 재구축하고 환경별 차이를 관리합니다.
- **변경 검토:** Git 이력·PR·plan으로 변경 내용과 영향을 확인한 뒤 반영합니다.
- **실수 감소:** 입력 검증·구성 테스트·plan 검토로 잘못된 주소나 과도한 접근 허용을 적용 전에 발견할 가능성을 높입니다.
- **자동화:** 반복적인 설정 작업을 줄이고 향후 CI/CD에 연결할 기반을 만듭니다.

현재는 운영 환경 한 개를 대상으로 합니다. 코드만으로 환경의 완전한 동일성이나 오류 방지가 보장되지는 않으며 실제 AWS 검증을 함께 수행합니다.

근거: [Terraform 버전 관리](https://developer.hashicorp.com/terraform/intro/phases/adopt), [변경 계획 검토](https://developer.hashicorp.com/terraform/tutorials/cli/plan).

## 실행 구조

```text
infra/aws/
├── .terraform-version       # Terraform 1.17.1
├── versions.tf              # AWS Provider 6.72.0, S3 Backend 선언
├── providers.tf             # 서울 리전·허용 계정·공통 태그
├── variables.tf             # 계정·CIDR·AZ 검증, DB 초기화 접속 옵션
├── network.tf               # VPC·서브넷·IGW·NAT·라우팅
├── security.tf              # 역할별 SG·통신 규칙·규칙 소유권
├── outputs.tf               # 네트워크·SG 식별자 출력
├── .terraform.lock.hcl      # Provider 버전·패키지 체크섬
├── tests/                   # 네트워크·SG 구성/참조 테스트 13개
├── backend.hcl.example      # 개인 Backend 설정 예시
├── terraform.tfvars.example # 개인 입력 예시
└── bootstrap/               # State S3를 처음 만드는 독립 root
    ├── versions.tf          # Terraform·AWS Provider 버전
    ├── providers.tf         # 서울 리전·허용 계정·태그
    ├── variables.tf         # 대상 계정 검증
    ├── state.tf             # 버킷과 보안 설정
    ├── outputs.tf           # Backend 설정·최소 IAM 정책 출력
    ├── .terraform.lock.hcl  # bootstrap Provider 버전·체크섬
    ├── backend.tf           # State 이전 후 활성화한 S3 Backend
    ├── backend.tf.example   # 최초 생성 후 S3로 State를 옮길 때 활성화
    ├── terraform.tfvars.example
    └── tests/               # State 보안·IAM 범위 테스트 3개
```

Terraform은 현재 작업 디렉터리의 `.tf`를 함께 읽습니다. 하위 `bootstrap/`을 본 인프라에 자동 포함하지 않으므로 두 root는 각각 실행합니다. `.terraform.lock.hcl`은 Git에 포함하고 `.terraform/`, State·plan·개인 입력값은 제외합니다.

두 root 모두 실제 `hashicorp/aws` Provider를 사용합니다. `required_providers`는 사용할 플러그인·버전을 정하고 `provider "aws"`는 리전·허용 계정·태그를 설정합니다. 테스트의 `mock_provider "aws"`는 그 테스트에서 AWS API 동작을 대체합니다. 운영 설정에서 AWS Provider를 제거하는 구성이 아닙니다.

## State를 먼저 준비하는 이유

State는 코드의 리소스와 실제 AWS ID를 연결하는 기록입니다. 여러 작업자가 같은 인프라를 관리할 때 S3의 같은 State를 사용하고, lockfile로 동시에 쓰는 작업을 막습니다.

S3 Backend 초기화에는 이미 존재하는 버킷이 필요합니다. 따라서 다음 순서로 진행합니다.

1. `bootstrap`이 기본 local State로 State용 S3를 생성합니다.
2. S3 생성이 끝나면 `bootstrap`의 State도 S3로 옮깁니다.
3. 본 인프라 root는 처음부터 같은 버킷의 별도 State를 사용합니다.

```text
travelpay-tfstate-<실제 계정 ID>-ap-northeast-2
├── bootstrap/terraform.tfstate
├── bootstrap/terraform.tfstate.tflock
├── production/terraform.tfstate
└── production/terraform.tfstate.tflock
```

`.tflock`는 Terraform 작업 중 사용하는 잠금 파일입니다. State와 lockfile의 삭제 권한을 구분합니다. S3 lockfile을 사용하므로 DynamoDB 잠금 테이블은 만들지 않습니다.

## 적용한 보호 설정

| 항목 | 동작 |
|---|---|
| 계정 ID 형식 | 숫자 12자리만 허용 |
| 허용 계정 | Provider와 Backend가 지정한 계정으로만 동작하도록 설정 |
| 리전 | 서울 `ap-northeast-2` |
| 공개 접근 | Public Access Block 네 항목 활성화, 객체 ACL 비활성화 |
| 저장 암호화 | SSE-S3 `AES256` |
| 전송 | 버킷 정책에서 일반 주체의 HTTP 요청 거절 |
| 복구 | 버전 관리 활성화 |
| 버킷 삭제 | `prevent_destroy=true`, `force_destroy=false` |
| Backend 권한 | 두 State는 읽기·쓰기, 삭제는 두 lockfile에만 허용 |

이 보호 설정은 자격 증명 자체의 권한을 줄이지 않습니다. `state_backend_access_policy_json`은 최소 Backend IAM 정책을 **출력만** 하며 역할에 자동 연결하지 않습니다. 버킷을 처음 만드는 권한·이력 복구 권한은 일상적인 Backend 사용 권한과 별도로 준비합니다. 다른 정책으로 추가된 삭제 권한까지 이 출력이 제거하지는 않습니다.

## 실행 준비

Terraform 1.17.1과 AWS CLI 2.x를 사용하는 인프라 담당자용 절차입니다. 개인 OS 설치 방법은 이 가이드에서 다루지 않습니다. AWS Provider는 각 root의 `terraform init`이 내려받고 `.terraform.lock.hcl`로 버전·체크섬을 고정합니다.

AWS 접속은 기존 SSO 또는 CLI 프로필을 사용합니다. 아래 `travelpay`는 예시 프로필명이므로 실제 준비한 이름으로 바꿉니다.

```bash
export AWS_PROFILE=travelpay
aws sts get-caller-identity
```

출력된 Account와 사용할 계정 ID가 같은지 확인합니다. 액세스 키·비밀번호를 `.tf`, tfvars, Backend 설정이나 Git에 넣지 않습니다. `.env`는 Terraform이 자동으로 읽는 입력 파일이 아닙니다.

예시의 `123456789012`는 설명용 계정 ID이며 이 프로젝트의 실제 계정으로 확인한 값이 아닙니다. 계정 ID는 인증 비밀값과 구분하지만 실제 개인 입력·Backend 파일은 Git에서 제외합니다. 실행 권한은 SSO·IAM 자격 증명으로 확인하며 숫자 12자리 검사만으로 확보되는 것은 아닙니다.

## 기존 State에 연결

현재는 최초 생성·이전이 끝났으므로 이후 작업자는 이 절차부터 시작합니다. 개인 `terraform.tfvars`와 `backend.hcl`을 준비하고 이미 있는 파일은 덮어쓰지 않습니다. 입력 예시와 `infra/aws/backend.hcl.example`에서 실제 계정·버킷·AZ를 맞춥니다. 같은 버킷 안에서 bootstrap의 key는 `bootstrap/terraform.tfstate`, 본 root의 key는 `production/terraform.tfstate`입니다.

```bash
terraform -chdir=infra/aws/bootstrap init -backend-config=backend.hcl -lockfile=readonly
terraform -chdir=infra/aws init -backend-config=backend.hcl -lockfile=readonly
```

이후 변경은 현재 Backend에서 새 plan으로 검토합니다. 이미 적용했거나 State 이전 전에 저장한 plan은 재사용하지 않습니다.

다음 1·2절은 S3 Backend 선언이 없었던 **최초 생성·이전 기록**입니다. 기존 State를 사용하는 담당자가 반복 실행하는 절차가 아닙니다. 새 계정에서 처음 구성할 경우에는 기존 운영 State와 분리하고 S3 Backend 선언이 없는 초기 bootstrap 구성부터 준비합니다.

## 1. 최초 State S3 생성 계획

아래 명령은 저장소 루트에서 실행합니다.

```bash
cp infra/aws/bootstrap/terraform.tfvars.example infra/aws/bootstrap/terraform.tfvars
```

생성한 `terraform.tfvars`의 설명용 계정 ID를 실제 ID로 수정합니다. 최초에는 `bootstrap/backend.tf.example`을 복사하지 않습니다. 버킷 생성 전 S3 Backend를 활성화하면 초기화할 수 없습니다.

```bash
terraform -chdir=infra/aws/bootstrap init
terraform -chdir=infra/aws/bootstrap validate
terraform -chdir=infra/aws/bootstrap plan -out=bootstrap.tfplan
```

계정·리전·버킷 이름·생성 항목과 비용을 확인합니다. 계획에는 State S3와 그 보안 설정만 있어야 합니다. 이 단계에는 EC2·RDS·NAT·앱 생성이 포함되지 않습니다.

다음 명령은 실제 S3를 생성합니다. **해당 plan을 검토하고 생성 승인을 받은 뒤** 실행합니다.

```bash
terraform -chdir=infra/aws/bootstrap apply bootstrap.tfplan
```

## 2. 최초 Backend 설정 생성과 State 이전

먼저 local State를 읽을 수 있는 상태에서 두 설정을 출력합니다.

```bash
terraform -chdir=infra/aws/bootstrap output -raw bootstrap_backend_config > infra/aws/bootstrap/backend.hcl
terraform -chdir=infra/aws/bootstrap output -raw production_backend_config > infra/aws/backend.hcl
```

그다음 bootstrap의 S3 Backend를 활성화하고 State를 이전합니다.

```bash
cp infra/aws/bootstrap/backend.tf.example infra/aws/bootstrap/backend.tf
terraform -chdir=infra/aws/bootstrap init -migrate-state -backend-config=backend.hcl
```

이전 확인 질문에서 버킷·기존 local State를 확인한 뒤 진행합니다. 기존 S3에 이미 State가 있다면 덮어쓰지 말고 먼저 어느 작업자의 State인지 확인합니다. `-force-copy`나 `-lock=false`로 우회하지 않습니다. 이전 후 `terraform state list`와 S3의 State 객체·버전·잠금 동작을 확인합니다. local backup State에도 민감 정보가 포함될 수 있으므로 Git 제외 상태를 유지합니다.

첫 부트스트랩이 완료되어 활성화한 `bootstrap/backend.tf`도 코드에 포함합니다. 이후 작업자는 기존 버킷을 새로 생성하지 않고 자신의 `backend.hcl`로 초기화합니다.

## 3. 본 인프라 root 연결

```bash
cp infra/aws/terraform.tfvars.example infra/aws/terraform.tfvars
```

같은 실제 계정 ID와 논리 A/B에 사용할 서로 다른 AZ 이름을 입력합니다. 예시 AZ를 실제 계정에서 사용할 수 있다고 가정하지 말고 다음 조회 결과로 확인합니다.

```bash
aws ec2 describe-availability-zones --region ap-northeast-2 --filters Name=zone-type,Values=availability-zone --query "AvailabilityZones[?State=='available'].[ZoneName,ZoneId]" --output table
```

VPC 기본값은 `10.20.0.0/16`입니다. 생성 전에 기존 VPC/VPN과 주소 충돌을 확인합니다. 앞에서 생성한 `infra/aws/backend.hcl`로 초기화합니다.

```bash
terraform -chdir=infra/aws init -backend-config=backend.hcl
terraform -chdir=infra/aws validate
terraform -chdir=infra/aws plan -out=foundation.tfplan
```

본 root는 현재 네트워크와 SG를 계획합니다. 실제 apply에는 NAT·EIP 등의 과금 리소스 생성이 포함되므로 계정·리전·AZ·CIDR과 생성/삭제/교체를 확인한 뒤 승인된 계획을 적용합니다. 위 plan 명령은 이 문서의 코드 검증 과정에서 자동 실행하지 않습니다. 실제 생성·수집·롤백 검증을 끝내기 전까지 전체 이슈 #245를 완료로 표시하지 않습니다.

## 네트워크 동작

`vpc_cidr`에서 주소를 파생하고, 입력한 AZ의 논리 A/B에 서브넷을 배치합니다. 모든 서브넷은 명시적으로 라우팅 테이블에 연결합니다.

| 서브넷 | 기본 CIDR | 기본 외부 경로 |
|---|---|---|
| Public A/B | 10.20.0.0/24, 10.20.1.0/24 | IGW |
| App A/B | 10.20.10.0/24, 10.20.11.0/24 | Public A의 Zonal NAT 한 개 |
| Data A | 10.20.20.0/24 | 같은 NAT |
| Data B | 10.20.21.0/24 | 없음(local만) |

VPC의 원래 Main Route Table도 local만 유지합니다. Public 여부는 IGW 경로로 정하며 EC2 공인 IP 자동 할당은 모든 서브넷에서 비활성화합니다. 같은 VPC 내부 통신은 local 경로로 이루어지며 SG로 필요한 연결만 허용합니다.

네트워크 입력을 생성 후 변경하면 VPC·서브넷·NAT 등이 교체될 수 있으므로 단순 설정 변경으로 보고 재적용하지 않습니다. Main Route Table의 기존 경로를 정리하는 구성은 새 VPC를 전제로 하며, 기존 VPC에 적용/import할 때는 실제 경로를 먼저 검토합니다. AWS 생성 후에는 ALB 진입·사설 통신·NAT 송신·Data B 외부 경로 부재를 실제로 확인합니다.

## SG 동작

`security.tf`는 앱 8개와 ALB·App 호스트·Data·RDS·모니터링·로그 수집기의 **14개 역할**을 분리합니다. 현재 운영 설정·호출 코드에 필요한 기본 TCP 연결 41개를 하나의 맵에 정의하고, 연결마다 출발 SG의 송신과 도착 SG의 수신 규칙을 생성합니다. 응답 트래픽은 SG의 상태 추적이 허용하므로 역방향 신규 연결을 추가하지 않습니다.

| 경계 | 허용 범위 |
|---|---|
| 인터넷 진입 | 공식 Cloudflare IPv4 → ALB TCP 443 |
| 서비스 간 통신 | Gateway·업무·Eureka·Mock Bank의 실제 호출 방향과 포트 |
| 저장소 | DB를 쓰는 앱 → RDS 5432, User → 세션 Redis 16379, Exchange → 캐시 Redis 16380, Wallet → Kafka 9092 |
| 수집 | 모니터링 → 앱 메트릭, 로그 수집기 → Loki 등 필요한 내부 연결 |
| 외부 HTTPS | App 호스트·Data·Exchange·모니터링의 TCP 443 송신 |

관리 포트·Grafana는 인터넷에 공개하지 않고 SSH 포트도 열지 않습니다. 운영 접속은 후속 IAM·Compute 구현에서 SSM으로 연결합니다. Cloudflare 목록은 코드에 확인한 값을 기록하며, 변경 시 ALB 규칙과 Gateway 신뢰 설정을 함께 검토합니다.

SG 본체와 개별 규칙을 분리해 상호 참조에 따른 생성 순환을 피합니다. `aws_vpc_security_group_rules_exclusive`가 선언한 규칙 전체를 관리하고 VPC 기본 SG는 닫습니다. 다른 도구·수동 규칙을 같은 SG에 섞으면 다음 apply에서 제거될 수 있으므로 보존할 변경을 먼저 코드에 반영합니다.

DB 계정·스키마 초기화 때만 `enable_database_bootstrap_access=true`로 Data → RDS 5432를 추가합니다. 완료 후 `false`로 되돌립니다. RDS 관리자 Secret의 임시 조회 권한은 후속 IAM 구현에서 별도로 제한합니다. 이번 코드는 SG ID까지 출력하며 실제 EC2·Task ENI에 연결하는 작업과 허용/차단 통신 검증은 후속 배치에서 수행합니다.

## AWS에 접속하지 않는 코드 검증

```bash
terraform fmt -check -recursive infra/aws
terraform -chdir=infra/aws/bootstrap init -backend=false
terraform -chdir=infra/aws/bootstrap validate
terraform -chdir=infra/aws/bootstrap test
terraform -chdir=infra/aws init -backend=false
terraform -chdir=infra/aws validate
terraform -chdir=infra/aws test
```

`-backend=false`는 이 검증에서 S3 Backend 초기화를 생략하는 옵션입니다. 실제 운영 명령에 계속 붙이는 설정이 아닙니다. `test`는 AWS Provider 경계를 mock으로 바꾸고 Terraform이 평가한 리소스 설정·정책을 검사합니다. 모의 입력과 식별자는 실제 AWS 리소스가 아닙니다.

Terraform test의 run은 기본적으로 apply입니다. 기본 구성 테스트는 `mock_provider "aws"`와 `command = plan`을 명시합니다. 계산된 SG 규칙 ID의 참조를 확인하는 `security_ownership.tftest.hcl` 한 파일은 모든 AWS 동작을 mock 처리한 `command = apply`를 명시합니다. 이 경우에도 실제 AWS 리소스는 생성하지 않습니다. 새 Provider나 실제 AWS 시험을 추가할 때는 모의 처리 범위와 실행·정리 대상을 별도로 검토합니다.

첫 PR의 구성 테스트는 총 16개입니다. State 보안·IAM 범위 3개, 네트워크 파생·입력·라우팅 7개, SG 허용 행렬·공개 범위·초기화 옵션·규칙 소유권 6개를 확인합니다. 넓은 공개 CIDR·불필요한 DB 연결·HTTPS 포트 확대·규칙 관리 목록 누락을 넣은 임시 복사본에서 해당 테스트가 실패하는 것도 확인했습니다.

## 실제 AWS 검증 결과

2026-11-10, 선택한 프로젝트 IAM 사용자·서울 리전에서 확인한 결과입니다.

| 대상 | 확인한 결과 |
|---|---|
| S3 생성 | 저장한 plan 적용: 버킷 1개·보안 설정 5개 생성, 기존 항목 변경·삭제 없음 |
| 실제 S3 설정 | 공개 차단 4개·AES256·버전 관리·ACL 비활성화·TLS 거절 정책 확인 |
| State 이전 | S3 객체의 암호화·버전과 이전 전후 6개 리소스 ID·속성·출력값 일치 확인 |
| 동시 잠금 | 한 Terraform 작업의 잠금 보유 중 다른 plan 차단, 첫 작업 취소 후 해제·State 불변 확인 |
| 이전 후 재검사 | 실제 bootstrap plan: `No changes` |
| 네트워크·SG 적용 | 156개 설정 적용, 기존 항목 변경·삭제 없음 |
| 실제 네트워크 | VPC DNS·서브넷 6개·주소/AZ·라우팅 연결 확인, NAT available·EIP 연결 확인 |
| 실제 SG | 역할 SG 14개·규칙 101개·규칙 관리 목록이 State와 일치, 기본 SG 닫힘 확인 |
| 적용 후 재검사 | 실제 본 root plan: `No changes` |

NAT Gateway 1개와 공인 IPv4 1개가 실제로 실행 중입니다. 서울 리전 기본 요금 합계는 약 USD0.064/h(24시간 약 USD1.54)이며 NAT 처리량·전송·세금은 별도입니다. 앱 배치 전에도 켜둔 기간의 요금이 발생합니다.

이번에는 현재 IAM 사용자의 S3·네트워크·SG 생성 및 AWS API 설정과 State의 일치를 확인했습니다. 최소 권한 역할별 요청 허용·거절, EC2/ECS·앱 배치 후 실제 패킷 접속·차단·외부 송신, 앱·데이터·Prometheus·Alloy·Loki·Grafana 배치는 후속 단계에 남아 있습니다. 설정 대조를 실제 업무 통신 시험으로 대신했다고 보지 않습니다.

근거: [Terraform S3 Backend](https://developer.hashicorp.com/terraform/language/backend/s3), [Provider mocking](https://developer.hashicorp.com/terraform/language/tests/mocking), [Terraform 파일 구성](https://developer.hashicorp.com/terraform/language/files).
