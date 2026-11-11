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
