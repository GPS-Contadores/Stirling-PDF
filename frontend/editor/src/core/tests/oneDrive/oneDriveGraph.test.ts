import { describe, expect, it, vi } from "vitest";
import {
  downloadPickedItems,
  SIMPLE_UPLOAD_LIMIT,
  UPLOAD_CHUNK_SIZE,
  uploadFileToFolder,
} from "@app/services/oneDriveGraph";

const GRAPH = "https://graph.microsoft.com/v1.0";

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

const folder = { id: "folder!1", parentReference: { driveId: "b!drive" } };

describe("downloadPickedItems", () => {
  it("reads metadata with the token and downloads through downloadUrl without it", async () => {
    const fetchMock = vi.fn(async (url: string) => {
      if (url.startsWith(GRAPH)) {
        return json({
          id: "i1",
          name: "extrato.pdf",
          lastModifiedDateTime: "2026-09-01T12:00:00Z",
          file: { mimeType: "application/pdf" },
          "@microsoft.graph.downloadUrl":
            "https://download.example/x?tempauth=1",
        });
      }
      return new Response("%PDF-1.7");
    });

    const [file] = await downloadPickedItems(
      [{ id: "i1", parentReference: { driveId: "b!drive" } }],
      "graph-token",
      fetchMock as unknown as typeof fetch,
    );

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      `${GRAPH}/drives/b!drive/items/i1`,
      { headers: { Authorization: "Bearer graph-token" } },
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "https://download.example/x?tempauth=1",
    );
    expect(file.name).toBe("extrato.pdf");
    expect(file.type).toBe("application/pdf");
    expect(file.lastModified).toBe(Date.parse("2026-09-01T12:00:00Z"));
    expect(file.size).toBe("%PDF-1.7".length);
  });

  it("refuses a picked folder", async () => {
    const fetchMock = vi.fn(async () =>
      json({ id: "f", name: "Pasta", folder: {} }),
    );
    await expect(
      downloadPickedItems(
        [{ id: "f", parentReference: { driveId: "d" } }],
        "t",
        fetchMock as unknown as typeof fetch,
      ),
    ).rejects.toThrow('"Pasta" is not a file');
  });

  it("surfaces Graph's error message", async () => {
    const fetchMock = vi.fn(async () =>
      json({ error: { message: "Access denied" } }, 403),
    );
    await expect(
      downloadPickedItems(
        [{ id: "i", name: "a.pdf", parentReference: { driveId: "d" } }],
        "t",
        fetchMock as unknown as typeof fetch,
      ),
    ).rejects.toThrow("HTTP 403 — Access denied");
  });
});

describe("uploadFileToFolder", () => {
  it("uses a simple PUT that renames on conflict for small files", async () => {
    const fetchMock = vi.fn(async () =>
      json({ id: "n", name: "saida 1.pdf", webUrl: "https://x/saida 1.pdf" }),
    );
    const file = new File(["abc"], "saída #1.pdf", { type: "application/pdf" });

    const uploaded = await uploadFileToFolder(
      file,
      folder,
      "graph-token",
      fetchMock as unknown as typeof fetch,
    );

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as unknown as [
      string,
      RequestInit,
    ];
    expect(url).toBe(
      `${GRAPH}/drives/b!drive/items/folder!1:/sa%C3%ADda%20%231.pdf:/content?@microsoft.graph.conflictBehavior=rename`,
    );
    expect(init.method).toBe("PUT");
    expect(init.headers).toMatchObject({
      Authorization: "Bearer graph-token",
      "Content-Type": "application/pdf",
    });
    expect(uploaded).toEqual({
      name: "saida 1.pdf",
      webUrl: "https://x/saida 1.pdf",
    });
  });

  it("uploads large files in 320 KiB-aligned chunks without the token", async () => {
    const size = 2 * UPLOAD_CHUNK_SIZE + 5;
    expect(size).toBeGreaterThan(SIMPLE_UPLOAD_LIMIT);
    const file = new File([new Uint8Array(size)], "grande.pdf");
    const fetchMock = vi.fn(async (url: string) => {
      if (url.endsWith("/createUploadSession")) {
        return json({ uploadUrl: "https://upload.example/session" });
      }
      return json({ id: "n", name: "grande.pdf" }, 202);
    });

    const uploaded = await uploadFileToFolder(
      file,
      folder,
      "graph-token",
      fetchMock as unknown as typeof fetch,
    );

    expect(UPLOAD_CHUNK_SIZE % (320 * 1024)).toBe(0);
    const calls = fetchMock.mock.calls as unknown as [string, RequestInit][];
    expect(calls[0][0]).toBe(
      `${GRAPH}/drives/b!drive/items/folder!1:/grande.pdf:/createUploadSession`,
    );
    expect(JSON.parse(calls[0][1].body as string)).toEqual({
      item: { "@microsoft.graph.conflictBehavior": "rename" },
    });

    const chunks = calls.slice(1);
    const ranges = chunks.map(
      ([, init]) => (init.headers as Record<string, string>)["Content-Range"],
    );
    const firstEnd = UPLOAD_CHUNK_SIZE - 1;
    expect(ranges).toEqual([
      `bytes 0-${firstEnd}/${size}`,
      `bytes ${UPLOAD_CHUNK_SIZE}-${2 * UPLOAD_CHUNK_SIZE - 1}/${size}`,
      `bytes ${2 * UPLOAD_CHUNK_SIZE}-${size - 1}/${size}`,
    ]);
    for (const [url, init] of chunks) {
      expect(url).toBe("https://upload.example/session");
      expect(init.headers).not.toHaveProperty("Authorization");
    }
    expect(uploaded.name).toBe("grande.pdf");
  });
});
