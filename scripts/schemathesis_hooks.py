import os
import uuid
import schemathesis


@schemathesis.hook
def before_call(context, case, kwargs):
    headers = dict(kwargs.get("headers") or {})
    path = case.operation.path

    if path.startswith("/api/admin"):
        admin_key = os.environ.get("SCHEMATHESIS_ADMIN_KEY", "")
        if admin_key and "X-Admin-Key" not in headers:
            headers["X-Admin-Key"] = admin_key
    elif not path.startswith("/api/auth"):
        token = os.environ.get("SCHEMATHESIS_AUTH_TOKEN", "")
        if token and "Authorization" not in headers:
            headers["Authorization"] = f"Bearer {token}"

    if case.operation.method.upper() == "POST" and "Idempotency-Key" not in headers:
        headers["Idempotency-Key"] = str(uuid.uuid4())

    kwargs["headers"] = headers
