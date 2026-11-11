# Data의 Kafka advertised 주소가 먼저 필요하므로 공용 namespace를 이 단계에서 준비합니다.
# 이후 Eureka·Mock Bank도 이 namespace를 재사용합니다.
resource "aws_service_discovery_private_dns_namespace" "main" {
  count = var.enable_data_foundation ? 1 : 0

  name        = "travelpay.internal"
  description = "TravelPay production private service discovery"
  vpc         = aws_vpc.main.id
}

resource "aws_service_discovery_service" "data" {
  count = var.enable_data_foundation ? 1 : 0

  name = "data"
  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.main[0].id
    routing_policy = "MULTIVALUE"
    dns_records {
      type = "A"
      ttl  = 30
    }
  }
}

# Eureka와 Mock Bank는 다른 앱이 고정 주소로 찾아야 하므로 Cloud Map 이름을 둡니다. 업무 앱끼리는 Eureka로 찾습니다.
# Task 등록은 ECS Service(PR4)가 하고, 롤링으로 IP가 바뀌므로 TTL을 Data(30초)보다 짧게 둡니다.
resource "aws_service_discovery_service" "application" {
  for_each = var.enable_app_foundation && var.enable_data_foundation ? { eureka = "eureka", mock_bank = "mock-bank" } : {}

  name = each.value
  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.main[0].id
    routing_policy = "MULTIVALUE"
    dns_records {
      type = "A"
      ttl  = 10
    }
  }

  # AWS ECS 문서는 사설 namespace에 ECS가 관리하는 custom 헬스 체크를 권장합니다. 컨테이너 헬스 체크를 통과한
  # Task만 DNS에 남습니다. Provider가 안내하는 health_check_config는 Route 53 헬스 체크라 공개 namespace에만 맞습니다.
  # 빈 블록은 Provider가 API로 보내지 않으므로(6.72.0 소스) AWS가 1로 고정하는 값을 명시합니다.
  health_check_custom_config {
    failure_threshold = 1
  }
}

# Cloud Map 소유 영역의 레코드는 Route 53에 직접 쓰지 않고 등록 API가 관리합니다.
resource "aws_service_discovery_instance" "data" {
  count = var.enable_data_foundation ? 1 : 0

  instance_id = "data-ec2"
  service_id  = aws_service_discovery_service.data[0].id
  attributes  = { AWS_INSTANCE_IPV4 = aws_instance.data[0].private_ip }
}
