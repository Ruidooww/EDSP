# EDSP AI Agent Service

This optional FastAPI service consumes Java-generated aggregate counts and returns read-only security insight sections.

```powershell
python -m pip install -r requirements.txt
python -m pytest
uvicorn app.main:app --host 127.0.0.1 --port 18090
```

Use `fallback-template` without model credentials. Configure local or cloud OpenAI-compatible providers with environment variables only. Do not commit `.env` files or real credentials.

