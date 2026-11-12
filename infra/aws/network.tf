data "aws_availability_zones" "available" {
  state = "available"

  filter {
    name   = "zone-type"
    values = ["availability-zone"]
  }
}

locals {
  subnet_specs = {
    public_a = { az = "a", offset = 0, route_table = "public" }
    public_b = { az = "b", offset = 1, route_table = "public" }
    app_a    = { az = "a", offset = 10, route_table = "app_a" }
    app_b    = { az = "b", offset = 11, route_table = "app_b" }
    data_a   = { az = "a", offset = 20, route_table = "data_a" }
    data_b   = { az = "b", offset = 21, route_table = "data_b" }
  }

  route_tables = toset(["public", "app_a", "app_b", "data_a", "data_b"])
  nat_egress   = toset(["app_a", "app_b", "data_a"])
}

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "travelpay" }

  lifecycle {
    precondition {
      condition     = alltrue([for az in values(var.availability_zones) : contains(data.aws_availability_zones.available.names, az)])
      error_message = "선택한 AZ가 이 계정의 사용 가능한 서울 리전 일반 AZ 목록에 있어야 합니다."
    }
  }
}

resource "aws_subnet" "network" {
  for_each = local.subnet_specs

  vpc_id                  = aws_vpc.main.id
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, each.value.offset)
  availability_zone       = var.availability_zones[each.value.az]
  map_public_ip_on_launch = false

  tags = { Name = "travelpay-${each.key}" }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "travelpay" }
}

resource "aws_default_route_table" "main" {
  default_route_table_id = aws_vpc.main.default_route_table_id
  route                  = []

  tags = { Name = "travelpay-main-local-only" }
}

resource "aws_route_table" "network" {
  for_each = local.route_tables

  vpc_id = aws_vpc.main.id
  tags   = { Name = "travelpay-${each.key}" }
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.network["public"].id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.main.id
}

resource "aws_eip" "nat" {
  domain = "vpc"
  tags   = { Name = "travelpay-nat-a" }
}

resource "aws_nat_gateway" "main" {
  availability_mode = "zonal"
  connectivity_type = "public"
  subnet_id         = aws_subnet.network["public_a"].id
  allocation_id     = aws_eip.nat.id

  tags = { Name = "travelpay-nat-a" }

  # Public NAT의 EIP 연결에는 VPC에 연결된 IGW도 필요합니다.
  depends_on = [aws_internet_gateway.main]
}

resource "aws_route" "private_egress" {
  for_each = local.nat_egress

  route_table_id         = aws_route_table.network[each.key].id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.main.id
}

resource "aws_route_table_association" "network" {
  for_each = local.subnet_specs

  subnet_id      = aws_subnet.network[each.key].id
  route_table_id = aws_route_table.network[each.value.route_table].id
}
