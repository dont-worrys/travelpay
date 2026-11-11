# Provider 경계만 대체하고 실제 Terraform의 주소 계산·참조·검증을 실행합니다.
mock_provider "aws" {
  override_during = plan

  mock_data "aws_availability_zones" {
    defaults = {
      names = ["ap-northeast-2a", "ap-northeast-2b", "ap-northeast-2c", "ap-northeast-2d"]
    }
  }

  mock_resource "aws_vpc" {
    defaults = {
      id                     = "vpc-00000000000000001"
      default_route_table_id = "rtb-00000000000000000"
    }
  }

  mock_resource "aws_internet_gateway" {
    defaults = { id = "igw-00000000000000001" }
  }

  mock_resource "aws_eip" {
    defaults = { id = "eipalloc-00000000000000001" }
  }

  mock_resource "aws_nat_gateway" {
    defaults = { id = "nat-00000000000000001" }
  }
}

# plan에서도 참조를 검증할 수 있도록 테스트 전용 식별자를 지정합니다.
override_resource {
  target          = aws_subnet.network["public_a"]
  values          = { id = "subnet-00000000000000001" }
  override_during = plan
}

override_resource {
  target          = aws_subnet.network["public_b"]
  values          = { id = "subnet-00000000000000002" }
  override_during = plan
}

override_resource {
  target          = aws_subnet.network["app_a"]
  values          = { id = "subnet-00000000000000003" }
  override_during = plan
}

override_resource {
  target          = aws_subnet.network["app_b"]
  values          = { id = "subnet-00000000000000004" }
  override_during = plan
}

override_resource {
  target          = aws_subnet.network["data_a"]
  values          = { id = "subnet-00000000000000005" }
  override_during = plan
}

override_resource {
  target          = aws_subnet.network["data_b"]
  values          = { id = "subnet-00000000000000006" }
  override_during = plan
}

override_resource {
  target          = aws_route_table.network["public"]
  values          = { id = "rtb-00000000000000001" }
  override_during = plan
}

override_resource {
  target          = aws_route_table.network["app_a"]
  values          = { id = "rtb-00000000000000002" }
  override_during = plan
}

override_resource {
  target          = aws_route_table.network["app_b"]
  values          = { id = "rtb-00000000000000003" }
  override_during = plan
}

override_resource {
  target          = aws_route_table.network["data_a"]
  values          = { id = "rtb-00000000000000004" }
  override_during = plan
}

override_resource {
  target          = aws_route_table.network["data_b"]
  values          = { id = "rtb-00000000000000005" }
  override_during = plan
}

variables {
  enable_app_foundation            = false
  enable_data_foundation           = false
  app_host_count                   = 0
  enable_database_bootstrap_access = false
  enable_github_deployment         = false
  aws_account_id                   = "123456789012"
  availability_zones = {
    a = "ap-northeast-2a"
    b = "ap-northeast-2c"
  }
}

