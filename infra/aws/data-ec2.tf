locals {
  data_volume_specs = {
    kafka      = { size = 30, device = "/dev/sdf", mount = "/srv/travelpay/kafka" }
    monitoring = { size = 20, device = "/dev/sdg", mount = "/srv/travelpay/monitoring" }
  }
  data_runtime_config = var.enable_data_foundation ? {
    region            = local.runtime_region
    parameter_prefix  = local.runtime_parameter_prefix
    compose_version   = "v5.7.0"
    compose_sha256    = "7758048ad38e580f22559603312874fec8bb638d021a9e64182385fc17cc12a2"
    redis_image       = "redis:8.2.11-bookworm@sha256:b0b01167ff47bc3868e56a967895a26be4230891a1f42a78ab61cdd227accc8d"
    kafka_image       = "apache/kafka:4.2.2@sha256:1213eb3943d551e5ed1fca7a4e109001cee35770b66a02a0c37a8964efe09b69"
    kafka_host        = "data.travelpay.internal"
    database_host     = aws_db_instance.main[0].address
    database_name     = "travelpay"
    master_secret_arn = one(aws_db_instance.main[0].master_user_secret).secret_arn
    databases = {
      for key, service in local.application_services : key => {
        schema             = service.schema, username = service.db_username,
        password_parameter = one([for name in service.secret_names : name if endswith(name, "_DB_PASSWORD")]),
      } if service.schema != null
    }
    volumes = {
      for name, spec in local.data_volume_specs : name => {
        id = aws_ebs_volume.data[name].id, size_bytes = spec.size * 1024 * 1024 * 1024, mount = spec.mount,
      }
    }
  } : null
  data_assets = var.enable_data_foundation ? merge({
    "runtime.json" = jsonencode(local.data_runtime_config)
    "compose.yaml" = templatefile("${path.module}/templates/data-compose.yaml.tftpl", {
      redis_image = local.data_runtime_config.redis_image, kafka_image = local.data_runtime_config.kafka_image,
    })
    "travelpay-data.service" = file("${path.module}/templates/travelpay-data.service")
    }, {
    for name in ["mount-data-volumes.py", "prepare-data.py", "bootstrap-database.py"] :
    name => file("${path.module}/scripts/${name}")
  }) : {}
}

data "aws_ami" "data" {
  count  = var.enable_data_foundation ? 1 : 0
  owners = ["amazon"]
  filter {
    name   = "image-id"
    values = [var.data_ami_id]
  }
  lifecycle {
    postcondition {
      condition     = self.architecture == "x86_64" && startswith(self.name, "al2023-ami-")
      error_message = "Data에는 확인한 Amazon Linux 2023 x86_64 AMI가 필요합니다."
    }
  }
}

resource "aws_ebs_volume" "data" {
  for_each = var.enable_data_foundation ? local.data_volume_specs : {}

  availability_zone = var.availability_zones.a
  type              = "gp3"
  size              = each.value.size
  encrypted         = true
  tags              = { Name = "${local.runtime_name_prefix}-${each.key}" }
  lifecycle { prevent_destroy = true }
}

resource "aws_instance" "data" {
  count = var.enable_data_foundation ? 1 : 0

  ami                         = data.aws_ami.data[0].id
  instance_type               = "m7i.large"
  subnet_id                   = aws_subnet.network["data_a"].id
  associate_public_ip_address = false
  vpc_security_group_ids = [
    aws_security_group.boundary["data"].id,
    aws_security_group.boundary["monitoring"].id,
    aws_security_group.boundary["log_collector"].id,
  ]
  iam_instance_profile        = aws_iam_instance_profile.data.name
  user_data_replace_on_change = true
  ebs_optimized               = true
  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
    instance_metadata_tags      = "disabled"
  }
  root_block_device {
    volume_type = "gp3"
    volume_size = 16
    encrypted   = true
  }
  user_data = templatefile("${path.module}/templates/data-user-data.sh.tftpl", {
    assets           = base64gzip(jsonencode(local.data_assets)),
    cloudwatch_agent = local.host_cloudwatch_configs["data"],
  })
  tags = { Name = "${local.runtime_name_prefix}-data", Role = "data" }

  lifecycle { prevent_destroy = true }
  depends_on = [
    aws_iam_role_policies_exclusive.hosts, aws_iam_role_policy_attachments_exclusive.hosts,
    aws_cloudwatch_log_group.hosts, aws_route.private_egress,
  ]
}

resource "aws_volume_attachment" "data" {
  for_each = var.enable_data_foundation ? local.data_volume_specs : {}

  device_name                    = each.value.device
  volume_id                      = aws_ebs_volume.data[each.key].id
  instance_id                    = aws_instance.data[0].id
  force_detach                   = false
  stop_instance_before_detaching = true
}
