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
}

override_resource {
  override_during = plan
  target          = aws_subnet.network["data_a"]
  values          = { id = "subnet-00000000000000020" }
}

override_resource {
  override_during = plan
  target          = aws_subnet.network["data_b"]
  values          = { id = "subnet-00000000000000021" }
}

variables {
  app_host_count                   = 0
  enable_database_bootstrap_access = false
  enable_github_deployment         = false
  aws_account_id                   = "123456789012"
  availability_zones               = { a = "ap-northeast-2a", b = "ap-northeast-2c" }
  enable_app_foundation            = true
  enable_data_foundation           = true
  ecs_ami_id                       = "ami-0123456789abcdef0"
  data_ami_id                      = "ami-0123456789abcdef1"
}

run "database_is_private_encrypted_and_recoverable" {
  command = plan
  assert {
    condition = (
      aws_db_instance.main[0].engine == "postgres" &&
      aws_db_instance.main[0].engine_version == "18.6" &&
      aws_db_instance.main[0].instance_class == "db.t4g.medium" &&
      !aws_db_instance.main[0].publicly_accessible &&
      !aws_db_instance.main[0].multi_az &&
      aws_db_instance.main[0].availability_zone == "ap-northeast-2a" &&
      aws_db_instance.main[0].storage_encrypted &&
      aws_db_instance.main[0].allocated_storage == 20 &&
      aws_db_instance.main[0].max_allocated_storage == 40 &&
      aws_db_instance.main[0].backup_retention_period == 7 &&
      aws_db_instance.main[0].deletion_protection &&
      !aws_db_instance.main[0].skip_final_snapshot
    )
    error_message = "RDS는 지정한 사설 AZ·암호화·복구 정책을 지켜야 합니다."
  }
  assert {
    condition = (
      aws_db_instance.main[0].manage_master_user_password &&
      length(aws_db_subnet_group.main[0].subnet_ids) == 2 &&
      contains([for p in aws_db_parameter_group.main[0].parameter : "${p.name}=${p.value}"], "rds.force_ssl=1") &&
      contains([for p in aws_db_parameter_group.main[0].parameter : "${p.name}=${p.value}"], "max_connections=100")
    )
    error_message = "관리자 비밀번호를 State에 넣지 않고 TLS와 연결 상한을 적용해야 합니다."
  }
  assert {
    condition     = contains([for p in aws_db_parameter_group.main[0].parameter : "${p.name}=${p.value}"], "log_error_verbosity=terse")
    error_message = "PL/pgSQL 오류의 CONTEXT에 비밀번호 DDL이 기록되지 않게 해야 합니다."
  }
}

run "data_host_and_persistent_storage_are_separate" {
  command = plan
  assert {
    condition = (
      aws_instance.data[0].instance_type == "m7i.large" &&
      aws_instance.data[0].subnet_id == aws_subnet.network["data_a"].id &&
      !aws_instance.data[0].associate_public_ip_address &&
      one(aws_instance.data[0].metadata_options).http_tokens == "required" &&
      one(aws_instance.data[0].root_block_device).encrypted &&
      one(aws_instance.data[0].root_block_device).volume_size == 16 &&
      aws_ebs_volume.data["kafka"].encrypted &&
      aws_ebs_volume.data["kafka"].size == 30 &&
      aws_ebs_volume.data["monitoring"].size == 20 &&
      alltrue([for attachment in aws_volume_attachment.data : !attachment.force_detach && attachment.stop_instance_before_detaching])
    )
    error_message = "Data는 사설 서버와 별도 암호화 EBS를 사용하고 강제 분리하면 안 됩니다."
  }
}

run "database_initialization_permissions_are_temporary" {
  command = plan
  assert {
    condition     = length(aws_iam_role_policy.database_bootstrap) == 0
    error_message = "평상시 Data 서버가 RDS 관리자 비밀값을 읽으면 안 됩니다."
  }
}

run "data_dns_is_registered_through_cloud_map" {
  command = plan
  override_resource {
    override_during = plan
    target          = aws_service_discovery_private_dns_namespace.main[0]
    values          = { id = "ns-0000000000000001" }
  }
  override_resource {
    override_during = plan
    target          = aws_service_discovery_service.data[0]
    values          = { id = "srv-0000000000000001" }
  }
  override_resource {
    override_during = plan
    target          = aws_instance.data[0]
    values          = { private_ip = "10.20.20.10" }
  }
  assert {
    condition = (
      aws_service_discovery_service.data[0].name == "data" &&
      one(aws_service_discovery_service.data[0].dns_config).namespace_id == "ns-0000000000000001" &&
      one(one(aws_service_discovery_service.data[0].dns_config).dns_records).type == "A" &&
      one(one(aws_service_discovery_service.data[0].dns_config).dns_records).ttl == 30 &&
      aws_service_discovery_instance.data[0].service_id == "srv-0000000000000001" &&
      aws_service_discovery_instance.data[0].attributes["AWS_INSTANCE_IPV4"] == "10.20.20.10"
    )
    error_message = "Cloud Map 관리 영역의 Data A 레코드는 서비스·인스턴스 등록으로 생성해야 합니다."
  }
}

run "bootstrap_grants_only_the_rds_master_secret" {
  command = plan
  variables { enable_database_bootstrap_access = true }
  override_resource {
    override_during = plan
    target          = aws_db_instance.main[0]
    values = {
      master_user_secret = [{ secret_arn = "arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:rds!db-test", secret_status = "active", kms_key_id = "test" }]
    }
  }
  assert {
    condition = (
      jsondecode(aws_iam_role_policy.database_bootstrap[0].policy).Statement[0].Effect == "Allow" &&
      jsondecode(aws_iam_role_policy.database_bootstrap[0].policy).Statement[0].Action == ["secretsmanager:GetSecretValue"] &&
      jsondecode(aws_iam_role_policy.database_bootstrap[0].policy).Statement[0].Resource == ["arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:rds!db-test"]
    )
    error_message = "초기화 때만 생성한 DB의 관리자 Secret 하나를 읽도록 해야 합니다."
  }
}
