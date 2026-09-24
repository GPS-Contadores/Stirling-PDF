import { describe, expect, test } from "vitest";
import {
  conversionErrorMessage,
  conversionSummary,
  conversionWarnings,
} from "@app/hooks/tools/convert/useConvertOperation";

const base64Json = (value: unknown) =>
  btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(value))));

describe("conversionWarnings (X-GPS-Avisos)", () => {
  test("decodes base64 UTF-8 JSON, accents included", () => {
    const headers = {
      "x-gps-avisos": base64Json(["Não identifiquei o banco no cabeçalho."]),
    };
    expect(conversionWarnings(headers)).toEqual([
      "Não identifiquei o banco no cabeçalho.",
    ]);
  });

  test("returns [] for an empty list, a missing header or garbage", () => {
    expect(conversionWarnings({ "x-gps-avisos": base64Json([]) })).toEqual([]);
    expect(conversionWarnings({})).toEqual([]);
    expect(conversionWarnings(undefined)).toEqual([]);
    expect(conversionWarnings({ "x-gps-avisos": "not base64 json" })).toEqual(
      [],
    );
  });

  test("drops non-string and blank entries", () => {
    const headers = { "x-gps-avisos": base64Json(["ok", 3, "  ", null]) };
    expect(conversionWarnings(headers)).toEqual(["ok"]);
  });
});

describe("conversionSummary (X-GPS-Lancamentos / Parser / Conferencia)", () => {
  test("reads the transaction count, the layout and the balance check", () => {
    const headers = {
      "x-gps-lancamentos": "42",
      "x-gps-parser": "bradesco-extrato",
      "x-gps-conferencia": "ok",
    };
    expect(conversionSummary(headers)).toEqual({
      entries: 42,
      parser: "bradesco-extrato",
      balanceOk: true,
    });
  });

  test("a balance mismatch is false, a missing check is null", () => {
    expect(
      conversionSummary({
        "x-gps-lancamentos": "3",
        "x-gps-conferencia": "divergente",
      }),
    ).toEqual({ entries: 3, parser: null, balanceOk: false });
    expect(conversionSummary({ "x-gps-lancamentos": "0" })).toEqual({
      entries: 0,
      parser: null,
      balanceOk: null,
    });
  });

  test("returns null for other converters or a malformed count", () => {
    expect(conversionSummary({})).toBeNull();
    expect(conversionSummary(undefined)).toBeNull();
    expect(conversionSummary({ "x-gps-lancamentos": "12a" })).toBeNull();
  });
});

describe("conversionErrorMessage", () => {
  // jsdom's Blob has no .text() (browsers do), and normalizeAxiosErrorData
  // duck-types on it, so the body is a Blob-like object with text().
  const blobError = (body: unknown) => ({
    message: "Request failed with status code 400",
    response: {
      data: { text: async () => JSON.stringify(body) },
    },
  });

  test("reads the ProblemDetail detail from a Blob body", async () => {
    const error = blobError({ detail: "OFX não gerado: o saldo não fecha." });
    expect(await conversionErrorMessage(error)).toBe(
      "OFX não gerado: o saldo não fecha.",
    );
  });

  test('reads the job "error" field and drops the "Job failed: " prefix', async () => {
    const error = blobError({
      error: "Job failed: O conversor OFX não respondeu.",
    });
    expect(await conversionErrorMessage(error)).toBe(
      "O conversor OFX não respondeu.",
    );
  });

  test("falls back to the axios message without a readable body", async () => {
    expect(await conversionErrorMessage(new Error("Network Error"))).toBe(
      "Network Error",
    );
  });
});