run "default_network_layout_and_routes" {
  command = plan

  assert {
    condition = { for name, subnet in aws_subnet.network : name => subnet.cidr_block } == {
      public_a = "10.20.0.0/24"
      public_b = "10.20.1.0/24"
      app_a    = "10.20.10.0/24"
      app_b    = "10.20.11.0/24"
      data_a   = "10.20.20.0/24"
      data_b   = "10.20.21.0/24"
    }
    error_message = "서브넷 6개는 합의한 주소·역할로 나뉘어야 합니다."
  }

  assert {
    condition = (
      aws_vpc.main.enable_dns_support && aws_vpc.main.enable_dns_hostnames &&
      alltrue([for subnet in aws_subnet.network : !subnet.map_public_ip_on_launch])
    )
    error_message = "사설 이름 해결을 활성화하고 EC2 공인 IP 자동 할당을 막아야 합니다."
  }

  assert {
    condition = { for name, association in aws_route_table_association.network : name => association.route_table_id } == {
      public_a = aws_route_table.network["public"].id
      public_b = aws_route_table.network["public"].id
      app_a    = aws_route_table.network["app_a"].id
      app_b    = aws_route_table.network["app_b"].id
      data_a   = aws_route_table.network["data_a"].id
      data_b   = aws_route_table.network["data_b"].id
    }
    error_message = "모든 서브넷을 역할에 맞는 라우팅 테이블에 명시적으로 연결해야 합니다."
  }

  assert {
    condition = { for name, association in aws_route_table_association.network : name => association.subnet_id } == {
      public_a = "subnet-00000000000000001"
      public_b = "subnet-00000000000000002"
      app_a    = "subnet-00000000000000003"
      app_b    = "subnet-00000000000000004"
      data_a   = "subnet-00000000000000005"
      data_b   = "subnet-00000000000000006"
    }
    error_message = "라우팅 연결 대상이 각 서브넷의 실제 식별자와 일치해야 합니다."
  }

  assert {
    condition = (
      aws_route.public_internet.route_table_id == aws_route_table.network["public"].id &&
      aws_route.public_internet.gateway_id == aws_internet_gateway.main.id &&
      aws_route.public_internet.destination_cidr_block == "0.0.0.0/0"
    )
    error_message = "Public의 기본 외부 경로는 IGW여야 합니다."
  }

  assert {
    condition = (
      toset(keys(aws_route.private_egress)) == toset(["app_a", "app_b", "data_a"]) &&
      { for name, route in aws_route.private_egress : name => route.route_table_id } == {
        app_a  = "rtb-00000000000000002"
        app_b  = "rtb-00000000000000003"
        data_a = "rtb-00000000000000004"
      } &&
      alltrue([for route in aws_route.private_egress : route.nat_gateway_id == aws_nat_gateway.main.id && route.destination_cidr_block == "0.0.0.0/0"]) &&
      length(aws_default_route_table.main.route) == 0
    )
    error_message = "App A/B·Data A만 NAT를 사용하고 Main에는 외부 경로를 만들지 않아야 합니다."
  }

  assert {
    condition = (
      aws_nat_gateway.main.availability_mode == "zonal" &&
      aws_nat_gateway.main.connectivity_type == "public" &&
      aws_nat_gateway.main.subnet_id == aws_subnet.network["public_a"].id &&
      aws_nat_gateway.main.allocation_id == aws_eip.nat.id
    )
    error_message = "NAT는 Public A에 EIP를 연결한 단일 Zonal Public NAT여야 합니다."
  }
}

run "changed_cidr_and_zones_propagate" {
  command = plan

  variables {
    vpc_cidr = "10.30.0.0/16"
    availability_zones = {
      a = "ap-northeast-2b"
      b = "ap-northeast-2d"
    }
  }

  assert {
    condition = { for name, subnet in aws_subnet.network : name => subnet.cidr_block } == {
      public_a = "10.30.0.0/24"
      public_b = "10.30.1.0/24"
      app_a    = "10.30.10.0/24"
      app_b    = "10.30.11.0/24"
      data_a   = "10.30.20.0/24"
      data_b   = "10.30.21.0/24"
    }
    error_message = "VPC CIDR을 바꾸면 모든 서브넷 주소도 같은 범위로 바뀌어야 합니다."
  }

  assert {
    condition = alltrue([
      for name, subnet in aws_subnet.network : subnet.availability_zone == (
        contains(["public_a", "app_a", "data_a"], name) ? "ap-northeast-2b" : "ap-northeast-2d"
      )
    ])
    error_message = "논리 A/B의 AZ 입력을 모든 서브넷에 일관되게 반영해야 합니다."
  }
}

run "reject_duplicate_zones" {
  command = plan
  variables {
    availability_zones = { a = "ap-northeast-2a", b = "ap-northeast-2a" }
  }
  expect_failures = [var.availability_zones]
}

run "reject_unavailable_zone" {
  command = plan
  variables {
    availability_zones = { a = "ap-northeast-2z", b = "ap-northeast-2c" }
  }
  expect_failures = [aws_vpc.main]
}

run "reject_public_vpc_cidr" {
  command = plan
  variables {
    vpc_cidr = "8.8.0.0/16"
  }
  expect_failures = [var.vpc_cidr]
}

run "reject_noncanonical_vpc_cidr" {
  command = plan
  variables {
    vpc_cidr = "10.20.1.0/16"
  }
  expect_failures = [var.vpc_cidr]
}

run "reject_non16_vpc_cidr" {
  command = plan
  variables {
    vpc_cidr = "10.20.0.0/24"
  }
  expect_failures = [var.vpc_cidr]
}
