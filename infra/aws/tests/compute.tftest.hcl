mock_provider "aws" {
  override_during = plan

  mock_data "aws_availability_zones" {
    defaults = { names = ["ap-northeast-2a", "ap-northeast-2c"] }
  }

  mock_data "aws_ami" {
    defaults = {
      id                  = "ami-0123456789abcdef0"
      architecture        = "x86_64"
      name                = "al2023-ami-ecs-hvm-test"
      root_device_name    = "/dev/xvda"
      virtualization_type = "hvm"
    }
  }
}

override_resource {
  override_during = plan
  target          = aws_subnet.network["app_a"]
  values          = { id = "subnet-00000000000000010" }
}

override_resource {
  override_during = plan
  target          = aws_subnet.network["app_b"]
  values          = { id = "subnet-00000000000000011" }
}

override_resource {
  override_during = plan
  target          = aws_security_group.boundary["app_host"]
  values          = { id = "sg-00000000000000001" }
}

override_resource {
  override_during = plan
  target          = aws_security_group.boundary["log_collector"]
  values          = { id = "sg-00000000000000002" }
}

override_resource {
  override_during = plan
  target          = aws_launch_template.app[0]
  values          = { id = "lt-00000000000000001", latest_version = 4 }
}

variables {
  enable_data_foundation           = false
  enable_app_runtime               = false
  enable_app_services              = false
  enable_public_entry              = false
  application_images               = null
  enable_database_bootstrap_access = false
  enable_github_deployment         = false
  aws_account_id                   = "123456789012"
  alert_email                      = "ops@example.com"
  availability_zones               = { a = "ap-northeast-2a", b = "ap-northeast-2c" }
  enable_app_foundation            = true
  app_host_count                   = 2
  ecs_ami_id                       = "ami-0123456789abcdef0"
  data_ami_id                      = "ami-0123456789abcdef1"
}

run "application_hosts_use_private_pinned_hardened_configuration" {
  command = plan

  assert {
    condition = (
      aws_launch_template.app[0].image_id == "ami-0123456789abcdef0" &&
      aws_launch_template.app[0].instance_type == "m7i.large" &&
      one(aws_launch_template.app[0].metadata_options).http_tokens == "required" &&
      one(aws_launch_template.app[0].metadata_options).http_put_response_hop_limit == 1 &&
      one(aws_launch_template.app[0].network_interfaces).associate_public_ip_address == "false" &&
      sort(tolist(one(aws_launch_template.app[0].network_interfaces).security_groups)) == sort(["sg-00000000000000001", "sg-00000000000000002"])
    )
    error_message = "고정한 M7i/AMI·프로젝트 SG·IMDSv2를 사용하고 공인 IP는 부여하지 않아야 합니다."
  }

  assert {
    condition = sort(tolist(aws_autoscaling_group.app[0].vpc_zone_identifier)) == sort([
      "subnet-00000000000000010", "subnet-00000000000000011",
    ])
    error_message = "앱 호스트는 Public이나 Data가 아닌 App A/B 서브넷에 배치해야 합니다."
  }
}

run "host_refresh_retains_two_hosts_and_has_bounded_surge" {
  command = plan

  assert {
    condition = (
      aws_autoscaling_group.app[0].min_size == 2 &&
      aws_autoscaling_group.app[0].desired_capacity == 2 &&
      aws_autoscaling_group.app[0].max_size == 3 &&
      one(one(aws_autoscaling_group.app[0].instance_refresh).preferences).min_healthy_percentage == 100 &&
      one(one(aws_autoscaling_group.app[0].instance_refresh).preferences).max_healthy_percentage == 150 &&
      one(aws_autoscaling_group.app[0].launch_template).version == "4"
    )
    error_message = "호스트 교체 중 정상 2대를 유지하고 임시 추가는 1대로 제한해야 합니다."
  }

  assert {
    condition = (
      one(aws_ecs_capacity_provider.app[0].auto_scaling_group_provider).managed_draining == "ENABLED" &&
      one(one(aws_ecs_capacity_provider.app[0].auto_scaling_group_provider).managed_scaling).status == "DISABLED" &&
      aws_ecs_account_setting_default.trunking[0].value == "enabled"
    )
    error_message = "Task를 안전하게 drain하고 Trunking을 켜며 자동 증설로 비용이 늘지 않게 해야 합니다."
  }
}

run "reject_non_ecs_ami_for_application_hosts" {
  command = plan

  override_data {
    override_during = plan
    target          = data.aws_ami.app[0]
    values = {
      id               = "ami-0123456789abcdef0"
      architecture     = "x86_64"
      name             = "al2023-ami-regular"
      root_device_name = "/dev/xvda"
    }
  }

  expect_failures = [data.aws_ami.app]
}

run "prepare_capacity_provider_before_starting_hosts" {
  command = plan

  variables {
    app_host_count = 0
  }

  assert {
    condition = (
      aws_autoscaling_group.app[0].min_size == 0 &&
      aws_autoscaling_group.app[0].desired_capacity == 0 &&
      aws_autoscaling_group.app[0].max_size == 3 &&
      length(aws_ecs_capacity_provider.app) == 1
    )
    error_message = "첫 생성에는 빈 ASG와 Capacity Provider를 먼저 준비해야 합니다."
  }

  assert {
    condition = try(one([
      for tag in aws_autoscaling_group.app[0].tag : tag if tag.key == "AmazonECSManaged"
    ]).propagate_at_launch, false)
    error_message = "Capacity Provider의 관리 태그를 Terraform이 지우거나 새 인스턴스에서 빠뜨리면 안 됩니다."
  }
}
