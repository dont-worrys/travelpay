# Data 호스트 설정은 user data가 아니라 S3 묶음과 SSM association으로 전달합니다.
# user data를 바꾸면 인스턴스가 교체되므로, 설정 변경은 같은 인스턴스에서 바뀐 컨테이너만 다시 만듭니다.
locals {
  data_config_bucket = "${local.runtime_name_prefix}-data-config-${var.aws_account_id}"
  data_bundle_key    = "data/bundle.json.gz"
}

resource "aws_s3_bucket" "data_config" {
  count = var.enable_data_foundation ? 1 : 0

  bucket = local.data_config_bucket
}

resource "aws_s3_bucket_public_access_block" "data_config" {
  count = var.enable_data_foundation ? 1 : 0

  bucket                  = aws_s3_bucket.data_config[0].id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "data_config" {
  count = var.enable_data_foundation ? 1 : 0

  bucket = aws_s3_bucket.data_config[0].id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "data_config" {
  count = var.enable_data_foundation ? 1 : 0

  bucket = aws_s3_bucket.data_config[0].id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_policy" "data_config" {
  count = var.enable_data_foundation ? 1 : 0

  bucket = aws_s3_bucket.data_config[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = ["s3:*"]
      Resource  = [aws_s3_bucket.data_config[0].arn, "${aws_s3_bucket.data_config[0].arn}/*"]
      Condition = {
        Bool = {
          "aws:SecureTransport"       = "false"
          "aws:PrincipalIsAWSService" = "false"
        }
      }
    }]
  })
}

# 묶음 내용은 코드에서 만들어지므로 버전 관리 대신 SHA-256으로 적용 대상을 고정합니다.
resource "aws_s3_object" "data_bundle" {
  count = var.enable_data_foundation ? 1 : 0

  bucket         = aws_s3_bucket.data_config[0].id
  key            = local.data_bundle_key
  content_base64 = base64gzip(local.data_bundle_json)
  content_type   = "application/gzip"

  depends_on = [
    aws_s3_bucket_public_access_block.data_config,
    aws_s3_bucket_ownership_controls.data_config,
    aws_s3_bucket_server_side_encryption_configuration.data_config,
    aws_s3_bucket_policy.data_config,
  ]
}

resource "aws_iam_role_policy" "data_config_read" {
  count = var.enable_data_foundation ? 1 : 0

  name = "read-data-config-bundle"
  role = aws_iam_role.data.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["s3:GetObject"]
      Resource = ["${aws_s3_bucket.data_config[0].arn}/${local.data_bundle_key}"]
    }]
  })
}

resource "aws_ssm_document" "data_apply" {
  count = var.enable_data_foundation ? 1 : 0

  name            = "${local.runtime_name_prefix}-data-apply"
  document_type   = "Command"
  document_format = "JSON"
  # 매개변수는 셸 작은따옴표 안에 들어가므로 형식을 제한합니다. 스크립트에는 SSM 치환 기호를 쓰지 않습니다.
  content = jsonencode({
    schemaVersion = "2.2"
    description   = "Fetch the TravelPay Data host bundle, verify its SHA-256 and apply it."
    parameters = {
      Bucket = { type = "String", allowedPattern = "^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$" }
      Key    = { type = "String", allowedPattern = "^[A-Za-z0-9][A-Za-z0-9/._-]{0,255}$" }
      Sha256 = { type = "String", allowedPattern = "^[0-9a-f]{64}$" }
      Region = { type = "String", allowedPattern = "^[a-z]{2}-[a-z]+-[0-9]$" }
    }
    mainSteps = [{
      action = "aws:runShellScript"
      name   = "applyDataBundle"
      inputs = {
        timeoutSeconds = "1500"
        runCommand = concat(
          [
            "set -eu",
            "export TRAVELPAY_BUNDLE_BUCKET='{{ Bucket }}' TRAVELPAY_BUNDLE_KEY='{{ Key }}' TRAVELPAY_BUNDLE_SHA256='{{ Sha256 }}' TRAVELPAY_REGION='{{ Region }}'",
            "python3 -I - <<'TRAVELPAY_FETCH'",
          ],
          split("\n", trimspace(file("${path.module}/scripts/fetch-data-bundle.py"))),
          ["TRAVELPAY_FETCH"],
        )
      }
    }]
  })
}

resource "aws_ssm_association" "data_apply" {
  count = var.enable_data_foundation ? 1 : 0

  name             = aws_ssm_document.data_apply[0].name
  association_name = "${local.runtime_name_prefix}-data-apply"
  max_concurrency  = "1"
  max_errors       = "0"
  targets {
    key    = "InstanceIds"
    values = [aws_instance.data[0].id]
  }
  parameters = {
    Bucket = aws_s3_bucket.data_config[0].id
    Key    = aws_s3_object.data_bundle[0].key
    Sha256 = sha256(local.data_bundle_json)
    Region = local.runtime_region
  }
  # Provider는 생성할 때만 성공을 기다립니다. 묶음·문서·인스턴스가 바뀌면 다시 만들어 실패가 apply에 드러나게 합니다.
  # 문서 실행 한도(1500초)보다 길게 기다려 성공·실패·시간 초과의 최종 상태를 apply에서 확인합니다.
  wait_for_success_timeout_seconds = 1800

  lifecycle {
    replace_triggered_by = [aws_s3_object.data_bundle[0], aws_ssm_document.data_apply[0], aws_instance.data[0]]
  }

  # 디스크 부착과 S3 조회 권한은 참조로 드러나지 않는 실행 조건입니다.
  depends_on = [aws_volume_attachment.data, aws_iam_role_policies_exclusive.hosts]
}
