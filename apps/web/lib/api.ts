export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public correlationId?: string,
  ) {
    super(message);
  }
}
let csrf: { token: string; headerName: string } | undefined;
export function clearCsrf() {
  csrf = undefined;
}
async function token() {
  if (!csrf) {
    const response = await fetch("/api/v1/auth/csrf", {
      credentials: "same-origin",
      cache: "no-store",
      signal: AbortSignal.timeout(15000),
    });
    if (!response.ok)
      throw new ApiError(
        response.status,
        "CSRF_UNAVAILABLE",
        "Cannot establish a secure session. Retry.",
      );
    csrf = (await response.json()) as { token: string; headerName: string };
  }
  return csrf;
}
export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body && !(init.body instanceof FormData))
    headers.set("Content-Type", "application/json");
  if (init.method && !["GET", "HEAD"].includes(init.method)) {
    const t = await token();
    if (t) headers.set(t.headerName, t.token);
  }
  const response = await fetch(`/api/v1${path}`, {
    ...init,
    headers,
    credentials: "same-origin",
    cache: "no-store",
    signal: init.signal ?? AbortSignal.timeout(15000),
  });
  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as {
      code?: string;
      detail?: string;
      correlationId?: string;
    };
    if (problem.code === "CSRF_INVALID") clearCsrf();
    throw new ApiError(
      response.status,
      problem.code ?? "REQUEST_FAILED",
      problem.detail ?? "Request failed. Please retry.",
      problem.correlationId,
    );
  }
  const text = await response.text();
  return text ? (JSON.parse(text) as T) : (undefined as T);
}
export function mutation(
  method: string,
  body?: unknown,
  version?: number,
  key?: string,
): RequestInit {
  return {
    method,
    body: body === undefined ? undefined : JSON.stringify(body),
    headers: {
      ...(version === undefined ? {} : { "If-Match": `"v${version}"` }),
      ...(key ? { "Idempotency-Key": key } : {}),
    },
  };
}
