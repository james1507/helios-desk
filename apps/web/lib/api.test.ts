import { afterEach, expect, it, vi } from "vitest";
import { api, ApiError, clearCsrf, mutation } from "./api";
afterEach(() => {
  vi.unstubAllGlobals();
  clearCsrf();
});
it("uses cookie credentials and CSRF for writes; does not retry mutations", async () => {
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(
      new Response(
        JSON.stringify({ token: "csrf-token", headerName: "X-CSRF-TOKEN" }),
      ),
    )
    .mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          status: 409,
          code: "TICKET_VERSION_CONFLICT",
          detail: "Refresh; draft retained.",
          correlationId: "request-id",
        }),
        { status: 409 },
      ),
    );
  vi.stubGlobal("fetch", fetch);
  await expect(
    api("/tickets/test", mutation("PATCH", { priority: "HIGH" }, 4)),
  ).rejects.toBeInstanceOf(ApiError);
  expect(fetch).toHaveBeenCalledTimes(2);
  const call = fetch.mock.calls[1];
  expect(call[1].credentials).toBe("same-origin");
  expect(call[1].headers.get("X-CSRF-TOKEN")).toBe("csrf-token");
  expect(call[1].headers.get("If-Match")).toBe('"v4"');
});
it("safe GET skips CSRF and returns typed data", async () => {
  const fetch = vi.fn().mockResolvedValue(new Response('{"items":[]}'));
  vi.stubGlobal("fetch", fetch);
  expect(await api("/tickets")).toEqual({ items: [] });
  expect(fetch).toHaveBeenCalledTimes(1);
});
