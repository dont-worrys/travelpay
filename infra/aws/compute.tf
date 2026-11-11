data "aws_ami" "app" {
  count  = var.enable_app_foundation ? 1 : 0
  owners = ["amazon"]

  filter {
    name   = "image-id"
    values = [var.ecs_ami_id]
  }

  lifecycle {
    postcondition {
      condition     = self.architecture == "x86_64" && startswith(self.name, "al2023-ami-ecs-")
      error_message = "앱 호스트에는 고정한 Amazon Linux 2023 x86_64 ECS 최적화 AMI가 필요합니다."
    }
  }
}

resource "aws_ecs_account_setting_default" "trunking" {
  count = var.enable_app_foundation ? 1 : 0

  name  = "awsvpcTrunking"
  value = "enabled"
}

resource "aws_ecs_cluster" "main" {
  count = var.enable_app_foundation ? 1 : 0

  name = local.runtime_cluster_name

  setting {
    name  = "containerInsights"
    value = "disabled"
  }
}

resource "aws_launch_template" "app" {
  count = var.enable_app_foundation ? 1 : 0

  name_prefix   = "${local.runtime_name_prefix}-app-"
  image_id      = data.aws_ami.app[0].id
  instance_type = "m7i.large"
  ebs_optimized = true

  iam_instance_profile {
    name = aws_iam_instance_profile.app_host.name
  }

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
    instance_metadata_tags      = "disabled"
  }

  network_interfaces {
    device_index                = 0
    associate_public_ip_address = false
    security_groups = [
      aws_security_group.boundary["app_host"].id,
      aws_security_group.boundary["log_collector"].id,
    ]
  }

  block_device_mappings {
    device_name = data.aws_ami.app[0].root_device_name
    ebs {
      volume_type           = "gp3"
      volume_size           = 30
      encrypted             = true
      delete_on_termination = true
    }
  }

  user_data = base64encode(templatefile("${path.module}/templates/app-user-data.sh.tftpl", {
    cluster_name     = aws_ecs_cluster.main[0].name
    cloudwatch_agent = local.host_cloudwatch_configs["app_host"]
  }))

  tag_specifications {
    resource_type = "instance"
    tags = {
      Name        = "${local.runtime_name_prefix}-app", Project = "travelpay",
      Environment = "production", ManagedBy = "Terraform", Role = "app_host",
    }
  }

  tag_specifications {
    resource_type = "volume"
    tags          = { Project = "travelpay", Environment = "production", ManagedBy = "Terraform" }
  }
}

resource "aws_autoscaling_group" "app" {
  count = var.enable_app_foundation ? 1 : 0

  name                      = "${local.runtime_name_prefix}-app"
  min_size                  = var.app_host_count
  desired_capacity          = var.app_host_count
  max_size                  = 3
  vpc_zone_identifier       = [aws_subnet.network["app_a"].id, aws_subnet.network["app_b"].id]
  health_check_type         = "EC2"
  default_instance_warmup   = 120
  wait_for_capacity_timeout = "15m"

  availability_zone_distribution {
    capacity_distribution_strategy = "balanced-only"
  }

  # Capacity Provider가 붙이는 관리 태그를 후속 apply에서 지우지 않습니다.
  tag {
    key                 = "AmazonECSManaged"
    value               = ""
    propagate_at_launch = true
  }

  launch_template {
    id      = aws_launch_template.app[0].id
    version = tostring(aws_launch_template.app[0].latest_version)
  }

  # 앱 Task 교체와 호스트 교체는 다릅니다. 호스트 교체는 임시 1대를 먼저 확보합니다.
  instance_refresh {
    strategy = "Rolling"
    preferences {
      min_healthy_percentage = 100
      max_healthy_percentage = 150
      instance_warmup        = 120
      skip_matching          = true
      auto_rollback          = true
    }
  }

  depends_on = [
    aws_ecs_account_setting_default.trunking,
    aws_iam_role_policies_exclusive.hosts,
    aws_iam_role_policy_attachments_exclusive.hosts,
    aws_cloudwatch_log_group.hosts,
  ]
}

resource "aws_ecs_capacity_provider" "app" {
  count = var.enable_app_foundation ? 1 : 0

  name = "${local.runtime_name_prefix}-app"

  auto_scaling_group_provider {
    auto_scaling_group_arn         = aws_autoscaling_group.app[0].arn
    managed_draining               = "ENABLED"
    managed_termination_protection = "DISABLED"
    managed_scaling {
      status = "DISABLED"
    }
  }
}

resource "aws_ecs_cluster_capacity_providers" "main" {
  count = var.enable_app_foundation ? 1 : 0

  cluster_name       = aws_ecs_cluster.main[0].name
  capacity_providers = [aws_ecs_capacity_provider.app[0].name]
  default_capacity_provider_strategy {
    capacity_provider = aws_ecs_capacity_provider.app[0].name
    weight            = 1
  }
}
