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
