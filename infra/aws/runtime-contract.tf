locals {
  runtime_region           = "ap-northeast-2"
  runtime_name_prefix      = "travelpay-production"
  runtime_parameter_prefix = "/travelpay/production"
  runtime_cluster_name     = "travelpay-production"

  # SG의 역할 키를 그대로 사용합니다. 이 목록이 이후 이미지·Task·DB 설정의 기준입니다.
  # name은 각 서비스의 spring.application.name과 같습니다. port는 업무 요청, management_port는 health·지표 포트이며
  # security.tf의 SG 포트와 같아야 합니다(tests/app_runtime.tftest.hcl이 대조합니다).
  application_services = {
    gateway   = { name = "gateway", port = 8080, management_port = 18080, schema = null, db_username = null, secret_names = [] }
    eureka    = { name = "eureka-server", port = 8761, management_port = 8761, schema = null, db_username = null, secret_names = [] }
    user      = { name = "user-service", port = 8080, management_port = 8080, schema = "user_service", db_username = "user_app", secret_names = ["USER_DB_PASSWORD", "REDIS_SESSION_PASSWORD"] }
    wallet    = { name = "wallet-service", port = 8080, management_port = 8080, schema = "wallet_service", db_username = "wallet_app", secret_names = ["WALLET_DB_PASSWORD", "WALLET_ACCOUNT_NUMBER_KEY"] }
    payment   = { name = "payment-service", port = 8080, management_port = 8080, schema = "payment_service", db_username = "payment_app", secret_names = ["PAYMENT_DB_PASSWORD"] }
    exchange  = { name = "exchange-service", port = 8080, management_port = 8080, schema = "exchange_service", db_username = "exchange_app", secret_names = ["EXCHANGE_DB_PASSWORD", "REDIS_CACHE_PASSWORD", "KOREAEXIM_API_KEY"] }
    ledger    = { name = "ledger-service", port = 8080, management_port = 8080, schema = "ledger_service", db_username = "ledger_app", secret_names = ["LEDGER_DB_PASSWORD"] }
    mock_bank = { name = "mock-bank", port = 8090, management_port = 8090, schema = "mock_bank", db_username = "mock_bank_app", secret_names = ["MOCK_BANK_DB_PASSWORD"] }
  }

  # Data 호스트의 Grafana 관리자 암호입니다. 앱 비밀값과 같은 경로·준비 도구를 쓰고 Data 역할만 읽습니다.
  monitoring_secret_names = ["GRAFANA_ADMIN_PASSWORD"]
  # Cloudflare가 ALB로 보내는 요청에 붙이는 원본 인증 값입니다. Terraform이 읽어 ALB 규칙에 넣고, 호스트·Task 역할은 읽지 않습니다.
  edge_secret_names = ["ORIGIN_AUTH"]
  secret_parameter_arns = {
    for name in toset(concat(flatten([for service in local.application_services : service.secret_names]), local.monitoring_secret_names, local.edge_secret_names)) :
    name => "arn:aws:ssm:${local.runtime_region}:${var.aws_account_id}:parameter${local.runtime_parameter_prefix}/${name}"
  }
  database_password_names = toset([
    "USER_DB_PASSWORD", "WALLET_DB_PASSWORD", "PAYMENT_DB_PASSWORD",
    "EXCHANGE_DB_PASSWORD", "LEDGER_DB_PASSWORD", "MOCK_BANK_DB_PASSWORD",
  ])
  data_secret_names = setunion(
    toset(concat(["REDIS_SESSION_PASSWORD", "REDIS_CACHE_PASSWORD"], local.monitoring_secret_names)),
    var.enable_database_bootstrap_access ? local.database_password_names : toset([]),
  )
  repository_arns = {
    for key, service in local.application_services :
    key => "arn:aws:ecr:${local.runtime_region}:${var.aws_account_id}:repository/travelpay/${service.name}"
  }
  application_log_arns = {
    for key, service in local.application_services :
    key => "arn:aws:logs:${local.runtime_region}:${var.aws_account_id}:log-group:/travelpay/production/${service.name}:*"
  }
  task_service_trust = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = ["sts:AssumeRole"]
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Condition = {
        StringEquals = { "aws:SourceAccount" = var.aws_account_id }
        ArnLike      = { "aws:SourceArn" = "arn:aws:ecs:${local.runtime_region}:${var.aws_account_id}:*" }
      }
    }]
  })
}
