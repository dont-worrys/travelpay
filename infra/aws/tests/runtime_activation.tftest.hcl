mock_provider "aws" {
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

variables {
  aws_account_id                   = "123456789012"
  alert_email                      = "ops@example.com"
  availability_zones               = { a = "ap-northeast-2a", b = "ap-northeast-2c" }
  enable_app_foundation            = false
  enable_data_foundation           = false
  enable_app_runtime               = false
  application_images               = null
  app_host_count                   = 0
  enable_database_bootstrap_access = false
  enable_github_deployment         = false
}

run "app_foundation_does_not_start_the_data_layer" {
  command = plan
  variables {
    enable_app_foundation  = true
    enable_data_foundation = false
    ecs_ami_id             = "ami-0123456789abcdef0"
    data_ami_id            = null
  }
  assert {
    condition = (
      length(aws_ecs_cluster.main) == 1 &&
      length(aws_autoscaling_group.app) == 1 &&
      length(aws_db_instance.main) == 0 &&
      length(aws_instance.data) == 0 &&
      length(aws_ebs_volume.data) == 0 &&
      length(aws_s3_bucket.data_config) == 0 &&
      length(aws_ssm_association.data_apply) == 0 &&
      keys(aws_cloudwatch_log_group.hosts) == ["app_host"] &&
      length(aws_ecs_task_definition.application) == 0 &&
      length(aws_lb.api) == 0
    )
    error_message = "5단계 활성화만으로 RDS·Data EC2·EBS·Data 설정 전달 자원이나 Task 정의·ALB를 생성하면 안 됩니다."
  }
}

run "data_layer_does_not_require_or_start_app_hosts" {
  command = plan
  variables {
    enable_app_foundation  = false
    enable_data_foundation = true
    ecs_ami_id             = null
    data_ami_id            = "ami-0123456789abcdef1"
  }
  assert {
    condition = (
      length(aws_ecs_cluster.main) == 0 &&
      length(aws_autoscaling_group.app) == 0 &&
      length(aws_db_instance.main) == 1 &&
      length(aws_instance.data) == 1 &&
      length(aws_ebs_volume.data) == 2 &&
      length(aws_ecr_repository.application) == 0 &&
      length(aws_service_discovery_service.application) == 0 &&
      length(aws_acm_certificate.api) == 0 &&
      keys(aws_cloudwatch_log_group.hosts) == ["data"]
    )
    error_message = "데이터 기반을 별도로 계획할 때 앱 서버 생성이나 앱 AMI를 요구하면 안 됩니다."
  }
}

run "paid_hosts_require_explicit_activation" {
  command = plan

  assert {
    condition = (
      length(aws_ecs_cluster.main) == 0 &&
      length(aws_launch_template.app) == 0 &&
      length(aws_autoscaling_group.app) == 0 &&
      length(aws_db_instance.main) == 0 &&
      length(aws_instance.data) == 0 &&
      length(aws_ebs_volume.data) == 0 &&
      length(aws_lb.api) == 0 &&
      length(aws_sns_topic.alerts) == 0
    )
    error_message = "초기 네트워크·권한 변경만으로 유료 서버·ALB가 자동 생성되면 안 됩니다."
  }
}
