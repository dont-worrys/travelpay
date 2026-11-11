resource "aws_db_subnet_group" "main" {
  count = var.enable_data_foundation ? 1 : 0

  name       = local.runtime_name_prefix
  subnet_ids = [aws_subnet.network["data_a"].id, aws_subnet.network["data_b"].id]
}

resource "aws_db_parameter_group" "main" {
  count = var.enable_data_foundation ? 1 : 0

  name   = "${local.runtime_name_prefix}-postgres18"
  family = "postgres18"
  parameter {
    name         = "max_connections"
    value        = "100"
    apply_method = "pending-reboot"
  }
  parameter {
    # PostgreSQL 18의 기본값 1은 AWS가 pending-reboot로 반환합니다.
    # 같은 값의 반복 변경을 피하고 실제 SSL 강제 상태는 접속 검사로 확인합니다.
    name         = "rds.force_ssl"
    value        = "1"
    apply_method = "pending-reboot"
  }
  # 초기화 SQL의 비밀번호가 오류 로그에 기록되지 않도록 문장 로깅을 제한합니다.
  parameter {
    name  = "log_statement"
    value = "none"
  }
  parameter {
    name  = "log_min_error_statement"
    value = "panic"
  }
  # PL/pgSQL 오류의 CONTEXT에도 암호 DDL이 들어갈 수 있어 문맥 출력도 차단합니다.
  parameter {
    name  = "log_error_verbosity"
    value = "terse"
  }
}

resource "aws_cloudwatch_log_group" "database" {
  count = var.enable_data_foundation ? 1 : 0

  name              = "/aws/rds/instance/${local.runtime_name_prefix}/postgresql"
  retention_in_days = 7
}

resource "aws_db_instance" "main" {
  count = var.enable_data_foundation ? 1 : 0

  identifier                      = local.runtime_name_prefix
  engine                          = "postgres"
  engine_version                  = "18.6"
  instance_class                  = "db.t4g.medium"
  db_name                         = "travelpay"
  username                        = "travelpay_admin"
  manage_master_user_password     = true
  db_subnet_group_name            = aws_db_subnet_group.main[0].name
  parameter_group_name            = aws_db_parameter_group.main[0].name
  availability_zone               = var.availability_zones.a
  multi_az                        = false
  publicly_accessible             = false
  vpc_security_group_ids          = [aws_security_group.boundary["rds"].id]
  port                            = 5432
  storage_type                    = "gp3"
  allocated_storage               = 20
  max_allocated_storage           = 40
  storage_encrypted               = true
  ca_cert_identifier              = "rds-ca-rsa2048-g1"
  backup_retention_period         = 7
  backup_window                   = "18:00-19:00"
  maintenance_window              = "sun:19:00-sun:20:00"
  auto_minor_version_upgrade      = false
  allow_major_version_upgrade     = false
  apply_immediately               = false
  deletion_protection             = true
  skip_final_snapshot             = false
  final_snapshot_identifier       = "${local.runtime_name_prefix}-final"
  delete_automated_backups        = false
  enabled_cloudwatch_logs_exports = ["postgresql"]
  monitoring_interval             = 0
  performance_insights_enabled    = false

  lifecycle {
    prevent_destroy = true
  }
  depends_on = [aws_cloudwatch_log_group.database]
}

resource "aws_iam_role_policy" "database_bootstrap" {
  count = var.enable_data_foundation && var.enable_database_bootstrap_access ? 1 : 0

  name = "temporary-database-bootstrap"
  role = aws_iam_role.data.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow", Action = ["secretsmanager:GetSecretValue"],
      Resource = [one(aws_db_instance.main[0].master_user_secret).secret_arn],
    }]
  })
}
