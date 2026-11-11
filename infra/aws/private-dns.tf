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

# Cloud Map 소유 영역의 레코드는 Route 53에 직접 쓰지 않고 등록 API가 관리합니다.
resource "aws_service_discovery_instance" "data" {
  count = var.enable_data_foundation ? 1 : 0

  instance_id = "data-ec2"
  service_id  = aws_service_discovery_service.data[0].id
  attributes  = { AWS_INSTANCE_IPV4 = aws_instance.data[0].private_ip }
}
