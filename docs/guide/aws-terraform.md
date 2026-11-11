# AWS Terraform 실행 가이드

Terraform 코드는 `infra/aws/`에서 관리합니다. Spring Boot Run과 별개이며 실제 리소스 생성은 Terraform의 `apply`로 실행합니다.

**State S3와 네트워크·SG에 이어 IAM·SSM·OIDC, ECS 실행 기반, RDS·Data EC2·Redis·Kafka의 실제 적용과 기동 검증을 완료**했습니다. 앱 Task·이미지·운영 YAML 정교화, ALB·ACM과 중앙 모니터링 배치는 후속 단계입니다.

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
├── runtime-contract.tf      # 서비스 이름·DB 계정·Secret ARN 공통 계약
├── iam-hosts.tf / iam-tasks.tf # 호스트·서비스별 최소 권한
├── github-oidc.tf            # production 환경 신뢰, 배포 권한은 기본 비활성
├── compute.tf               # ECS·빈 ASG·Capacity Provider
├── rds.tf                   # 사설 RDS·관리형 관리자 암호·복구 보호
├── data-ec2.tf / private-dns.tf # Data EC2·별도 EBS·공유 사설 DNS
├── host-observability.tf    # 호스트 메모리·디스크·초기화 로그
├── templates/               # EC2 기동·Data Compose·systemd 설정
├── scripts/                 # 적용 순서·비밀값 준비·디스크·DB 초기화 도구
├── outputs.tf               # 식별자·실행 계약 출력, 비밀값 제외
├── .terraform.lock.hcl      # Provider 버전·패키지 체크섬
├── tests/                   # Terraform 모의 검증·Python 핵심 동작 검사
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

DB 계정·스키마 초기화 때만 `enable_database_bootstrap_access=true`로 Data → RDS 5432와 해당 RDS 관리자 Secret 조회 권한, 앱 DB 암호 조회를 엽니다. 완료 후 `false`로 되돌려 plan/apply합니다. 평상시 Data는 Redis 암호 두 개만 조회합니다. EC2에는 역할별 SG를 연결하며 앱 Task 연결과 실제 허용·차단 검증은 배치 단계에서 확인합니다.

## 실행 기반 적용 순서

같은 PR의 코드도 순서대로 적용합니다. 아래 명령은 저장한 계획과 계정·비용을 검토하고 생성이 승인된 경우에만 실행합니다. `travelpay` 프로필과 `123456789012`는 실제 값으로 바꿉니다.

활성화 옵션을 앱 기반과 데이터 기반으로 분리했습니다. 이미 켠 앞 단계의 옵션은 유지하고 해당 단계만 추가로 켭니다. 초기 작성 코드가 한 PR에 있어도 실제 적용·검증은 한 단계씩 마칩니다.

| 단계 | 설정 | 적용 후 확인 |
|---|---|---|
| 4 · IAM·SSM·OIDC | 두 기반 옵션 `false`, `app_host_count=0` | 역할·정책 범위, 임시 권한 비활성, 필요한 SSM 경로 |
| 5 · ECS·앱 호스트 | `enable_app_foundation=true`, `enable_data_foundation=false`, ECS AMI 고정 | 빈 ASG·Capacity Provider 연결 → 새 plan으로 2대 기동 → SSM·ECS Agent·등록 메모리 |
| 6 · RDS·Data | 앞 단계 설정과 호스트 수를 유지하고 `enable_data_foundation=true`, Data AMI 고정 | 사설 RDS·TLS·디스크 마운트·Redis 인증·Kafka 응답·재기동 보존 |

각 단계에서 **새 plan → 검토 → 승인된 apply → 실제 확인 → 변경 없음 확인** 순서로 진행합니다. 단계 5에서 ASG와 Capacity Provider를 처음 만들 때는 호스트를 0대로 둡니다. AWS 연결 확인 후 `app_host_count=2`의 새 plan을 만들고 검사 도구를 통과시켜 적용합니다. EC2의 running 상태만으로 기동 완료를 판단하지 않습니다.

아래는 최초 4단계의 예시입니다. 이후에는 단계에 맞는 입력과 별도의 plan 파일을 사용합니다. `check-runtime-plan.py`는 ASG 초기 연결 순서만 검사하므로 전체 변경 검토를 대신하지 않습니다.

