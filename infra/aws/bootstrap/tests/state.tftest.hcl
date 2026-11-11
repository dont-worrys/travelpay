# AWS API 대신 Provider 경계만 대체합니다. 실제 버킷·권한·잠금 동작을 입증하는 테스트는 아닙니다.
mock_provider "aws" {}

variables {
  aws_account_id = "123456789012"
}

run "state_bucket_security" {
  command = plan

  assert {
    condition = alltrue([
      aws_s3_bucket_public_access_block.state.block_public_acls,
      aws_s3_bucket_public_access_block.state.block_public_policy,
      aws_s3_bucket_public_access_block.state.ignore_public_acls,
      aws_s3_bucket_public_access_block.state.restrict_public_buckets,
    ])
    error_message = "State 버킷의 공개 접근 차단 네 항목을 모두 활성화해야 합니다."
  }

  assert {
    condition     = aws_s3_bucket_versioning.state.versioning_configuration[0].status == "Enabled"
    error_message = "State를 복구할 수 있도록 버전 관리를 활성화해야 합니다."
  }

  assert {
    condition     = one(aws_s3_bucket_server_side_encryption_configuration.state.rule).apply_server_side_encryption_by_default[0].sse_algorithm == "AES256"
    error_message = "State에는 SSE-S3 암호화를 적용해야 합니다."
  }

  assert {
    condition     = aws_s3_bucket_ownership_controls.state.rule[0].object_ownership == "BucketOwnerEnforced"
    error_message = "객체 ACL 대신 버킷 소유권과 정책으로 접근을 관리해야 합니다."
  }

  assert {
    condition = anytrue([
      for statement in jsondecode(aws_s3_bucket_policy.state.policy).Statement :
      statement.Effect == "Deny" && try(statement.Condition.Bool["aws:SecureTransport"] == "false", false)
    ])
    error_message = "State 버킷 정책은 HTTP 접근을 거절해야 합니다."
  }
}

run "reject_malformed_account" {
  command = plan

  variables {
    aws_account_id = "12345"
  }

  expect_failures = [var.aws_account_id]
}

run "backend_policy_keeps_state_and_lock_permissions_separate" {
  command = plan

  assert {
    condition = anytrue([
      for statement in jsondecode(output.state_backend_access_policy_json).Statement :
      statement.Effect == "Allow" &&
      contains(statement.Action, "s3:ListBucket") &&
      toset(statement.Resource) == toset(["arn:aws:s3:::travelpay-tfstate-123456789012-ap-northeast-2"])
    ])
    error_message = "Backend가 State를 찾을 수 있도록 전용 버킷의 목록 조회 권한이 필요합니다."
  }

  assert {
    condition = toset(flatten([
      for statement in jsondecode(output.state_backend_access_policy_json).Statement :
      statement.Resource if contains(statement.Action, "s3:DeleteObject")
      ])) == toset([
      "arn:aws:s3:::travelpay-tfstate-123456789012-ap-northeast-2/bootstrap/terraform.tfstate.tflock",
      "arn:aws:s3:::travelpay-tfstate-123456789012-ap-northeast-2/production/terraform.tfstate.tflock",
    ])
    error_message = "삭제 권한은 두 lockfile에만 허용해야 하며 State 자체에 허용하면 안 됩니다."
  }

  assert {
    condition = alltrue(flatten([
      for statement in jsondecode(output.state_backend_access_policy_json).Statement : [
        for action in statement.Action :
        contains(["s3:ListBucket", "s3:GetObject", "s3:PutObject", "s3:DeleteObject"], action)
      ]
    ]))
    error_message = "Backend 정책에 wildcard나 불필요한 S3 작업 권한을 추가하면 안 됩니다."
  }

  assert {
    condition = alltrue([
      for key in ["bootstrap/terraform.tfstate", "production/terraform.tfstate"] : anytrue([
        for statement in jsondecode(output.state_backend_access_policy_json).Statement :
        statement.Effect == "Allow" &&
        contains(statement.Action, "s3:GetObject") &&
        contains(statement.Action, "s3:PutObject") &&
        contains(statement.Resource, "arn:aws:s3:::travelpay-tfstate-123456789012-ap-northeast-2/${key}")
      ])
    ])
    error_message = "두 State 파일에 읽기·쓰기 권한이 필요합니다."
  }
}
