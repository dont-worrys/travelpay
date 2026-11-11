"""검토할 plan의 실제 호스트 시작 조건을 검사합니다. AWS 호출은 정상화한 조회 결과만 대체합니다."""

import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).parents[1] / "scripts" / "check-runtime-plan.py"
spec = importlib.util.spec_from_file_location("runtime_plan_guard", SCRIPT)
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)

ASG_ARN = "arn:aws:autoscaling:ap-northeast-2:123456789012:autoScalingGroup:00000000:autoScalingGroupName/travelpay-production-app"


def plan(actions, desired, before=None):
    return {
        "resource_changes": [
            {"address": "aws_autoscaling_group.app[0]", "change": {
                "actions": actions, "before": before,
                "after": {"arn": ASG_ARN, "desired_capacity": desired},
            }},
            {"address": "aws_ecs_cluster.main[0]", "change": {
                "actions": ["no-op"], "after": {"name": "travelpay-production"},
            }},
            {"address": "aws_ecs_capacity_provider.app[0]", "change": {
                "actions": ["no-op"], "after": {"name": "travelpay-production-app"},
            }},
        ]
    }


def ready(asg=ASG_ARN, attached=True):
    return {
        "cluster_status": "ACTIVE",
        "cluster_providers": ["travelpay-production-app"] if attached else [],
        "provider_status": "ACTIVE", "provider_update_status": "UPDATE_COMPLETE",
        "provider_asg_arn": asg,
    }


class RuntimePlanGuardTest(unittest.TestCase):
    def test_initial_group_must_be_empty(self):
        with self.assertRaises(guard.PlanSafetyError):
            guard.check_plan(plan(["create"], 2), lambda *_: ready())

    def test_empty_group_can_prepare_without_existing_provider(self):
        self.assertEqual(guard.check_plan(plan(["create"], 0), lambda *_: {}), "prepare")

    def test_preparation_cannot_stop_existing_hosts(self):
        with self.assertRaises(guard.PlanSafetyError):
            guard.check_plan(plan(["update"], 0, {"arn": ASG_ARN, "desired_capacity": 2}), lambda *_: {})

    def test_scale_out_requires_cluster_association(self):
        with self.assertRaises(guard.PlanSafetyError):
            guard.check_plan(plan(["update"], 2, {"arn": ASG_ARN}), lambda *_: ready(attached=False))

    def test_provider_must_reference_the_same_group(self):
        with self.assertRaises(guard.PlanSafetyError):
            guard.check_plan(plan(["update"], 2, {"arn": ASG_ARN}), lambda *_: ready(asg="other-group"))

    def test_ready_existing_group_can_start_hosts(self):
        self.assertEqual(guard.check_plan(plan(["update"], 2, {"arn": ASG_ARN}), lambda *_: ready()), "hosts")


if __name__ == "__main__":
    unittest.main()
