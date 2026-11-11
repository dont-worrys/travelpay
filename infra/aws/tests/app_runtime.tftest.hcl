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
    defaults = { arn = "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:targetgroup/travelpay-production-gateway/0123456789abcdef" }
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
      { for item in jsondecode(aws_ecs_task_definition.application["eureka"].container_definitions)[0].environment : item.name => item.value }.EUREKA_SERVER_RESPONSE_CACHE_UPDATE_INTERVAL_MS == "5000"
    )
    error_message = "Redis·Kafka·Mock Bank·ALB 주소와 Eureka 응답 캐시가 운영 값과 같아야 합니다."
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

  assert {
    condition = (
      one(one(aws_lb_listener_rule.api[0].condition).host_header).values == toset(["api.travelpay.store"]) &&
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
    app_host_count = 0
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
