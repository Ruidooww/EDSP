from app.safety.redaction import redact


def test_redaction_masks_tokens_urls_and_sensitive_fields():
    message = (
        "Authorization: Bearer abc.def.ghi "
        "api_key=demo-key "
        "API Key: space-key "
        "endpoint=https://example-webhook.local/hook "
        "jdbc:postgresql://user:pass@host/db "
        'payload_json={"secret":"value"}'
    )

    redacted = redact(message)

    assert "[redacted]" in redacted
    assert "abc.def.ghi" not in redacted
    assert "demo-key" not in redacted
    assert "space-key" not in redacted
    assert "example-webhook.local" not in redacted
    assert "jdbc:postgresql" not in redacted
    assert "payload_json" not in redacted
    assert '"value"' not in redacted