```bash
terraform -chdir=infra/aws plan -out=stage-4.tfplan
python3 infra/aws/scripts/check-runtime-plan.py infra/aws/stage-4.tfplan --profile travelpay
python3 infra/aws/scripts/prepare-parameters.py infra/aws/stage-4.tfplan --profile travelpay --account 123456789012
```

비밀값 준비 도구는 기본적으로 미리보기만 실행합니다. 승인 후 같은 명령에 `--apply`를 추가해 DB 6개·Redis 2개의 SecureString을 준비합니다. 인프라 담당자의 Linux에서 비밀값을 익명 메모리 파일로 AWS CLI에 전달하며 기존 SecureString을 덮어쓰지 않습니다. 외부 API 키와 Wallet 암호화 키는 대상이 아니며 앱 배포 전에 별도로 준비합니다.

```bash
terraform -chdir=infra/aws apply stage-4.tfplan
```

4~6단계는 이미 적용됐으므로 위 최초 생성 절차를 그대로 반복하지 않습니다. 실제 운영 입력은 Git에서 제외한 개인 파일에 유지하고 매번 최신 State로 새 plan을 만듭니다. 다음 단계는 앞 단계의 옵션·호스트 수를 유지합니다. 분리 전 또는 적용 전 State로 만든 plan을 재사용하지 않습니다.

DB 계정 초기화는 별도 작업입니다. `enable_database_bootstrap_access=true`의 plan/apply 후 SSM으로 Data에 접속해 아래 명령을 실행합니다. 암호는 출력하지 않으며 DB 역할·스키마를 한 트랜잭션으로 준비합니다. Flyway는 이후 앱 기동 시 실행합니다.

```bash
sudo python3 /opt/travelpay/data/bootstrap-database.py
```

초기화 성공과 앱 계정의 자기 스키마 접근·타 스키마 차단을 확인한 뒤 옵션을 `false`로 되돌려 새 plan/apply합니다. 공용 namespace는 Kafka 주소 때문에 먼저 만들며, 후속 Eureka·Mock Bank도 같은 `travelpay.internal`을 재사용합니다.

`data.travelpay.internal`은 Cloud Map의 서비스·인스턴스 등록으로 생성합니다. Cloud Map 소유 영역에 Route 53 레코드를 직접 쓰는 방식은 AWS에서 거절됩니다. RDS는 비밀번호 DDL이 오류의 문맥에 남지 않도록 `log_statement=none`, `log_min_error_statement=panic`, `log_error_verbosity=terse`를 사용합니다. 오류의 상세·문맥 대신 기본 오류와 애플리케이션의 안전한 진단 정보를 확인합니다.

현재 확인한 TLS는 **Data EC2 → RDS**이며 RDS 서버 인증서와 AWS CA 번들을 사용합니다. 클라이언트의 `verify-full` 접속과 평문 접속 거절을 실제로 확인했습니다. ACM 인증서를 사용하는 **브라우저 → ALB HTTPS**와 구분합니다. `rds.force_ssl`은 AWS 제어면의 설정과 실제 접속 거절로 검증하며, 이 환경에서는 PostgreSQL의 `current_setting()`에 같은 이름이 노출되지 않았습니다.

Data는 16GiB root, Kafka 30GiB, 향후 모니터링용 20GiB의 별도 암호화 gp3를 사용합니다. Kafka 3072MiB·Redis 두 개 512MiB의 컨테이너 상한을 두고, 모니터링 배치 때 전체 RAM을 다시 검증합니다. 두 EBS와 Data EC2·RDS에는 `prevent_destroy`가 있으며, RDS에는 추가 삭제 보호와 최종 스냅샷을 설정합니다. 삭제·교체는 데이터 보존과 중단 영향을 검토한 별도 작업입니다. 반복 종료 시 최종 스냅샷 이름 충돌도 확인합니다.

기동 코드 변경은 Data EC2 교체 계획을 만들 수 있고 삭제 보호가 이를 차단합니다. 자동 교체를 강행하거나 User Data만 바꾸고 적용됐다고 보지 않습니다. 승인된 유지보수에서 중단·디스크 재부착·검증 절차를 먼저 정합니다. Redis는 현재 로컬 정책처럼 영속 저장하지 않아 재시작 시 세션·견적이 사라집니다. Kafka EBS와 DB의 영속 데이터와 구분합니다.

## AWS에 접속하지 않는 코드 검증

