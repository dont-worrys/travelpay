locals {
  api_domain_name = "api.travelpay.store"
}

# ALB에서 쓸 공개 인증서입니다. DNS 검증 레코드는 운영자가 Cloudflare에 프록시 없이 추가하고,
# 발급(ISSUED)을 확인한 뒤 ALB 리스너를 만듭니다. 발급 전 인증서로는 HTTPS 리스너를 만들 수 없습니다.
resource "aws_acm_certificate" "api" {
  count = var.enable_app_foundation ? 1 : 0

  domain_name       = local.api_domain_name
  validation_method = "DNS"

  # 인증서를 바꿔야 할 때 리스너가 쓰는 기존 인증서를 먼저 지우지 않도록 새 인증서를 먼저 만듭니다.
  lifecycle {
    create_before_destroy = true
  }
}

# 발급(ISSUED)될 때까지 기다리는 자원입니다. 리스너가 이 결과를 참조하므로 미발급 인증서로 리스너를 만들지 않습니다.
resource "aws_acm_certificate_validation" "api" {
  count = var.enable_app_runtime ? 1 : 0

  certificate_arn         = aws_acm_certificate.api[0].arn
  validation_record_fqdns = [for option in aws_acm_certificate.api[0].domain_validation_options : option.resource_record_name]
}

# 상태가 없는 진입점이라 삭제 보호를 두지 않습니다. 다시 만들면 DNS 이름이 바뀌므로 Cloudflare 레코드도 함께 바꿉니다.
resource "aws_lb" "api" {
  count = var.enable_app_runtime ? 1 : 0

  name               = local.runtime_name_prefix
  load_balancer_type = "application"
  internal           = false
  ip_address_type    = "ipv4"
  subnets            = [aws_subnet.network["public_a"].id, aws_subnet.network["public_b"].id]
  # SG는 Cloudflare 주소의 443만 허용합니다(security.tf).
  security_groups = [aws_security_group.boundary["alb"].id]
  # 이름 형식이 잘못된 헤더는 Gateway에 넘기지 않습니다.
  drop_invalid_header_fields = true
  # Cloudflare는 마지막 요청 뒤 최대 900초 동안 원본 연결을 다시 씁니다(Proxy Idle Timeout).
  # ALB가 먼저 끊으면(기본 60초) 재사용하는 순간 연결이 끊겨 520 오류가 날 수 있으므로 ALB 쪽을 더 길게 둡니다.
  idle_timeout = 1000
}

# Gateway Task는 PR4의 ECS Service가 IP로 등록합니다. 상태 확인은 업무 포트가 아닌 관리 포트의 readiness를 봅니다.
resource "aws_lb_target_group" "gateway" {
  count = var.enable_app_runtime ? 1 : 0

  name        = "${local.runtime_name_prefix}-gateway"
  port        = local.application_services.gateway.port
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = aws_vpc.main.id
  # 종료 전 처리 중인 요청을 마칠 시간입니다. ECS는 Target 등록 해제(DEACTIVATING)를 마친 뒤 종료 신호를 보냅니다.
  deregistration_delay = 30

  health_check {
    protocol            = "HTTP"
    port                = tostring(local.application_services.gateway.management_port)
    path                = "/actuator/health/readiness"
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener" "https" {
  count = var.enable_app_runtime ? 1 : 0

  load_balancer_arn = aws_lb.api[0].arn
  port              = 443
  protocol          = "HTTPS"
  # TLS 1.2·1.3만 허용하는 AWS 권장 정책입니다.
  ssl_policy      = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn = aws_acm_certificate_validation.api[0].certificate_arn

  # API 도메인이 아닌 Host로 온 요청은 Gateway에 넘기지 않습니다.
  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "Not Found"
      status_code  = "404"
    }
  }
}

resource "aws_lb_listener_rule" "api" {
  count = var.enable_app_runtime ? 1 : 0

  listener_arn = aws_lb_listener.https[0].arn
  priority     = 10

  condition {
    host_header {
      values = [local.api_domain_name]
    }
  }

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.gateway[0].arn
  }
}
