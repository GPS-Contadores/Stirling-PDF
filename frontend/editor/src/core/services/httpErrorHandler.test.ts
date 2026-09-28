import { describe, expect, test, vi, beforeEach } from "vitest";
import { alert } from "@app/components/toast";
import { handleHttpError } from "@app/services/httpErrorHandler";
import { extractAxiosErrorMessage } from "@app/services/httpErrorUtils";

vi.mock("@app/components/toast", () => ({ alert: vi.fn() }));

const PROBLEM_DETAIL = {
  type: "/errors/pdf-corrupted",
  title: "PDF File Corrupted",
  status: 400,
  detail:
    "PDF file appears to be corrupted or damaged. Please try using the 'Repair PDF' feature first to fix the file before proceeding with this operation.",
  errorCode: "E001",
};

// jsdom's Blob has no text(); every browser's does, and it is all that
// normalizeAxiosErrorData needs.
function blob(text: string, type = "application/problem+json"): Blob {
  return Object.assign(new Blob([text], { type }), {
    text: () => Promise.resolve(text),
  });
}

// Tool requests are made with `responseType: "blob"`, so axios hands the
// error body over as a Blob, not as parsed JSON.
function blobError(
  status: number,
  body: unknown,
  url = "/api/v1/general/rotate-pdf",
) {
  return {
    isAxiosError: true,
    config: { url },
    response: { status, data: blob(JSON.stringify(body)) },
  };
}

describe("handleHttpError — Blob bodies", () => {
  beforeEach(() => vi.mocked(alert).mockClear());

  test("shows the ProblemDetail `detail` instead of the generic fallback", async () => {
    await handleHttpError(blobError(400, PROBLEM_DETAIL));

    expect(alert).toHaveBeenCalledTimes(1);
    const shown = vi.mocked(alert).mock.calls[0][0];
    expect(shown.title).toBe("Request error");
    expect(shown.body).toContain("appears to be corrupted or damaged");
    expect(shown.body).not.toBe("There was an error processing your request.");
  });

  test("an empty Blob still falls back to the friendly message", async () => {
    await handleHttpError({
      isAxiosError: true,
      config: { url: "/api/v1/general/rotate-pdf-empty" },
      response: { status: 400, data: blob("") },
    });

    const shown = vi.mocked(alert).mock.calls[0][0];
    expect(shown.body).toBe("There was an error processing your request.");
  });
});

describe("extractAxiosErrorMessage", () => {
  test("reads `detail` from a parsed ProblemDetail", () => {
    const { body } = extractAxiosErrorMessage({
      isAxiosError: true,
      response: { status: 400, data: PROBLEM_DETAIL },
    });
    expect(body).toBe(PROBLEM_DETAIL.detail);
  });

  test("prefers the data passed in over the unread Blob on the error", () => {
    const error = blobError(400, PROBLEM_DETAIL);
    expect(extractAxiosErrorMessage(error).body).toBe(
      "There was an error processing your request.",
    );
    expect(extractAxiosErrorMessage(error, PROBLEM_DETAIL).body).toBe(
      PROBLEM_DETAIL.detail,
    );
  });
});
