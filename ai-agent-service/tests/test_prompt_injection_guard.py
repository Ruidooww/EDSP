from types import SimpleNamespace

import pytest

from app.safety.policy import (
    SAFE_POLICY_WARNING_CODE,
    PolicyCategory,
    evaluate_prompt_context,
)
from app.safety.prompt_guard import build_safe_prompt


@pytest.mark.parametrize(
    ("context", "category"),
    [
        ({"payload_json": '{"secret":"demo"}'}, PolicyCategory.RAW_PAYLOAD_REQUEST),
        ({"rawEventDetails": "full event body"}, PolicyCategory.RAW_PAYLOAD_REQUEST),
        ({"sourceConfig": "endpoint=https://internal.example"}, PolicyCategory.ENDPOINT_EXPOSURE),
        ({"endpoint": "https://internal.example"}, PolicyCategory.ENDPOINT_EXPOSURE),
        ({"apiKey": "demo-key"}, PolicyCategory.SECRET_EXFILTRATION),
        ({"providerConfig": {"model": "demo"}}, PolicyCategory.RAW_PAYLOAD_REQUEST),
    ],
)
def test_prompt_policy_blocks_sensitive_context(context, category):
    violations = evaluate_prompt_context(context)

    assert category in {violation.category for violation in violations}


def test_build_safe_prompt_rejects_sensitive_context_without_echoing_input():
    unsafe_context = SimpleNamespace(model_dump=lambda: {"payload_json": '{"secret":"demo"}'})
    unsafe_request = SimpleNamespace(
        theme="security_overview",
        period="last_7_days",
        context=unsafe_context,
    )

    with pytest.raises(ValueError) as exc_info:
        build_safe_prompt(unsafe_request, 4000)

    assert str(exc_info.value) == SAFE_POLICY_WARNING_CODE
    assert "secret" not in str(exc_info.value)
    assert "payload_json" not in str(exc_info.value)
