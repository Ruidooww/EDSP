import re
from dataclasses import dataclass
from enum import Enum
from typing import Mapping


SAFE_POLICY_WARNING_CODE = "ai_agent_safety_policy_blocked"


class PolicyCategory(str, Enum):
    SECRET_EXFILTRATION = "SECRET_EXFILTRATION"
    ENDPOINT_EXPOSURE = "ENDPOINT_EXPOSURE"
    RAW_PAYLOAD_REQUEST = "RAW_PAYLOAD_REQUEST"
    SQL_GENERATION = "SQL_GENERATION"
    ACTION_EXECUTION_CLAIM = "ACTION_EXECUTION_CLAIM"
    FILE_ACCESS_REQUEST = "FILE_ACCESS_REQUEST"
    SHELL_EXECUTION_REQUEST = "SHELL_EXECUTION_REQUEST"
    NOTIFICATION_TRIGGER_REQUEST = "NOTIFICATION_TRIGGER_REQUEST"
    LIFECYCLE_MUTATION_REQUEST = "LIFECYCLE_MUTATION_REQUEST"
    UNSUPPORTED_IDENTITY_CLAIM = "UNSUPPORTED_IDENTITY_CLAIM"
    UNSAFE_URL_OUTPUT = "UNSAFE_URL_OUTPUT"


@dataclass(frozen=True)
class PolicyViolation:
    category: PolicyCategory
    warning_code: str = SAFE_POLICY_WARNING_CODE


ALLOWED_CONTEXT_KEYS = {
    "rawEventCount",
    "standardEventCount",
    "alertDecisionCount",
    "matchedDecisionCount",
    "notMatchedDecisionCount",
    "errorDecisionCount",
    "alertCount",
    "openAlertCount",
    "criticalAlertCount",
    "highAlertCount",
    "warningSyncCount",
    "failedDecisionCount",
    "notificationDeliveryCount",
}


TEXT_RULES = (
    (
        PolicyCategory.SECRET_EXFILTRATION,
        re.compile(r"(?i)(api\s*[_-]?\s*key|\bbearer\s+\S+|\bauthorization\b|\btoken\b|\bsecret\b|\bpassword\b)"),
    ),
    (
        PolicyCategory.ENDPOINT_EXPOSURE,
        re.compile(r"(?i)(\bendpoint\b|\bjdbc:|\bwebhook\b)"),
    ),
    (
        PolicyCategory.RAW_PAYLOAD_REQUEST,
        re.compile(
            r"(?i)(payload_json|normalized_json|extra_json|config_json|\braw[_ -]?events?\b|"
            r"(?<!no )\braw[_ -]?payload\b|\bsource[_ -]?config\b|\bprovider[_ -]?config\b|\bmodel[_ -]?config\b)"
        ),
    ),
    (
        PolicyCategory.SQL_GENERATION,
        re.compile(r"(?i)(\bsql\b|\bselect\b.+\bfrom\b|\binsert\s+into\b|\bupdate\s+\w+\s+set\b|\bdelete\s+from\b|\bquery\s+raw_events\b)"),
    ),
    (
        PolicyCategory.ACTION_EXECUTION_CLAIM,
        re.compile(r"(?i)(已执行|已关闭告警|已发送通知|已修改规则|\bexecuted\b|\baction\s+completed\b)"),
    ),
    (
        PolicyCategory.FILE_ACCESS_REQUEST,
        re.compile(r"(?i)(/etc/passwd|\bread\s+(?:the\s+)?file\b|读取文件|\bcat\s+[/\\])"),
    ),
    (
        PolicyCategory.SHELL_EXECUTION_REQUEST,
        re.compile(r"(?i)(\bshell\b|\bbash\b|\bpowershell\b|\bcmd\.exe\b|\bexec(?:ute)?\s+command\b|执行命令)"),
    ),
    (
        PolicyCategory.NOTIFICATION_TRIGGER_REQUEST,
        re.compile(r"(?i)(\bsend\s+(?:a\s+)?notification\b|发送通知|已发送通知|\bwebhook\b)"),
    ),
    (
        PolicyCategory.LIFECYCLE_MUTATION_REQUEST,
        re.compile(r"(?i)(\bclose\s+(?:all\s+)?(?:critical\s+)?alerts?\b|\bmodify\s+rules?\b|\bupdate\s+rules?\b|关闭告警|已关闭告警|修改规则|已修改规则)"),
    ),
    (
        PolicyCategory.UNSUPPORTED_IDENTITY_CLAIM,
        re.compile(r"(?i)(\bi\s+am\s+(?:an?\s+)?admin(?:istrator)?\b|\bsystem\s+administrator\b|我是.{0,12}管理员|作为.{0,12}管理员)"),
    ),
    (
        PolicyCategory.UNSAFE_URL_OUTPUT,
        re.compile(r"(?i)https?://"),
    ),
)


def evaluate_text(value: str) -> tuple[PolicyViolation, ...]:
    return tuple(
        PolicyViolation(category)
        for category, pattern in TEXT_RULES
        if pattern.search(value)
    )


def evaluate_prompt_context(context: Mapping[str, object]) -> tuple[PolicyViolation, ...]:
    violations: list[PolicyViolation] = []
    for key, value in context.items():
        if key not in ALLOWED_CONTEXT_KEYS:
            violations.append(PolicyViolation(PolicyCategory.RAW_PAYLOAD_REQUEST))
        violations.extend(evaluate_text(f"{key}={value}"))
        if type(value) is not int or value < 0:
            violations.append(PolicyViolation(PolicyCategory.RAW_PAYLOAD_REQUEST))
    return tuple(dict.fromkeys(violations))