```bash
terraform fmt -check -recursive infra/aws
terraform -chdir=infra/aws/bootstrap init -backend=false
terraform -chdir=infra/aws/bootstrap validate
terraform -chdir=infra/aws/bootstrap test
terraform -chdir=infra/aws init -backend=false
terraform -chdir=infra/aws validate
terraform -chdir=infra/aws test
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s infra/aws/tests -p 'test_*.py'
```

`-backend=false`는 이 검증에서 S3 Backend 초기화를 생략하는 옵션입니다. 실제 운영 명령에 계속 붙이는 설정이 아닙니다. `test`는 AWS Provider 경계를 mock으로 바꾸고 Terraform이 평가한 리소스 설정·정책을 검사합니다. 모의 입력과 식별자는 실제 AWS 리소스가 아닙니다.

Terraform test의 run은 기본적으로 apply입니다. 기본 구성 테스트는 `mock_provider "aws"`와 `command = plan`을 명시합니다. SG 규칙 ID 참조와 OIDC 역할 신뢰 등 적용 후 계산값이 필요한 일부 검사는 AWS 동작 전체를 mock 처리한 `command = apply`를 명시합니다. 이 경우에도 실제 AWS 리소스는 생성하지 않습니다. 새 Provider나 실제 AWS 시험을 추가할 때는 모의 처리 범위와 실행·정리 대상을 별도로 검토합니다.

DB 초기화 SQL은 별도 경계 검사로도 확인합니다. 로컬에 `postgres:18.6-bookworm` 이미지가 준비된 경우 `TRAVELPAY_TEST_POSTGRES=1`을 위 Python 검사 명령 앞에 추가합니다. 포트·호스트 볼륨·외부 네트워크 없는 임시 컨테이너에서 비슈퍼유저 초기화·재실행·스키마 격리를 검사하고 제거합니다. 실제 RDS의 확장 권한 동작과 접속 검증은 적용 후 확인합니다.

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

2026-11-11에는 4~6단계를 차례로 적용하고 다음을 확인했습니다.

| 대상 | 실제 확인 결과 |
|---|---|
| IAM·OIDC | 허용·차단 시뮬레이션 19건 통과, 일반 Task 8개와 GitHub 역할의 정책 비어 있음, GitHub 배포 권한 비활성 |
| SSM | DB 6개·Redis 2개의 SecureString 생성, 값은 Terraform State·코드·로그에 넣지 않음 |
| ECS on EC2 | m7i.large 2대·두 AZ·Capacity Provider 연결·SSM/ECS Agent 정상, Trunk ENI 실제 부착, ECS 등록 메모리 각 6759MiB |
| Data EC2 | m7i.large 1대·실제 메모리 7779MiB, Redis 두 개·Kafka 정상, 사설 바인딩·암호화 EBS·메모리 상한 확인 |
| RDS | PostgreSQL 18.6·db.t4g.medium·사설·암호화·백업 7일·삭제 보호, TLS·평문 거절·max_connections=100 확인 |
| DB 초기화 | 배포된 스크립트 최초 실행·재실행 성공, 6개 계정 자기 스키마 쓰기·타 스키마 거절, SCRAM 인증 설정 확인 |
| 임시 권한 회수 | Data → RDS TCP 차단, 관리자 Secret·앱 DB 암호 조회 AccessDenied 실제 확인 |
| Data 재기동 | Boot ID 변경, Kafka cluster ID·시험 메시지·두 EBS UUID 유지, Redis 임시 키 초기화·인증 복구, 시험 토픽 삭제 |
| 기본 관측 | 호스트 메모리 3개·디스크 5개 지표 수집, 호스트 초기화 로그 스트림 3개·보관 7일 확인 |
| 적용 후 재검사 | 본 root plan `No changes`, 구성 테스트 33개·Python 검사 22개 통과 |

EC2·RDS·Data 기반은 실행 중이며 ECS의 애플리케이션 Task는 아직 0개입니다. 외부 환율 API 키와 Wallet 암호화 키는 앱 배포 전 별도로 준비합니다. 앱 Task의 실제 업무 통신·전체 업무 흐름과 Prometheus·Alloy·Loki·Grafana 배치는 후속 단계에서 검증합니다.

근거: [Terraform S3 Backend](https://developer.hashicorp.com/terraform/language/backend/s3), [Provider mocking](https://developer.hashicorp.com/terraform/language/tests/mocking), [Terraform 파일 구성](https://developer.hashicorp.com/terraform/language/files).
