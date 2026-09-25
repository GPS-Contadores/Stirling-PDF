import { describe, expect, test } from "vitest";
import {
  buildConvertFormData,
  conversionSummary,
  createFileFromResponse,
  payrollSummary,
  shouldProcessFilesSeparately,
} from "@app/hooks/tools/convert/useConvertOperation";
import { defaultParameters } from "@app/hooks/tools/convert/useConvertParameters";
import {
  getAvailableToExtensions,
  getEndpointName,
  getEndpointUrl,
} from "@app/utils/convertUtils";

// GPS-Contadores: payroll sheet (xlsx) → FP-EVENTOS TXT for Sênior/RUBI,
// served by the ofx service's /folha/api/converter through ConvertXlsxToRubi.

const params = (calculo = "") => ({
  ...defaultParameters,
  fromExtension: "xlsx",
  toExtension: "rubi",
  rubiOptions: { calculo },
});

const sheet = (name = "Folha ACME.xlsx") =>
  new File([new Uint8Array([0x50, 0x4b, 3, 4])], name);

describe("xlsx → RUBI TXT routing", () => {
  test("xlsx offers the RUBI TXT next to PDF, under Finance", () => {
    const targets = getAvailableToExtensions("xlsx");
    expect(targets.map((t) => t.value)).toEqual(
      expect.arrayContaining(["pdf", "rubi"]),
    );
    expect(targets.find((t) => t.value === "rubi")?.group).toBe("Finance");
  });

  test("resolves to the xlsx-to-rubi endpoint", () => {
    expect(getEndpointName("xlsx", "rubi")).toBe("xlsx-to-rubi");
    expect(getEndpointUrl("xlsx", "rubi")).toBe("/api/v1/convert/xlsx/rubi");
  });

  test("other spreadsheets are not offered the RUBI TXT", () => {
    // The service only reads the .xlsx layouts of the payroll department.
    expect(getEndpointName("xls", "rubi")).toBe("");
    expect(getEndpointName("ods", "rubi")).toBe("");
  });

  test("each sheet is converted on its own", () => {
    expect(
      shouldProcessFilesSeparately([sheet(), sheet("b.xlsx")], params()),
    ).toBe(true);
  });
});

describe("buildConvertFormData for xlsx → RUBI TXT", () => {
  test("sends the calculation code when informed", () => {
    const form = buildConvertFormData(params(" 505 "), [sheet()]);
    expect(form.get("calculo")).toBe("505");
    expect(form.getAll("fileInput")).toHaveLength(1);
  });

  test("leaves calculo out when blank, so the one in the sheet is used", () => {
    const form = buildConvertFormData(params("  "), [sheet()]);
    expect(form.has("calculo")).toBe(false);
  });
});

describe("payrollSummary (X-GPS-Lancamentos / X-GPS-Arquivos)", () => {
  test("reads the entry count and the number of TXT files", () => {
    expect(
      payrollSummary({ "x-gps-lancamentos": "8", "x-gps-arquivos": "2" }),
    ).toEqual({ entries: 8, files: 2 });
  });

  test("null for the OFX converter or garbage", () => {
    expect(payrollSummary({ "x-gps-lancamentos": "8" })).toBeNull();
    expect(
      payrollSummary({ "x-gps-lancamentos": "8", "x-gps-arquivos": "dois" }),
    ).toBeNull();
    expect(payrollSummary(undefined)).toBeNull();
  });

  test("the OFX summary stays out of a RUBI response", () => {
    // Both converters send X-GPS-Lancamentos; without this the RUBI TXT
    // would get the "OFX ready to import" toast.
    expect(
      conversionSummary({ "x-gps-lancamentos": "8", "x-gps-arquivos": "1" }),
    ).toBeNull();
  });
});

describe("createFileFromResponse for the RUBI TXT", () => {
  test("keeps the name the converter chose", () => {
    const file = createFileFromResponse(
      new Blob(["x"]),
      {
        "content-type": "text/plain; charset=windows-1252",
        "content-disposition":
          'form-data; name="attachment"; filename="FP_EVENTOS_0150_202607_fixo.txt"',
      },
      "Folha ACME.xlsx",
      "rubi",
    );
    expect(file.name).toBe("FP_EVENTOS_0150_202607_fixo.txt");
  });

  test("falls back to .txt, not .rubi", () => {
    const file = createFileFromResponse(
      new Blob(["x"]),
      { "content-type": "text/plain" },
      "Folha ACME.xlsx",
      "rubi",
    );
    expect(file.name).toBe("Folha ACME.txt");
  });
});
