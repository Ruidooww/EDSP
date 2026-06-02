import re


SENSITIVE_ASSIGNMENT_PATTERN = re.compile(
    r"(?i)\b(token|secret|password|api\s*[_-]?\s*key|authorization|endpoint|payload_json|normalized_json|"
    r"extra_json|config_json)\s*[:=]\s*(?:bearer\s+)?(?:\{[^}]*\}|\"[^\"]*\"|'[^']*'|[^\s,;]+)"
)
BEARER_PATTERN = re.compile(r"(?i)\bbearer\s+\S+")
JDBC_URL_PATTERN = re.compile(r"(?i)\bjdbc:[^\s,;]+")
URL_PATTERN = re.compile(r"(?i)https?://[^\s,;]+")


def redact(value: str) -> str:
    redacted = SENSITIVE_ASSIGNMENT_PATTERN.sub("[redacted]", value)
    redacted = BEARER_PATTERN.sub("[redacted]", redacted)
    redacted = JDBC_URL_PATTERN.sub("[redacted]", redacted)
    return URL_PATTERN.sub("[redacted]", redacted)

