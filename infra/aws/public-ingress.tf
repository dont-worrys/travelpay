locals {
  api_domain_name     = "api.travelpay.store"
  grafana_domain_name = "grafana.travelpay.store"
  # Cloudflare Transform Rule이 붙이고 ALB 규칙이 확인한 뒤 Gateway가 지우는 헤더입니다(InternalHeaderResetFilter.ORIGIN_AUTH).
  origin_auth_header = "Origin-Auth"
}

# Cloudflare Transform Rule에 넣은 것과 같은 값입니다(비밀값 준비 도구가 생성). ALB 규칙 설정과 State에도 남습니다.
data "aws_ssm_parameter" "origin_auth" {
  count = var.enable_public_entry ? 1 : 0

  name            = "${local.runtime_parameter_prefix}/${one(local.edge_secret_names)}"
  with_decryption = true

  # ALB는 *·?를 와일드카드로 보므로 손으로 바꾼 값이 들어오면 검사가 조용히 느슨해집니다. 도구가 만드는 형식만 받습니다.
  lifecycle {
    postcondition {
      condition     = nonsensitive(can(regex("^[0-9a-f]{64}$", self.value)))
      error_message = "ORIGIN_AUTH는 비밀값 준비 도구가 만드는 소문자 16진수 64자여야 합니다."
    }
  }
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

# Grafana 공개 주소의 인증서입니다. 쓰고 있는 API 인증서를 바꾸지 않도록 따로 발급해 같은 리스너에 추가합니다(SNI).
# 검증 레코드는 API 인증서와 같은 방법으로 Cloudflare에 추가합니다.
resource "aws_acm_certificate" "grafana" {
  count = var.enable_app_foundation ? 1 : 0

  domain_name       = local.grafana_domain_name
  validation_method = "DNS"

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

# Gateway Task는 ECS Service(app-services.tf)가 IP로 등록합니다. 상태 확인은 업무 포트가 아닌 관리 포트의 readiness를 봅니다.
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

  # SG는 Cloudflare 주소만 받지만 다른 Cloudflare 영역을 거친 요청도 그 주소에서 옵니다. 이 영역의 Transform Rule이 붙인
  # 헤더가 있는 요청만 보내고, 없거나 다르면 기본 응답(404)입니다. ALB는 값을 대소문자 구분 없이 비교합니다.
  dynamic "condition" {
    for_each = var.enable_public_entry ? [1] : []
    content {
      http_header {
        http_header_name = local.origin_auth_header
        values           = [data.aws_ssm_parameter.origin_auth[0].value]
      }
    }
  }

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.gateway[0].arn
  }
}

# Grafana 공개 경로입니다. Cloudflare Access(이메일 일회용 코드)를 통과하고 원본 인증 헤더가 붙은 요청만 Data 호스트로 보냅니다.
resource "aws_acm_certificate_validation" "grafana" {
  count = var.enable_public_entry ? 1 : 0

  certificate_arn         = aws_acm_certificate.grafana[0].arn
  validation_record_fqdns = [for option in aws_acm_certificate.grafana[0].domain_validation_options : option.resource_record_name]
}

# 같은 HTTPS 리스너에 추가 인증서로 붙입니다. ALB는 요청의 서버 이름(SNI)에 맞는 인증서를 고릅니다.
resource "aws_lb_listener_certificate" "grafana" {
  count = var.enable_public_entry ? 1 : 0

  listener_arn    = aws_lb_listener.https[0].arn
  certificate_arn = aws_acm_certificate_validation.grafana[0].certificate_arn
}

resource "aws_lb_target_group" "grafana" {
  count = var.enable_public_entry ? 1 : 0

  name                 = "${local.runtime_name_prefix}-grafana"
  port                 = local.tcp_connections.alb_grafana.port
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = aws_vpc.main.id
  deregistration_delay = 30

  health_check {
    protocol            = "HTTP"
    path                = "/api/health"
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

# Data 호스트를 교체하면 사설 IP가 바뀌므로 등록도 함께 바뀝니다.
resource "aws_lb_target_group_attachment" "grafana" {
  count = var.enable_public_entry ? 1 : 0

  target_group_arn = aws_lb_target_group.grafana[0].arn
  target_id        = aws_instance.data[0].private_ip
  port             = local.tcp_connections.alb_grafana.port
}

resource "aws_lb_listener_rule" "grafana" {
  count = var.enable_public_entry ? 1 : 0

  listener_arn = aws_lb_listener.https[0].arn
  priority     = 20

  condition {
    host_header {
      values = [local.grafana_domain_name]
    }
  }

  condition {
    http_header {
      http_header_name = local.origin_auth_header
      values           = [data.aws_ssm_parameter.origin_auth[0].value]
    }
  }

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.grafana[0].arn
  }
}
