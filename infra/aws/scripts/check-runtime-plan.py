#!/usr/bin/env python3
"""저장한 plan을 적용하기 전에 초기 ASG와 Capacity Provider 연결 순서를 확인합니다."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys


class PlanSafetyError(Exception):
    pass


def check_plan(plan, lookup):
    changes = {item["address"]: item["change"] for item in plan.get("resource_changes", [])}
    group = changes.get("aws_autoscaling_group.app[0]")
    if not group or group["actions"] == ["no-op"]:
        return "unchanged"
    after = group.get("after") or {}
    before = group.get("before") or {}
    desired = after.get("desired_capacity")
    if desired == 0:
        if before.get("desired_capacity", 0) > 0:
            raise PlanSafetyError("초기 준비 단계로 실행 중인 앱 서버를 중단할 수 없습니다.")
        return "prepare"
    if desired != 2:
        raise PlanSafetyError("앱 서버 시작은 정상 운영값 2대만 허용합니다.")
    if "create" in group["actions"] or "delete" in group["actions"]:
        raise PlanSafetyError("먼저 app_host_count=0으로 빈 ASG와 Capacity Provider를 적용해야 합니다.")

    asg_arn = before.get("arn")
    if not asg_arn or after.get("arn") not in (None, asg_arn):
        raise PlanSafetyError("현재 ASG의 식별자를 확정할 수 없습니다.")

    planned = {
        item["address"]: item["values"]
        for item in plan.get("planned_values", {}).get("root_module", {}).get("resources", [])
    }

    def name(address):
        values = changes.get(address, {}).get("after") or planned.get(address, {})
        value = values.get("name")
        if not value:
            raise PlanSafetyError("클러스터와 Capacity Provider 이름을 확정할 수 없습니다.")
        return value

    cluster = name("aws_ecs_cluster.main[0]")
    provider = name("aws_ecs_capacity_provider.app[0]")
    state = lookup(cluster, provider)
    if (
        state["cluster_status"] != "ACTIVE"
        or provider not in state["cluster_providers"]
        or state["provider_status"] != "ACTIVE"
        or state["provider_update_status"] not in (None, "UPDATE_COMPLETE")
        or state["provider_asg_arn"] != asg_arn
    ):
        raise PlanSafetyError("AWS의 클러스터·Capacity Provider·ASG 연결이 준비되지 않았습니다.")
    return "hosts"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("plan", type=Path)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--region", default="ap-northeast-2")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    env = dict(os.environ, AWS_PROFILE=args.profile)

    def request(command):
        result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=60)
        if result.returncode:
            raise PlanSafetyError("조회 명령이 실패했습니다. AWS 인증과 조회 권한을 확인하세요.")
        return json.loads(result.stdout)

    def aws(operation, *parameters):
        return request([
            "aws", "ecs", operation, "--profile", args.profile, "--region", args.region,
            "--output", "json", "--no-cli-pager", *parameters,
        ])

    def lookup(cluster_name, provider_name):
        clusters = aws("describe-clusters", "--clusters", cluster_name).get("clusters", [])
        providers = aws("describe-capacity-providers", "--capacity-providers", provider_name).get("capacityProviders", [])
        if len(clusters) != 1 or len(providers) != 1:
            raise PlanSafetyError("대상 클러스터 또는 Capacity Provider가 존재하지 않습니다.")
        cluster, provider = clusters[0], providers[0]
        return {
            "cluster_status": cluster["status"],
            "cluster_providers": cluster.get("capacityProviders", []),
            "provider_status": provider["status"],
            "provider_update_status": provider.get("updateStatus"),
            "provider_asg_arn": provider["autoScalingGroupProvider"]["autoScalingGroupArn"],
        }

    plan = request(["terraform", f"-chdir={root}", "show", "-json", str(args.plan.resolve())])
    result = check_plan(plan, lookup)
    print(f"Runtime plan guard passed: {result}. 이 검사는 AWS 리소스를 변경하지 않습니다.")


if __name__ == "__main__":
    try:
        main()
    except (PlanSafetyError, KeyError, ValueError, subprocess.TimeoutExpired) as error:
        print(f"Runtime plan guard failed: {error}", file=sys.stderr)
        sys.exit(1)
