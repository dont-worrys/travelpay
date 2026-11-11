locals {
  # 설계표의 Task 크기입니다. 메모리는 넘으면 종료되는 상한이고, CPU는 배치 예약과 경합 시 보장 몫입니다.
  # 힙(Xmx)은 메모리의 절반으로 두어 메타스페이스·스레드·직접 버퍼 몫을 남깁니다.
  task_sizes = {
    gateway   = { cpu = 256, memory = 768 }
    eureka    = { cpu = 256, memory = 768 }
    user      = { cpu = 256, memory = 1024 }
    wallet    = { cpu = 512, memory = 1536 }
    payment   = { cpu = 256, memory = 1024 }
    exchange  = { cpu = 512, memory = 1536 }
    ledger    = { cpu = 256, memory = 1024 }
    mock_bank = { cpu = 256, memory = 768 }
  }

  # Eureka에 등록하고 목록을 조회하는 서비스입니다. eureka-server 자신과 Cloud Map으로 찾는 Mock Bank는 제외합니다.
  eureka_clients = toset(["gateway", "user", "wallet", "payment", "exchange", "ledger"])

  # Redis·Kafka·Eureka·Mock Bank 주소는 사설 DNS 이름과 SG가 허용한 포트에서 만듭니다(포트를 SG 규칙에서 읽어 허용 범위와 맞춤).
  # DB는 인증서의 이름과 맞아야 verify-full이 통과하므로 RDS 엔드포인트 이름을 그대로 씁니다.
  data_host_name     = var.enable_data_foundation ? "${aws_service_discovery_service.data[0].name}.${aws_service_discovery_private_dns_namespace.main[0].name}" : null
  eureka_service_url = var.enable_app_runtime ? "http://${aws_service_discovery_service.application["eureka"].name}.${aws_service_discovery_private_dns_namespace.main[0].name}:${local.application_services.eureka.port}/eureka/" : null

  task_environment = var.enable_app_runtime ? {
    for key, service in local.application_services : key => merge(
      {
        # application.yml의 로컬 전용 설정(파일 로그 등)을 읽지 않도록 운영 파일만 기본 설정으로 씁니다.
        SPRING_CONFIG_NAME = "application-prod"
        # @Profile("!prod") 빈(Mock Bank 장애 주입 등)을 끄려면 prod 프로필도 켜야 합니다.
        SPRING_PROFILES_ACTIVE = "prod"
        JAVA_TOOL_OPTIONS      = "-Xms128m -Xmx${floor(local.task_sizes[key].memory / 2)}m -XX:+ExitOnOutOfMemoryError"
        # application.yml에만 있는 histogram 설정입니다. 점이 든 지표 이름은 환경 변수 이름으로 쓸 수 없어 JSON으로 넘깁니다.
        SPRING_APPLICATION_JSON = jsonencode({
          management = { metrics = { distribution = { "percentiles-histogram" = { "http.server.requests" = true } } } }
        })
      },
      contains(local.eureka_clients, key) ? {
        EUREKA_CLIENT_SERVICEURL_DEFAULTZONE = local.eureka_service_url
        EUREKA_INSTANCE_PREFER_IP_ADDRESS    = "true"
        # 목록 조회·LoadBalancer 캐시를 5초로 줄여(기본 30초·35초) 종료 전 대기(20초) 안에 다른 서비스가 빠진 주소를 반영합니다.
        EUREKA_CLIENT_REGISTRY_FETCH_INTERVAL_SECONDS = "5"
        SPRING_CLOUD_LOADBALANCER_CACHE_TTL           = "5s"
        # 이미지 시작 스크립트가 종료 신호를 받으면 이 주소에 OUT_OF_SERVICE를 알리고 기다린 뒤 Java를 종료합니다.
        TRAVELPAY_EUREKA_URL    = local.eureka_service_url
        TRAVELPAY_EUREKA_APP    = upper(service.name)
        TRAVELPAY_SERVICE_PORT  = tostring(service.port)
        TRAVELPAY_DRAIN_SECONDS = "20"
      } : {},
      service.db_username == null ? {} : {
        # 비밀번호 파라미터 이름(USER_DB_PASSWORD 등)과 같은 접두어를 앱 설정이 씁니다.
        "${local.database_env_prefixes[key]}_URL"      = "jdbc:postgresql://${aws_db_instance.main[0].address}:${aws_db_instance.main[0].port}/${aws_db_instance.main[0].db_name}?sslmode=verify-full&sslrootcert=/opt/travelpay/rds-ca.pem"
        "${local.database_env_prefixes[key]}_USERNAME" = service.db_username
        # RDS max_connections=100입니다. DB 서비스 6개가 롤링 중 Task 2개씩이어도 60개 이하가 되도록 기본 10개를 줄입니다.
        SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = "5"
        SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE      = "1"
      },
      lookup({
        # Gateway는 이 주소(ALB 노드)에서 온 요청에만 Cloudflare가 전달한 클라이언트 IP를 신뢰합니다.
        gateway  = { GATEWAY_CLOUDFLARE_PROXY_ADDRESSES = join(",", [aws_subnet.network["public_a"].cidr_block, aws_subnet.network["public_b"].cidr_block]) }
        eureka   = { EUREKA_SERVER_RESPONSE_CACHE_UPDATE_INTERVAL_MS = "5000" }
        user     = { REDIS_SESSION_HOST = local.data_host_name, REDIS_SESSION_PORT = tostring(local.tcp_connections.user_session_redis.port) }
        exchange = { REDIS_CACHE_HOST = local.data_host_name, REDIS_CACHE_PORT = tostring(local.tcp_connections.exchange_cache_redis.port) }
        wallet = {
          KAFKA_BOOTSTRAP_SERVERS = "${local.data_host_name}:${local.tcp_connections.wallet_kafka.port}"
          MOCK_BANK_URL           = "http://${aws_service_discovery_service.application["mock_bank"].name}.${aws_service_discovery_private_dns_namespace.main[0].name}:${local.tcp_connections.wallet_bank.port}"
        }
      }, key, {}),
    )
  } : {}

  database_env_prefixes = {
    for key, service in local.application_services :
    key => trimsuffix(one([for name in service.secret_names : name if endswith(name, "_DB_PASSWORD")]), "_PASSWORD")
    if service.db_username != null
  }
}

