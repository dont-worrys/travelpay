variable "aws_account_id" {
  description = "State 버킷을 만들 AWS 계정 ID입니다. Provider가 다른 계정으로의 적용을 차단합니다."
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[0-9]{12}$", var.aws_account_id))
    error_message = "AWS 계정 ID는 숫자 12자리여야 합니다."
  }
}
