# Task 정의·공개 진입점·경보가 실행 계약(서비스 목록·SG 포트·Secret 이름)과 같은 값을 쓰는지 검사합니다.
mock_provider "aws" {
  override_during = plan
  mock_data "aws_availability_zones" {
    defaults = { names = ["ap-northeast-2a", "ap-northeast-2c"] }
  }
  mock_data "aws_ami" {
    defaults = {
      id   = "ami-0123456789abcdef0", architecture = "x86_64",
      name = "al2023-ami-ecs-hvm-test", root_device_name = "/dev/xvda",
    }
  }
  # 연결된 자원이 ARN 형식을 검사하므로 형식이 맞는 모의 ARN을 줍니다.
  mock_resource "aws_lb" {
    defaults = {
      arn        = "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:loadbalancer/app/travelpay-production/0123456789abcdef"
      arn_suffix = "app/travelpay-production/0123456789abcdef"
    }
  }
  mock_resource "aws_lb_listener" {
    defaults = { arn = "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:listener/app/travelpay-production/0123456789abcdef/0123456789abcdef" }
  }
  mock_resource "aws_lb_target_group" {
    defaults = {
      arn        = "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:targetgroup/travelpay-production-gateway/0123456789abcdef"
      arn_suffix = "targetgroup/travelpay-production-gateway/0123456789abcdef"
    }
  }
  mock_resource "aws_acm_certificate" {
    defaults = { arn = "arn:aws:acm:ap-northeast-2:123456789012:certificate/00000000-0000-0000-0000-000000000000" }
  }
  mock_resource "aws_sns_topic" {
    defaults = { arn = "arn:aws:sns:ap-northeast-2:123456789012:travelpay-production-alerts" }
  }
}

override_resource {
  override_during = plan
  target          = aws_db_instance.main[0]
  values = {
    address            = "travelpay-production.test.ap-northeast-2.rds.amazonaws.com"
    master_user_secret = [{ secret_arn = "arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:rds-test", kms_key_id = "", secret_status = "active" }]
  }
}

override_resource {
  override_during = plan
  target          = aws_instance.data[0]
  values          = { id = "i-0123456789abcdef0", private_ip = "10.20.20.10" }
}

override_resource {
  override_during = plan
  target          = aws_subnet.network["public_a"]
  values          = { id = "subnet-00000000000000000" }
}

override_resource {
  override_during = plan
  target          = aws_subnet.network["public_b"]
  values          = { id = "subnet-00000000000000001" }
}

override_resource {
  override_during = plan
  target          = aws_security_group.boundary["alb"]
  values          = { id = "sg-00000000000000a1b" }
}

override_resource {
  override_during = plan
  target          = aws_subnet.network["app_a"]
  values          = { id = "subnet-00000000000000010" }
}

override_resource {
  override_during = plan
  target          = aws_subnet.network["app_b"]
  values          = { id = "subnet-00000000000000011" }
}