# ECS Service와 배포는 PR4에서 만듭니다. 이 단계는 Task 정의만 등록하고 명령을 바꾼 시험 실행으로 주입 경로를 확인합니다.
resource "aws_ecs_task_definition" "application" {
  for_each = var.enable_app_runtime ? local.application_services : {}

  family                   = "${local.runtime_name_prefix}-${each.value.name}"
  requires_compatibilities = ["EC2"]
  network_mode             = "awsvpc"
  memory                   = tostring(local.task_sizes[each.key].memory)
  execution_role_arn       = aws_iam_role.task_execution[each.key].arn
  task_role_arn            = aws_iam_role.task[each.key].arn
  # 설정을 바꿔 새 리비전을 만들 때 이전 리비전을 지우지 않아 롤백할 정의를 남깁니다.
  skip_destroy = true

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  # 루트 파일시스템은 읽기 전용입니다. Tomcat·Netty 임시 파일만 Task와 함께 지워지는 볼륨에 씁니다.
  volume {
    name = "tmp"
    docker_volume_configuration {
      scope  = "task"
      driver = "local"
    }
  }

  container_definitions = jsonencode([{
    name                   = each.value.name
    image                  = var.application_images[each.key]
    essential              = true
    readonlyRootFilesystem = true
    # Task 단위 cpu는 넘을 수 없는 상한이라 0.25 vCPU에서 user-service 기동이 114초 걸렸습니다(2 vCPU 13초, 로컬 측정).
    # 컨테이너 단위 cpu는 같은 값으로 배치를 예약하고 경합 때 이 비율을 보장하며, 호스트에 남는 CPU는 더 씁니다.
    cpu = local.task_sizes[each.key].cpu
    # 시작 스크립트의 종료 전 대기(20초)와 Spring 정상 종료(기본 30초)가 끝날 시간을 둡니다.
    stopTimeout = 60
    portMappings = [
      for port in distinct([each.value.port, each.value.management_port]) : { containerPort = port, hostPort = port, protocol = "tcp" }
    ]
    environment = [
      for name in sort(keys(local.task_environment[each.key])) : { name = name, value = local.task_environment[each.key][name] }
    ]
    # 값은 SSM SecureString에 두고 실행 역할이 Task 시작 때 주입합니다. Task 정의와 State에는 ARN만 남습니다.
    secrets = [
      for name in sort(each.value.secret_names) : { name = name, valueFrom = local.secret_parameter_arns[name] }
    ]
    mountPoints = [{ sourceVolume = "tmp", containerPath = "/tmp", readOnly = false }]
    # liveness는 DB·Redis 상태를 포함하지 않아 의존 서비스 장애로 컨테이너를 다시 시작하지 않습니다.
    # 호스트 교체로 여러 Task가 한꺼번에 시작하면 각자 예약 몫만큼만 CPU를 받으므로, 그 최악의 기동 시간(0.25 vCPU 114초)보다 길게 기다립니다.
    healthCheck = {
      command     = ["CMD-SHELL", "curl --fail --silent --show-error --max-time 3 --output /dev/null http://localhost:${each.value.management_port}/actuator/health/liveness || exit 1"]
      interval    = 15
      timeout     = 5
      retries     = 3
      startPeriod = 180
    }
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.application[each.key].name
        awslogs-region        = local.runtime_region
        awslogs-stream-prefix = "app"
        # 로그 전송이 막혀도 앱의 표준 출력이 멈추지 않게 합니다. 버퍼를 넘는 로그는 버려집니다.
        mode            = "non-blocking"
        max-buffer-size = "4m"
      }
    }
  }])
}
