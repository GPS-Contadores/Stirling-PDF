import { renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  fetchProxyIdentity,
  parseProxyUserinfo,
  PROXY_USERINFO_PATH,
  useProxyIdentity,
} from "@app/hooks/useProxyIdentity";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("parseProxyUserinfo", () => {
  it("prefers the email over the opaque OIDC subject", () => {
    expect(
      parseProxyUserinfo({
        user: "a1b2c3-subject",
        email: "lorenzo@gestao.com.br",
        preferredUsername: "lorenzo@gestao.com.br",
      }),
    ).toEqual({
      displayName: "lorenzo@gestao.com.br",
      email: "lorenzo@gestao.com.br",
    });
  });

  it("uses the name claim when the proxy passes it", () => {
    expect(
      parseProxyUserinfo({
        user: "sub",
        email: "kauan.holstein@gestao.com.br",
        additionalClaims: { name: "Kauan Holstein" },
      }),
    ).toEqual({
      displayName: "Kauan Holstein",
      email: "kauan.holstein@gestao.com.br",
    });
  });

  it("falls back to preferredUsername, then user", () => {
    expect(
      parseProxyUserinfo({ user: "sub", preferredUsername: "upn" }),
    ).toEqual({ displayName: "upn", email: null });
    expect(parseProxyUserinfo({ user: "sub", email: "" })).toEqual({
      displayName: "sub",
      email: null,
    });
  });

  it("returns null for an empty session or a non-object", () => {
    expect(parseProxyUserinfo({})).toBeNull();
    expect(parseProxyUserinfo(null)).toBeNull();
    expect(parseProxyUserinfo("<!doctype html>")).toBeNull();
    expect(parseProxyUserinfo([{ email: "x@y.z" }])).toBeNull();
  });
});

describe("fetchProxyIdentity", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("reads the same-origin userinfo endpoint", async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse({ user: "sub", email: "lorenzo@gestao.com.br" }),
    );
    vi.stubGlobal("fetch", fetchMock);

    await expect(fetchProxyIdentity()).resolves.toEqual({
      displayName: "lorenzo@gestao.com.br",
      email: "lorenzo@gestao.com.br",
    });
    expect(fetchMock).toHaveBeenCalledWith(
      PROXY_USERINFO_PATH,
      expect.objectContaining({ credentials: "same-origin" }),
    );
  });

  it("returns null on 401 and 404 (no proxy, e.g. local dev)", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => jsonResponse({}, 401)),
    );
    await expect(fetchProxyIdentity()).resolves.toBeNull();

    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response("Not Found", { status: 404 })),
    );
    await expect(fetchProxyIdentity()).resolves.toBeNull();
  });

  it("returns null when the SPA answers with index.html", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(
        async () =>
          new Response("<!doctype html><html></html>", {
            status: 200,
            headers: { "Content-Type": "text/html" },
          }),
      ),
    );
    await expect(fetchProxyIdentity()).resolves.toBeNull();
  });

  it("returns null when the request fails", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new TypeError("Failed to fetch");
      }),
    );
    await expect(fetchProxyIdentity()).resolves.toBeNull();
  });
});

describe("useProxyIdentity", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("does not call the proxy while disabled", () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const { result } = renderHook(() => useProxyIdentity(false));

    expect(result.current).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("exposes the identity once the proxy answers", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        jsonResponse({ user: "sub", email: "lorenzo@gestao.com.br" }),
      ),
    );

    const { result } = renderHook(() => useProxyIdentity(true));

    await waitFor(() =>
      expect(result.current?.displayName).toBe("lorenzo@gestao.com.br"),
    );
  });
});