override_resource {
  override_during = plan
  target          = aws_ecs_task_definition.application["wallet"]
  values          = { arn = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/travelpay-production-wallet-service:1" }
}

override_resource {
  override_during = plan
  target          = aws_ecs_task_definition.application["user"]
  values          = { arn = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/travelpay-production-user-service:1" }
}

# Eureka·Mock Bank가 서로의 Cloud Map 이름에 등록되지 않는지 서로 다른 값으로 구분합니다.
override_resource {
  override_during = plan
  target          = aws_service_discovery_service.application["eureka"]
  values          = { arn = "arn:aws:servicediscovery:ap-northeast-2:123456789012:service/srv-eureka000000000" }
}

override_resource {
  override_during = plan
  target          = aws_service_discovery_service.application["mock_bank"]
  values          = { arn = "arn:aws:servicediscovery:ap-northeast-2:123456789012:service/srv-mockbank00000000" }
}

# 원본 인증 값은 SSM에서 읽으므로 알려진 값으로 바꿔 ALB 규칙에 그대로 들어가는지 봅니다.
override_data {
  override_during = plan
  target          = data.aws_ssm_parameter.origin_auth[0]
  values          = { value = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" }
}

# Grafana 인증서·Target Group이 API 쪽 자원과 섞이지 않는지 서로 다른 값으로 구분합니다.
override_resource {
  override_during = plan
  target          = aws_acm_certificate.grafana[0]
  values          = { arn = "arn:aws:acm:ap-northeast-2:123456789012:certificate/11111111-1111-1111-1111-111111111111" }
}

override_resource {
  override_during = plan
  target          = aws_lb_target_group.grafana[0]
  values          = { arn = "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:targetgroup/travelpay-production-grafana/1111111111111111" }
}

# Service마다 자기 SG를 쓰는지 서로 다른 값으로 구분합니다.
override_resource {
  override_during = plan
  target          = aws_security_group.boundary["gateway"]
  values          = { id = "sg-000000000000000a1" }
}

override_resource {
  override_during = plan
  target          = aws_security_group.boundary["wallet"]
  values          = { id = "sg-000000000000000a2" }
}

override_resource {
  override_during = plan
  target          = aws_iam_role.task_execution["wallet"]
  values          = { arn = "arn:aws:iam::123456789012:role/travelpay-production-wallet-service-execution" }
}

override_resource {
  override_during = plan
  target          = aws_iam_role.task["wallet"]
  values          = { arn = "arn:aws:iam::123456789012:role/travelpay-production-wallet-service-task" }
}

override_resource {
  override_during = plan
  target          = aws_iam_role.task_execution["user"]
  values          = { arn = "arn:aws:iam::123456789012:role/travelpay-production-user-service-execution" }
}

override_resource {
  override_during = plan
  target          = aws_iam_role.task["user"]
  values          = { arn = "arn:aws:iam::123456789012:role/travelpay-production-user-service-task" }
}

variables {
  aws_account_id                   = "123456789012"
  alert_email                      = "ops@example.com"
  availability_zones               = { a = "ap-northeast-2a", b = "ap-northeast-2c" }
  enable_app_foundation            = true
  enable_data_foundation           = true
  enable_app_runtime               = true
  enable_app_services              = true
  enable_public_entry              = true
  app_host_count                   = 2
  ecs_ami_id                       = "ami-0123456789abcdef0"
  data_ami_id                      = "ami-0123456789abcdef1"
  enable_database_bootstrap_access = false
  enable_github_deployment         = false
  application_images = {
    gateway   = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/gateway@sha256:1111111111111111111111111111111111111111111111111111111111111111"
    eureka    = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/eureka-server@sha256:2222222222222222222222222222222222222222222222222222222222222222"
    user      = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/user-service@sha256:3333333333333333333333333333333333333333333333333333333333333333"
    wallet    = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/wallet-service@sha256:4444444444444444444444444444444444444444444444444444444444444444"
    payment   = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/payment-service@sha256:5555555555555555555555555555555555555555555555555555555555555555"
    exchange  = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/exchange-service@sha256:6666666666666666666666666666666666666666666666666666666666666666"
    ledger    = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/ledger-service@sha256:7777777777777777777777777777777777777777777777777777777777777777"
    mock_bank = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/mock-bank@sha256:8888888888888888888888888888888888888888888888888888888888888888"
  }
}

run "task_definitions_use_designed_size_roles_and_hardening" {
  command = plan

  assert {
    condition = {
      for key, task in aws_ecs_task_definition.application : key => [jsondecode(task.container_definitions)[0].cpu, task.memory]
      } == {
      gateway = [256, "768"], eureka = [256, "768"], user = [256, "1024"], wallet = [512, "1536"],
      payment = [256, "1024"], exchange = [512, "1536"], ledger = [256, "1024"], mock_bank = [256, "768"],
    }
    error_message = "8개 서비스의 CPU 예약·메모리 상한이 설계표와 같아야 합니다."
  }

  assert {
    condition     = alltrue([for task in aws_ecs_task_definition.application : task.cpu == null])
    error_message = "Task 단위 CPU는 넘을 수 없는 상한이라 기동이 크게 느려지므로 두지 않아야 합니다(컨테이너 몫만 사용)."
  }

  assert {
    condition = alltrue([
      for key, task in aws_ecs_task_definition.application : (
        task.family == "travelpay-production-${local.application_services[key].name}" &&
        task.requires_compatibilities == toset(["EC2"]) && task.network_mode == "awsvpc" &&
        one(task.runtime_platform).cpu_architecture == "X86_64"
      )
    ])
    error_message = "Task 정의는 EC2·awsvpc·x86_64로 등록해야 합니다."
  }

  assert {
    condition     = alltrue([for task in aws_ecs_task_definition.application : task.skip_destroy])
    error_message = "설정 변경으로 새 리비전을 만들 때 롤백할 이전 리비전을 남겨야 합니다."
  }

  assert {
    condition = (
      aws_ecs_task_definition.application["wallet"].execution_role_arn == "arn:aws:iam::123456789012:role/travelpay-production-wallet-service-execution" &&
      aws_ecs_task_definition.application["wallet"].task_role_arn == "arn:aws:iam::123456789012:role/travelpay-production-wallet-service-task" &&
      aws_ecs_task_definition.application["user"].execution_role_arn == "arn:aws:iam::123456789012:role/travelpay-production-user-service-execution" &&
      aws_ecs_task_definition.application["user"].task_role_arn == "arn:aws:iam::123456789012:role/travelpay-production-user-service-task"
    )
    error_message = "각 Task 정의는 같은 서비스의 실행 역할(자기 Secret만 읽음)과 Task 역할을 써야 합니다."
  }

  assert {
    condition = alltrue([
      for key, task in aws_ecs_task_definition.application : (
        jsondecode(task.container_definitions)[0].image == var.application_images[key] &&
        jsondecode(task.container_definitions)[0].readonlyRootFilesystem &&
        jsondecode(task.container_definitions)[0].mountPoints == [{ sourceVolume = "tmp", containerPath = "/tmp", readOnly = false }] &&
        one(task.volume).name == "tmp" && one(one(task.volume).docker_volume_configuration).scope == "task" &&
        jsondecode(task.container_definitions)[0].stopTimeout == 60 &&
        lookup(jsondecode(task.container_definitions)[0], "privileged", false) == false
      )
    ])
    error_message = "게시한 digest 이미지를 쓰고, 루트는 읽기 전용·/tmp만 Task 볼륨·종료 대기 60초·비특권이어야 합니다."
  }

  assert {
    condition = {
      for key, task in aws_ecs_task_definition.application : key => [for mapping in jsondecode(task.container_definitions)[0].portMappings : mapping.containerPort]
      } == {
      gateway = [8080, 18080], eureka = [8761], user = [8080], wallet = [8080],
      payment = [8080], exchange = [8080], ledger = [8080], mock_bank = [8090],
    }
    error_message = "컨테이너 포트는 업무 포트와 관리 포트여야 합니다."
  }

  assert {
    condition = alltrue([
      for key, task in aws_ecs_task_definition.application : (
        strcontains(jsondecode(task.container_definitions)[0].healthCheck.command[1], "http://localhost:${local.application_services[key].management_port}/actuator/health/liveness") &&
        jsondecode(task.container_definitions)[0].healthCheck.startPeriod == 180 &&
        jsondecode(task.container_definitions)[0].logConfiguration.logDriver == "awslogs" &&
        jsondecode(task.container_definitions)[0].logConfiguration.options["awslogs-group"] == aws_cloudwatch_log_group.application[key].name &&
        jsondecode(task.container_definitions)[0].logConfiguration.options.mode == "non-blocking"
      )
    ])
    error_message = "health는 관리 포트의 liveness를 보고, 로그는 실행 역할 권한이 있는 서비스 로그 그룹으로 보내야 합니다."
  }
}

run "secrets_are_injected_from_ssm_and_never_set_as_plain_environment" {
  command = plan

  assert {
    condition = alltrue([
      for key, task in aws_ecs_task_definition.application :
      jsondecode(task.container_definitions)[0].secrets == [
        for name in sort(local.application_services[key].secret_names) : { name = name, valueFrom = local.secret_parameter_arns[name] }
      ]
    ])
    error_message = "각 서비스는 자기 Secret만 SSM ARN으로 주입받아야 합니다(실행 역할 권한과 같은 목록)."
  }

  assert {
    condition     = [for item in jsondecode(aws_ecs_task_definition.application["wallet"].container_definitions)[0].secrets : item.name] == ["WALLET_ACCOUNT_NUMBER_KEY", "WALLET_DB_PASSWORD"]
    error_message = "Wallet은 DB 암호와 계좌번호 암호화 키를 Secret으로 받아야 합니다."
  }

  assert {
    condition = alltrue(flatten([
      for key, task in aws_ecs_task_definition.application : [
        for item in jsondecode(task.container_definitions)[0].environment :
        !endswith(item.name, "PASSWORD") && !endswith(item.name, "_KEY") && !contains(local.application_services[key].secret_names, item.name)
      ]
    ]))
    error_message = "암호·키는 일반 환경 변수로 넣으면 안 됩니다."
  }
}

run "environment_points_to_private_endpoints_and_production_profile" {
  command = plan

  assert {
    condition = alltrue([
      for key, task in aws_ecs_task_definition.application :
      tomap({ for item in jsondecode(task.container_definitions)[0].environment : item.name => item.value }) == tomap(local.task_environment[key])
    ])
    error_message = "컨테이너 환경 변수는 task_environment의 값을 빠짐없이 그대로 써야 합니다."
  }

  # 다른 서비스가 빠진 주소를 반영하는 최대 시간(서버 응답 캐시 + 목록 조회 + LoadBalancer 캐시)이 종료 전 대기보다 짧아야 하고,
  # 종료 전 대기 + OUT_OF_SERVICE 호출(entrypoint.sh의 curl 최대 5초) + Spring 정상 종료(단계별 기본 30초)가 stopTimeout 안에 끝나야 합니다.
  assert {
    condition = alltrue([
      for key in ["gateway", "user", "wallet", "payment", "exchange", "ledger"] : (
        tonumber(local.task_environment.eureka.EUREKA_SERVER_RESPONSE_CACHE_UPDATE_INTERVAL_MS) / 1000 +
        tonumber(local.task_environment[key].EUREKA_CLIENT_REGISTRY_FETCH_INTERVAL_SECONDS) +
        tonumber(trimsuffix(local.task_environment[key].SPRING_CLOUD_LOADBALANCER_CACHE_TTL, "s")) <
        tonumber(local.task_environment[key].TRAVELPAY_DRAIN_SECONDS) &&
        tonumber(local.task_environment[key].TRAVELPAY_DRAIN_SECONDS) + 5 + 30 <= jsondecode(aws_ecs_task_definition.application[key].container_definitions)[0].stopTimeout &&
        local.task_environment[key].TRAVELPAY_SERVICE_PORT == tostring(local.application_services[key].port) &&
        local.task_environment[key].TRAVELPAY_EUREKA_URL == local.task_environment[key].EUREKA_CLIENT_SERVICEURL_DEFAULTZONE
      )
    ])
    error_message = "캐시 반영 시간 < 종료 전 대기, 종료 전 대기 + 종료 처리 <= stopTimeout이어야 하고 OUT_OF_SERVICE 대상(주소·포트)이 등록 정보와 같아야 합니다."
  }

  assert {
    condition = alltrue([
      for key, task in aws_ecs_task_definition.application : (
        { for item in jsondecode(task.container_definitions)[0].environment : item.name => item.value }.SPRING_CONFIG_NAME == "application-prod" &&
        { for item in jsondecode(task.container_definitions)[0].environment : item.name => item.value }.SPRING_PROFILES_ACTIVE == "prod" &&
        jsondecode({ for item in jsondecode(task.container_definitions)[0].environment : item.name => item.value }.SPRING_APPLICATION_JSON).management.metrics.distribution["percentiles-histogram"]["http.server.requests"]
      )
    ])
    error_message = "모든 서비스는 운영 설정 파일·prod 프로필(Mock Bank 장애 주입 비활성)·HTTP histogram을 써야 합니다."
  }

  assert {
    condition = {
      for key, task in aws_ecs_task_definition.application : key => regex("-Xmx([0-9]+)m", { for item in jsondecode(task.container_definitions)[0].environment : item.name => item.value }.JAVA_TOOL_OPTIONS)[0]
      } == {
      gateway = "384", eureka = "384", user = "512", wallet = "768", payment = "512", exchange = "768", ledger = "512", mock_bank = "384",
    }
    error_message = "힙 상한은 Task 메모리의 절반이어야 합니다."
  }

  assert {
    condition = alltrue([
      for key in ["user", "wallet", "payment", "exchange", "ledger", "mock_bank"] : (
        { for item in jsondecode(aws_ecs_task_definition.application[key].container_definitions)[0].environment : item.name => item.value }["${upper(key)}_DB_URL"] ==
        "jdbc:postgresql://travelpay-production.test.ap-northeast-2.rds.amazonaws.com:5432/travelpay?sslmode=verify-full&sslrootcert=/opt/travelpay/rds-ca.pem" &&
        { for item in jsondecode(aws_ecs_task_definition.application[key].container_definitions)[0].environment : item.name => item.value }["${upper(key)}_DB_USERNAME"] == local.application_services[key].db_username &&
        { for item in jsondecode(aws_ecs_task_definition.application[key].container_definitions)[0].environment : item.name => item.value }.SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE == "5"
      )
    ])
    error_message = "DB 서비스는 RDS를 인증서 검증(verify-full)으로 접속하고 자기 계정·풀 상한 5를 써야 합니다."
  }

  assert {
    condition = toset([
      for key, task in aws_ecs_task_definition.application : key
      if contains([for item in jsondecode(task.container_definitions)[0].environment : item.name], "EUREKA_CLIENT_SERVICEURL_DEFAULTZONE")
    ]) == toset([for name, connection in local.tcp_connections : connection.source if connection.target == "eureka" && connection.source != "monitoring"])
    error_message = "Eureka 주소를 받는 서비스와 SG가 Eureka 접근을 허용한 서비스가 같아야 합니다."
  }

  assert {
    condition = alltrue([
      for key in ["gateway", "user", "wallet", "payment", "exchange", "ledger"] : (
        { for item in jsondecode(aws_ecs_task_definition.application[key].container_definitions)[0].environment : item.name => item.value }.EUREKA_CLIENT_SERVICEURL_DEFAULTZONE == "http://eureka.travelpay.internal:8761/eureka/" &&
        { for item in jsondecode(aws_ecs_task_definition.application[key].container_definitions)[0].environment : item.name => item.value }.TRAVELPAY_EUREKA_APP == upper(local.application_services[key].name) &&
        { for item in jsondecode(aws_ecs_task_definition.application[key].container_definitions)[0].environment : item.name => item.value }.EUREKA_INSTANCE_PREFER_IP_ADDRESS == "true"
      )
    ])
    error_message = "Eureka 클라이언트는 사설 DNS의 Eureka에 IP로 등록하고 종료 시 자기 앱 이름으로 OUT_OF_SERVICE를 알려야 합니다."
  }

  assert {
    condition = (
      { for item in jsondecode(aws_ecs_task_definition.application["user"].container_definitions)[0].environment : item.name => item.value }.REDIS_SESSION_HOST == "data.travelpay.internal" &&
      { for item in jsondecode(aws_ecs_task_definition.application["user"].container_definitions)[0].environment : item.name => item.value }.REDIS_SESSION_PORT == "16379" &&
      { for item in jsondecode(aws_ecs_task_definition.application["exchange"].container_definitions)[0].environment : item.name => item.value }.REDIS_CACHE_PORT == "16380" &&
      { for item in jsondecode(aws_ecs_task_definition.application["wallet"].container_definitions)[0].environment : item.name => item.value }.KAFKA_BOOTSTRAP_SERVERS == "data.travelpay.internal:9092" &&
      { for item in jsondecode(aws_ecs_task_definition.application["wallet"].container_definitions)[0].environment : item.name => item.value }.MOCK_BANK_URL == "http://mock-bank.travelpay.internal:8090" &&
      { for item in jsondecode(aws_ecs_task_definition.application["gateway"].container_definitions)[0].environment : item.name => item.value }.GATEWAY_CLOUDFLARE_PROXY_ADDRESSES == "10.20.0.0/24,10.20.1.0/24" &&
      { for item in jsondecode(aws_ecs_task_definition.application["eureka"].container_definitions)[0].environment : item.name => item.value }.EUREKA_SERVER_RESPONSE_CACHE_UPDATE_INTERVAL_MS == "5000" &&
      { for item in jsondecode(aws_ecs_task_definition.application["eureka"].container_definitions)[0].environment : item.name => item.value }.EUREKA_SERVER_ENABLE_SELF_PRESERVATION == "false"
    )
    error_message = "Redis·Kafka·Mock Bank·ALB 주소와 Eureka 응답 캐시·자기 보호 설정이 운영 값과 같아야 합니다."
  }
}

run "service_ports_match_security_group_rules" {
  command = plan

  assert {
    condition = alltrue([
      for name, connection in local.tcp_connections : connection.port == local.application_services[connection.target].port
      if contains(keys(local.application_services), connection.target) && !contains(["alb", "monitoring"], connection.source)
    ])
    error_message = "서비스 간 SG 포트가 실행 계약의 업무 포트와 같아야 합니다."
  }

  assert {
    condition = alltrue([
      for key, service in local.application_services : local.tcp_connections["monitoring_${key}"].port == service.management_port
    ])
    error_message = "지표 수집 SG 포트가 health·지표를 여는 관리 포트와 같아야 합니다."
  }

  assert {
    condition = (
      local.tcp_connections.alb_gateway_api.port == aws_lb_target_group.gateway[0].port &&
      tostring(local.tcp_connections.alb_gateway_health.port) == one(aws_lb_target_group.gateway[0].health_check).port
    )
    error_message = "ALB가 보내는 업무·상태 확인 포트를 SG가 허용해야 합니다."
  }
}

run "public_entry_accepts_only_the_api_host_over_tls" {
  command = plan

  assert {
    condition = (
      !aws_lb.api[0].internal && aws_lb.api[0].load_balancer_type == "application" && aws_lb.api[0].drop_invalid_header_fields &&
      aws_lb.api[0].subnets == toset(["subnet-00000000000000000", "subnet-00000000000000001"]) &&
      aws_lb.api[0].security_groups == toset(["sg-00000000000000a1b"]) &&
      aws_lb.api[0].idle_timeout > 900
    )
    error_message = "ALB는 Public A/B에 두고 Cloudflare만 허용한 SG를 쓰며 잘못된 헤더를 버리고, Cloudflare 연결 재사용(900초)보다 늦게 끊어야 합니다."
  }

  assert {
    condition = (
      aws_lb_listener.https[0].port == 443 && aws_lb_listener.https[0].protocol == "HTTPS" &&
      aws_lb_listener.https[0].ssl_policy == "ELBSecurityPolicy-TLS13-1-2-2021-06" &&
      aws_lb_listener.https[0].certificate_arn == aws_acm_certificate_validation.api[0].certificate_arn &&
      aws_acm_certificate_validation.api[0].certificate_arn == aws_acm_certificate.api[0].arn &&
      one(aws_lb_listener.https[0].default_action).type == "fixed-response" &&
      one(one(aws_lb_listener.https[0].default_action).fixed_response).status_code == "404"
    )
    error_message = "HTTPS만 열고 발급 확인을 거친 인증서를 쓰며 기본 응답은 404여야 합니다."
  }

  # Grafana 인증서는 쓰고 있는 API 인증서를 바꾸지 않도록 따로 발급합니다.
  assert {
    condition = (
      aws_acm_certificate.grafana[0].domain_name == "grafana.travelpay.store" && aws_acm_certificate.grafana[0].validation_method == "DNS" &&
      length(aws_acm_certificate.grafana[0].subject_alternative_names) <= 1 && aws_acm_certificate.api[0].domain_name == "api.travelpay.store"
    )
    error_message = "Grafana는 API와 별도의 DNS 검증 인증서를 써야 합니다."
  }

  assert {
    condition = (
      [for condition in aws_lb_listener_rule.api[0].condition : one(condition.host_header).values if length(condition.host_header) > 0] == [toset(["api.travelpay.store"])] &&
      one(aws_lb_listener_rule.api[0].action).type == "forward" &&
      one(aws_lb_listener_rule.api[0].action).target_group_arn == aws_lb_target_group.gateway[0].arn
    )
    error_message = "API 도메인으로 온 요청만 Gateway로 보내야 합니다."
  }

  assert {
    condition = (
      aws_lb_target_group.gateway[0].target_type == "ip" && aws_lb_target_group.gateway[0].protocol == "HTTP" &&
      aws_lb_target_group.gateway[0].deregistration_delay == "30" &&
      one(aws_lb_target_group.gateway[0].health_check).path == "/actuator/health/readiness" &&
      one(aws_lb_target_group.gateway[0].health_check).matcher == "200"
    )
    error_message = "Gateway Target은 awsvpc IP로 등록하고 관리 포트의 readiness로 판단해야 합니다."
  }
}

run "alarms_cover_database_hosts_and_entry_point" {
  command = plan

  assert {
    condition = toset(keys(aws_cloudwatch_metric_alarm.service)) == toset([
      "rds_cpu", "rds_memory", "rds_storage", "rds_connections", "data_memory",
      "data_disk_root", "data_disk_kafka", "data_disk_monitoring", "app_memory", "app_disk", "alb_5xx", "gateway_5xx",
    ])
    error_message = "RDS·Data·앱 호스트·ALB 경보가 모두 있어야 합니다."
  }

  assert {
    condition = alltrue([
      for alarm in aws_cloudwatch_metric_alarm.service :
      alarm.alarm_actions == toset([aws_sns_topic.alerts[0].arn]) && alarm.ok_actions == toset([aws_sns_topic.alerts[0].arn]) && alarm.period == 300
    ])
    error_message = "모든 경보는 발생과 복구를 같은 SNS 주제로 알려야 합니다."
  }

  assert {
    condition = alltrue([
      for key, alarm in aws_cloudwatch_metric_alarm.service :
      alarm.evaluation_periods == (contains(["alb_5xx", "gateway_5xx"], key) ? 1 : 3) && alarm.datapoints_to_alarm == alarm.evaluation_periods
    ])
    error_message = "상태 값 경보는 15분 연속, 5xx 건수 경보는 5분 단위로 판단해야 합니다."
  }

  assert {
    condition = (
      aws_sns_topic_subscription.alert_email[0].protocol == "email" &&
      nonsensitive(aws_sns_topic_subscription.alert_email[0].endpoint) == "ops@example.com"
    )
    error_message = "경보 주제는 입력한 이메일로 전달해야 합니다."
  }

  assert {
    condition = (
      aws_cloudwatch_metric_alarm.service["data_disk_kafka"].dimensions == tomap({ InstanceId = "i-0123456789abcdef0", path = "/srv/travelpay/kafka", fstype = "ext4" }) &&
      aws_cloudwatch_metric_alarm.service["data_disk_root"].dimensions == tomap({ InstanceId = "i-0123456789abcdef0", path = "/", fstype = "xfs" }) &&
      aws_cloudwatch_metric_alarm.service["app_memory"].dimensions == tomap({ AutoScalingGroupName = "travelpay-production-app" }) &&
      aws_cloudwatch_metric_alarm.service["alb_5xx"].dimensions == tomap({ LoadBalancer = "app/travelpay-production/0123456789abcdef" }) &&
      # Grafana도 같은 ALB에 있으므로 Gateway 응답 5xx는 Gateway Target Group으로 좁힙니다.
      aws_cloudwatch_metric_alarm.service["gateway_5xx"].dimensions == tomap({
        LoadBalancer = "app/travelpay-production/0123456789abcdef"
        TargetGroup  = "targetgroup/travelpay-production-gateway/0123456789abcdef"
      }) &&
      aws_cloudwatch_metric_alarm.service["rds_connections"].dimensions == tomap({ DBInstanceIdentifier = "travelpay-production" })
    )
    error_message = "경보 차원이 Agent·RDS·ALB가 실제로 발행하는 지표의 차원과 같아야 합니다."
  }

  assert {
    condition = (
      alltrue([for key in ["data_memory", "data_disk_root", "app_memory", "app_disk"] : aws_cloudwatch_metric_alarm.service[key].treat_missing_data == "breaching"]) &&
      alltrue([for key in ["alb_5xx", "gateway_5xx"] : aws_cloudwatch_metric_alarm.service[key].treat_missing_data == "notBreaching"])
    )
    error_message = "호스트 지표가 끊기면 장애로 알리고, 요청이 없어 ALB 지표가 없을 때는 알리지 않아야 합니다."
  }
}

run "runtime_contract_matches_application_configs" {
  command = plan

  # 서비스 담당자가 이름·포트를 바꾸면 SG·Target Group·health 확인이 배포 때에야 실패하므로 앱 설정 파일과 직접 대조합니다.
  assert {
    condition = alltrue([
      for key, service in local.application_services : (
        yamldecode(file("${path.module}/../../${service.name}/src/main/resources/application-prod.yml")).spring.application.name == service.name &&
        yamldecode(file("${path.module}/../../${service.name}/src/main/resources/application-prod.yml")).server.port == service.port &&
        try(
          tonumber(regex(":([0-9]+)}$", yamldecode(file("${path.module}/../../${service.name}/src/main/resources/application-prod.yml")).management.server.port)[0]),
          yamldecode(file("${path.module}/../../${service.name}/src/main/resources/application-prod.yml")).server.port,
        ) == service.management_port
      )
    ])
    error_message = "실행 계약의 서비스 이름·업무 포트·관리 포트가 각 서비스의 application-prod.yml과 같아야 합니다."
  }

  # 운영 DB 계정 이름은 로컬 예시와 같게 둡니다. 이름이 갈라지면 운영 계정을 찾거나 권한을 확인할 때 헷갈립니다
  # (2026-11-12 mock-bank 계정이 로컬 mock_bank_app·운영 bank_app으로 갈라진 것을 발견해 맞춤).
  assert {
    condition = alltrue([
      for key, service in local.application_services :
      service.db_username == null || try(regex("(?m)^${local.database_env_prefixes[key]}_USERNAME=([a-z0-9_]+)$", file("${path.module}/../../.env.example"))[0], null) == service.db_username
    ])
    error_message = "실행 계약의 DB 계정 이름은 .env.example의 로컬 계정 이름과 같아야 합니다."
  }

  # 운영 Task는 application-prod.yml만 읽고 기본값 없는 ${이름}을 환경 변수에서 찾습니다. 빠지면 서비스가 시작하지 못합니다.
  assert {
    condition = alltrue(flatten([
      for key, service in local.application_services : [
        for match in regexall("\\$\\{([A-Z][A-Z0-9_]*)\\}", file("${path.module}/../../${service.name}/src/main/resources/application-prod.yml")) :
        contains(concat(
          [for item in jsondecode(aws_ecs_task_definition.application[key].container_definitions)[0].environment : item.name],
          [for item in try(jsondecode(aws_ecs_task_definition.application[key].container_definitions)[0].secrets, []) : item.name],
        ), match[0])
      ]
    ]))
    error_message = "application-prod.yml의 기본값 없는 자리표시자는 모두 Task 정의의 환경 변수나 비밀값이 채워야 합니다."
  }

  # 운영 Task는 application.yml을 읽지 않습니다. DB 커넥션 풀·서비스 호출 설정이 개발 설정과 다르면 장애 때 개발에서 확인한
  # 시간보다 오래 붙잡습니다(2026-11-12 Ledger 누락: DB 무응답 시 커넥션 대기 30초, 호출하는 쪽은 5~6초에 끊음).
  # 파일을 해석하지 못하면(예: 문서 구분 --- 추가) 검사가 꺼진 채 통과하지 않도록 해석 가능 여부도 확인합니다.
  assert {
    condition = alltrue([
      for service in local.application_services : can(yamldecode(file("${path.module}/../../${service.name}/src/main/resources/application.yml"))) && can(yamldecode(file("${path.module}/../../${service.name}/src/main/resources/application-prod.yml")))
      ]) && alltrue(flatten([
        for key, service in local.application_services : [
          for pair in [
            [try(yamldecode(file("${path.module}/../../${service.name}/src/main/resources/application.yml")).spring.datasource.hikari, null), try(yamldecode(file("${path.module}/../../${service.name}/src/main/resources/application-prod.yml")).spring.datasource.hikari, null)],
            [try(yamldecode(file("${path.module}/../../${service.name}/src/main/resources/application.yml")).spring.cloud.openfeign, null), try(yamldecode(file("${path.module}/../../${service.name}/src/main/resources/application-prod.yml")).spring.cloud.openfeign, null)],
            [try(yamldecode(file("${path.module}/../../${service.name}/src/main/resources/application.yml")).spring.cloud.gateway.server.webflux.httpclient, null), try(yamldecode(file("${path.module}/../../${service.name}/src/main/resources/application-prod.yml")).spring.cloud.gateway.server.webflux.httpclient, null)],
          ] : pair[0] == null || pair[0] == pair[1]
        ]
    ]))
    error_message = "application.yml의 DB 커넥션 풀(Hikari)·서비스 호출(OpenFeign)·Gateway 전달(httpclient) 설정은 application-prod.yml에도 블록 전체가 같아야 합니다."
  }

  # Gateway가 다른 서비스로 넘기는 요청의 연결 대기 기본값은 30초입니다. Eureka에 남은 죽은 인스턴스로 간 요청이 그만큼
  # 붙잡히므로(2026-11-12 리허설 실측 30.0초, 2초로 둔 뒤 2.0초) 짧게 둡니다.
  assert {
    condition     = try(yamldecode(file("${path.module}/../../gateway/src/main/resources/application-prod.yml")).spring.cloud.gateway.server.webflux.httpclient["connect-timeout"], 30000) <= 5000
    error_message = "Gateway의 다른 서비스 연결 대기(httpclient.connect-timeout)는 5초 이하로 둬야 합니다."
  }
}

run "services_run_each_app_once_with_rolling_and_rollback" {
  command = plan

  assert {
    condition = alltrue([
      for key, service in local.application_services : (
        aws_ecs_service.application[key].name == service.name && aws_ecs_service.application[key].desired_count == 1
      )
      ]) && (
      aws_ecs_service.application["wallet"].task_definition == "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/travelpay-production-wallet-service:1" &&
      aws_ecs_service.application["user"].task_definition == "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/travelpay-production-user-service:1"
    )
    error_message = "8개 앱마다 같은 이름의 Service가 자기 Task 정의로 Task 1개를 실행해야 합니다."
  }

  # CD 배포 역할은 travelpay-production 클러스터의 이 이름들만 갱신할 수 있습니다(tests/identity.tftest.hcl이 정책 ARN을 대조).
  assert {
    condition = aws_ecs_cluster.main[0].name == "travelpay-production" && toset([for service in aws_ecs_service.application : service.name]) == toset([
      "gateway", "eureka-server", "user-service", "wallet-service", "payment-service", "exchange-service", "ledger-service", "mock-bank",
    ])
    error_message = "Service 이름과 클러스터가 CD 배포 역할이 갱신할 수 있는 대상과 같아야 합니다."
  }

  assert {
    condition = alltrue([
      for service in aws_ecs_service.application : (
        service.deployment_minimum_healthy_percent == 100 && service.deployment_maximum_percent == 200 &&
        one(service.deployment_circuit_breaker).enable && one(service.deployment_circuit_breaker).rollback &&
        service.wait_for_steady_state
      )
    ])
    error_message = "새 Task가 정상이 된 뒤 이전 Task를 내리고, 실패하면 되돌리며, apply가 그 결과를 기다려야 합니다."
  }

  # 생략하면 AWS가 클러스터 기본값을 기록해 다음 plan부터 갱신이 거절되므로 명시해야 합니다.
  assert {
    condition = alltrue([
      for service in aws_ecs_service.application :
      [for item in service.capacity_provider_strategy : [item.capacity_provider, item.weight]] == [[aws_ecs_capacity_provider.app[0].name, 1]]
    ])
    error_message = "Service는 앱 호스트 Capacity Provider를 명시해야 합니다."
  }

  assert {
    condition = alltrue([
      for service in aws_ecs_service.application : (
        one(service.network_configuration).subnets == toset(["subnet-00000000000000010", "subnet-00000000000000011"]) &&
        length(one(service.network_configuration).security_groups) == 1 &&
        one(service.network_configuration).assign_public_ip != true
      )
      ]) && (
      one(aws_ecs_service.application["gateway"].network_configuration).security_groups == toset(["sg-000000000000000a1"]) &&
      one(aws_ecs_service.application["wallet"].network_configuration).security_groups == toset(["sg-000000000000000a2"])
    )
    error_message = "Task는 App 서브넷에 공인 IP 없이 뜨고, 자기 역할의 SG 하나만 써야 합니다."
  }

  # RDS max_connections=100이고 연결 경보는 80입니다. 모든 DB 서비스가 한꺼번에 롤링해도 풀 상한 합계가 60을 넘지 않아야 합니다.
  assert {
    condition = sum([
      for key, service in aws_ecs_service.application :
      service.desired_count * service.deployment_maximum_percent / 100 * tonumber(local.task_environment[key].SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE)
      if local.application_services[key].db_username != null
    ]) <= 60
    error_message = "롤링 중 DB 커넥션 풀 상한 합계가 60을 넘으면 안 됩니다."
  }
}

run "only_fixed_name_services_use_cloud_map_and_only_gateway_joins_the_alb" {
  command = plan

  assert {
    condition = alltrue([
      for key, service in aws_ecs_service.application :
      contains(["eureka", "mock_bank"], key) ? (
        length(service.service_registries) == 1 &&
        one(service.service_registries).registry_arn == aws_service_discovery_service.application[key].arn
      ) : length(service.service_registries) == 0
    ])
    error_message = "다른 앱이 고정 이름으로 찾는 Eureka·Mock Bank만 자기 Cloud Map 이름에 등록해야 합니다."
  }

  assert {
    condition = (
      [for item in aws_ecs_service.application["gateway"].load_balancer : [item.target_group_arn, item.container_name, item.container_port]] ==
      [[aws_lb_target_group.gateway[0].arn, "gateway", 8080]] &&
      aws_ecs_service.application["gateway"].health_check_grace_period_seconds == 180 &&
      alltrue([
        for key, service in aws_ecs_service.application :
        length(service.load_balancer) == 0 && service.health_check_grace_period_seconds == null if key != "gateway"
      ])
    )
    error_message = "Gateway만 업무 포트로 ALB Target에 등록하고 기동 유예 180초를 둬야 합니다(다른 서비스는 Task 정의의 시작 유예가 같은 역할)."
  }
}

run "runtime_without_service_activation_starts_no_tasks" {
  command = plan
  variables {
    enable_public_entry = false
    enable_app_services = false
  }

  assert {
    condition     = length(aws_ecs_service.application) == 0 && length(aws_ecs_task_definition.application) == 8
    error_message = "Service 활성화 전에는 Task 정의만 있고 상시 실행 Task가 없어야 합니다."
  }
}

run "public_entry_accepts_only_requests_carrying_the_cloudflare_origin_header" {
  command = plan

  # 헤더 이름은 Gateway가 업무 서비스로 넘기기 전에 지우는 이름과 같아야 비밀값이 서비스 로그·요청에 남지 않습니다.
  assert {
    condition = alltrue([
      for rule in [aws_lb_listener_rule.api[0], aws_lb_listener_rule.grafana[0]] :
      [for condition in rule.condition : one(condition.http_header).http_header_name if length(condition.http_header) > 0] == [
        regex("ORIGIN_AUTH = \"([^\"]+)\"", file("${path.module}/../../gateway/src/main/java/com/travelpay/gateway/request/InternalHeaderResetFilter.java"))[0]
      ] &&
      nonsensitive([for condition in rule.condition : one(condition.http_header).values if length(condition.http_header) > 0]) == [
        toset(["0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"])
      ]
    ])
    error_message = "API·Grafana 규칙은 Cloudflare가 붙인 원본 인증 헤더(Gateway가 지우는 이름)와 SSM 값이 모두 맞을 때만 보내야 합니다."
  }

  assert {
    condition = (
      [for condition in aws_lb_listener_rule.grafana[0].condition : one(condition.host_header).values if length(condition.host_header) > 0] == [toset(["grafana.travelpay.store"])] &&
      one(aws_lb_listener_rule.grafana[0].action).target_group_arn == "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:targetgroup/travelpay-production-grafana/1111111111111111" &&
      aws_lb_listener_rule.grafana[0].priority != aws_lb_listener_rule.api[0].priority &&
      one(aws_lb_listener.https[0].default_action).type == "fixed-response"
    )
    error_message = "Grafana 이름으로 온 요청만 Grafana로 보내고, 맞는 규칙이 없으면 기본 404여야 합니다."
  }

  assert {
    condition = (
      aws_lb_listener_certificate.grafana[0].listener_arn == aws_lb_listener.https[0].arn &&
      aws_lb_listener_certificate.grafana[0].certificate_arn == aws_acm_certificate_validation.grafana[0].certificate_arn &&
      aws_acm_certificate_validation.grafana[0].certificate_arn == "arn:aws:acm:ap-northeast-2:123456789012:certificate/11111111-1111-1111-1111-111111111111" &&
      aws_lb_listener.https[0].certificate_arn == aws_acm_certificate_validation.api[0].certificate_arn
    )
    error_message = "같은 리스너에 발급 확인을 거친 Grafana 인증서를 추가하고, 기본 인증서는 API 인증서로 둬야 합니다."
  }

  assert {
    condition = (
      aws_lb_target_group.grafana[0].target_type == "ip" && aws_lb_target_group.grafana[0].protocol == "HTTP" && aws_lb_target_group.grafana[0].port == 3000 &&
      one(aws_lb_target_group.grafana[0].health_check).path == "/api/health" && one(aws_lb_target_group.grafana[0].health_check).matcher == "200" &&
      aws_lb_target_group_attachment.grafana[0].target_id == "10.20.20.10" && aws_lb_target_group_attachment.grafana[0].port == 3000 &&
      local.tcp_connections.alb_grafana == { source = "alb", target = "monitoring", port = 3000 }
    )
    error_message = "Grafana Target은 Data 호스트 사설 IP의 3000이고, SG는 ALB에서 모니터링 역할의 3000만 열어야 합니다."
  }

  assert {
    condition = (
      yamldecode(local.monitoring_runtime_files["monitoring-compose.yaml"]).services.grafana.ports == ["127.0.0.1:3000:3000", "$${DATA_PRIVATE_IP:?}:3000:3000"] &&
      yamldecode(local.monitoring_runtime_files["monitoring-compose.yaml"]).services.grafana.environment.GF_SERVER_ROOT_URL == "https://grafana.travelpay.store" &&
      yamldecode(local.monitoring_runtime_files["monitoring-compose.yaml"]).services.grafana.environment.GF_SECURITY_COOKIE_SECURE == "true" &&
      output.monitoring.grafana_url == "https://grafana.travelpay.store"
    )
    error_message = "공개한 Grafana는 사설 IP에서도 받고, 공개 주소를 기준으로 링크를 만들며 세션 쿠키를 HTTPS로만 보내야 합니다."
  }
}

run "without_public_entry_the_api_needs_no_origin_header_and_grafana_stays_private" {
  command = plan
  variables {
    enable_public_entry = false
  }

  assert {
    condition = (
      alltrue([for condition in aws_lb_listener_rule.api[0].condition : length(condition.http_header) == 0]) &&
      length(aws_lb_listener_rule.grafana) == 0 && length(aws_lb_target_group.grafana) == 0 && length(aws_lb_listener_certificate.grafana) == 0 &&
      length(data.aws_ssm_parameter.origin_auth) == 0 && !contains(keys(local.tcp_connections), "alb_grafana") &&
      yamldecode(local.monitoring_runtime_files["monitoring-compose.yaml"]).services.grafana.ports == ["127.0.0.1:3000:3000"] &&
      !contains(keys(yamldecode(local.monitoring_runtime_files["monitoring-compose.yaml"]).services.grafana.environment), "GF_SERVER_ROOT_URL") &&
      output.monitoring.grafana_url == null
    )
    error_message = "공개 진입점을 켜기 전에는 원본 인증 값을 읽지 않고, Grafana는 루프백에만 둬야 합니다."
  }
}

run "origin_auth_with_wildcard_characters_is_rejected" {
  command = plan

  override_data {
    target = data.aws_ssm_parameter.origin_auth[0]
    values = { value = "*" }
  }

  expect_failures = [data.aws_ssm_parameter.origin_auth]
}

run "public_entry_requires_running_services" {
  command = plan
  variables {
    enable_app_services = false
  }
  expect_failures = [var.enable_public_entry]
}

run "services_require_app_hosts" {
  command = plan
  variables {
    app_host_count = 0
  }
  expect_failures = [var.enable_app_services]
}

run "services_require_runtime" {
  command = plan
  variables {
    enable_app_runtime = false
    application_images = null
  }
  expect_failures = [var.enable_app_services]
}

run "app_host_metrics_roll_up_by_autoscaling_group" {
  command = plan

  assert {
    condition = (
      jsondecode(local.host_cloudwatch_configs["app_host"]).metrics.aggregation_dimensions == [["AutoScalingGroupName"]] &&
      contains(keys(jsondecode(local.host_cloudwatch_configs["app_host"]).metrics.append_dimensions), "AutoScalingGroupName") &&
      strcontains(base64decode(aws_launch_template.app[0].user_data), "AutoScalingGroupName")
    )
    error_message = "앱 호스트 Agent는 교체돼도 같은 경보가 보도록 ASG 이름으로 합친 지표를 보내야 합니다."
  }

  assert {
    condition = (
      jsondecode(local.host_cloudwatch_configs["data"]).metrics.append_dimensions == { InstanceId = "$${aws:InstanceId}" } &&
      !contains(keys(jsondecode(local.host_cloudwatch_configs["data"]).metrics), "aggregation_dimensions")
    )
    error_message = "Data 호스트 Agent 설정은 바꾸지 않아야 합니다(바꾸면 설정 묶음이 다시 적용됩니다)."
  }
}

run "no_app_host_alarms_without_hosts" {
  command = plan
  variables {
    enable_public_entry = false
    app_host_count      = 0
    enable_app_services = false
  }

  assert {
    condition     = !contains(keys(aws_cloudwatch_metric_alarm.service), "app_memory") && !contains(keys(aws_cloudwatch_metric_alarm.service), "app_disk")
    error_message = "앱 호스트가 0대이면 지표가 없으므로 앱 호스트 경보를 만들면 안 됩니다."
  }
}

run "reject_tag_reference_instead_of_digest" {
  command = plan
  variables {
    application_images = {
      gateway   = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/gateway:41de684a38aa"
      eureka    = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/eureka-server@sha256:2222222222222222222222222222222222222222222222222222222222222222"
      user      = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/user-service@sha256:3333333333333333333333333333333333333333333333333333333333333333"
      wallet    = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/wallet-service@sha256:4444444444444444444444444444444444444444444444444444444444444444"
      payment   = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/payment-service@sha256:5555555555555555555555555555555555555555555555555555555555555555"
      exchange  = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/exchange-service@sha256:6666666666666666666666666666666666666666666666666666666666666666"
      ledger    = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/ledger-service@sha256:7777777777777777777777777777777777777777777777777777777777777777"
      mock_bank = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/mock-bank@sha256:8888888888888888888888888888888888888888888888888888888888888888"
    }
  }
  expect_failures = [var.application_images]
}

run "reject_image_from_another_service_repository" {
  command = plan
  variables {
    application_images = {
      gateway   = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/gateway@sha256:1111111111111111111111111111111111111111111111111111111111111111"
      eureka    = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/eureka-server@sha256:2222222222222222222222222222222222222222222222222222222222222222"
      user      = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/wallet-service@sha256:3333333333333333333333333333333333333333333333333333333333333333"
      wallet    = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/wallet-service@sha256:4444444444444444444444444444444444444444444444444444444444444444"
      payment   = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/payment-service@sha256:5555555555555555555555555555555555555555555555555555555555555555"
      exchange  = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/exchange-service@sha256:6666666666666666666666666666666666666666666666666666666666666666"
      ledger    = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/ledger-service@sha256:7777777777777777777777777777777777777777777777777777777777777777"
      mock_bank = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/mock-bank@sha256:8888888888888888888888888888888888888888888888888888888888888888"
    }
  }
  expect_failures = [var.application_images]
}

run "reject_missing_service_image" {
  command = plan
  variables {
    application_images = {
      gateway = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/gateway@sha256:1111111111111111111111111111111111111111111111111111111111111111"
    }
  }
  expect_failures = [var.application_images]
}

run "runtime_requires_published_images" {
  command = plan
  variables {
    application_images = null
  }
  expect_failures = [var.application_images]
}

run "runtime_requires_both_foundations" {
  command = plan
  variables {
    enable_data_foundation = false
    data_ami_id            = null
  }
  expect_failures = [var.enable_app_runtime]
}

run "foundations_require_alert_email" {
  command = plan
  variables {
    alert_email = null
  }
  expect_failures = [var.alert_email]
}

run "reject_malformed_alert_email" {
  command = plan
  variables {
    alert_email = "ops.example.com"
  }
  expect_failures = [var.alert_email]
}
