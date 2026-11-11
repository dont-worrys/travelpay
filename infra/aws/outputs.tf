output "network" {
  description = "생성된 네트워크 식별자입니다. 이 출력만으로 실제 통신 성공이 검증되지는 않습니다."
  value = {
    vpc_id             = aws_vpc.main.id
    vpc_cidr           = aws_vpc.main.cidr_block
    availability_zones = var.availability_zones
    subnet_ids         = { for name, subnet in aws_subnet.network : name => subnet.id }
    route_table_ids    = { for name, table in aws_route_table.network : name => table.id }
    nat_gateway_id     = aws_nat_gateway.main.id
  }
}

output "security_group_ids" {
  description = "역할별 SG 식별자입니다. 실제 호스트·Task 연결은 해당 구현 단위에서 구성합니다."
  value       = { for name, group in aws_security_group.boundary : name => group.id }
}

output "runtime_identity" {
  description = "런타임 역할과 비밀값 참조입니다. 실제 비밀값은 읽거나 출력하지 않습니다."
  value = {
    app_instance_profile   = aws_iam_instance_profile.app_host.name
    data_instance_profile  = aws_iam_instance_profile.data.name
    task_execution_roles   = { for name, role in aws_iam_role.task_execution : name => role.arn }
    task_roles             = { for name, role in aws_iam_role.task : name => role.arn }
    secret_parameter_arns  = local.secret_parameter_arns
    github_deployment_role = aws_iam_role.github_deploy.arn
    github_deploy_enabled  = var.enable_github_deployment
  }
}

output "runtime_secret_parameters" {
  description = "초기 비밀값 준비 도구가 사용하는 이름·ARN 계약입니다. 비밀값은 포함하지 않습니다."
  value       = local.secret_parameter_arns
}

output "application_capacity" {
  description = "활성화된 앱 서버 기반입니다. 생성 완료만으로 Agent 준비가 검증되지는 않습니다."
  value = var.enable_app_foundation ? {
    cluster_name      = aws_ecs_cluster.main[0].name
    autoscaling_group = aws_autoscaling_group.app[0].name
    capacity_provider = aws_ecs_capacity_provider.app[0].name
    normal_hosts      = 2
    configured_hosts  = var.app_host_count
    maximum_hosts     = 3
  } : null
}

output "application_repositories" {
  description = "이미지 게시 도구가 사용하는 서비스별 ECR 주소입니다."
  value       = { for key, repository in aws_ecr_repository.application : key => repository.repository_url }
}

output "api_certificate" {
  description = "Cloudflare에 추가할 ACM DNS 검증 레코드입니다. 프록시 없이(DNS only) 추가하고 발급 뒤에도 갱신용으로 유지합니다."
  value = var.enable_app_foundation ? {
    arn    = aws_acm_certificate.api[0].arn
    domain = aws_acm_certificate.api[0].domain_name
    validation_records = [
      for option in aws_acm_certificate.api[0].domain_validation_options : {
        name = option.resource_record_name, type = option.resource_record_type, value = option.resource_record_value
      }
    ]
  } : null
}

output "data_runtime" {
  description = "Data 접속·초기화용 비밀값 없는 실행 계약입니다. 관리자 암호 대신 Secret ARN만 제공합니다."
  value = var.enable_data_foundation ? {
    instance_id       = aws_instance.data[0].id
    private_address   = "${aws_service_discovery_service.data[0].name}.${aws_service_discovery_private_dns_namespace.main[0].name}"
    namespace_id      = aws_service_discovery_private_dns_namespace.main[0].id
    namespace_zone_id = aws_service_discovery_private_dns_namespace.main[0].hosted_zone
    database_host     = aws_db_instance.main[0].address
    master_secret_arn = one(aws_db_instance.main[0].master_user_secret).secret_arn
    volumes           = { for name, volume in aws_ebs_volume.data : name => volume.id }
    config_bundle = {
      bucket         = aws_s3_bucket.data_config[0].id
      key            = aws_s3_object.data_bundle[0].key
      sha256         = sha256(local.data_bundle_json)
      association_id = aws_ssm_association.data_apply[0].association_id
    }
  } : null
}
