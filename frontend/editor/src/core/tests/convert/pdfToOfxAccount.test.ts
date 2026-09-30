import { beforeEach, describe, expect, test, vi } from "vitest";
import {
  buildConvertFormData,
  convertProcessor,
  ofxAccountRefusal,
} from "@app/hooks/tools/convert/useConvertOperation";
import apiClient from "@app/services/apiClient";

vi.mock("@app/services/apiClient", () => ({
  default: { post: vi.fn() },
}));
vi.mock("@app/components/toast", () => ({ alert: vi.fn() }));
import { defaultParameters } from "@app/hooks/tools/convert/useConvertParameters";

// GPS-Contadores: the Banco do Brasil statement downloaded from the website
// does not print the account number, and the ofx service refuses it without
// one. The account typed in the Convert tool goes as "conta" (#42).

const params = (conta = "") => ({
  ...defaultParameters,
  fromExtension: "pdf",
  toExtension: "ofx",
  ofxOptions: { conta },
});

const statement = (name = "extrato.pdf") =>
  new File([new Uint8Array([0x25, 0x50, 0x44, 0x46])], name);

describe("buildConvertFormData for PDF → OFX", () => {
  test("sends the account when informed", () => {
    const form = buildConvertFormData(params(" 98765-4 "), [statement()]);
    expect(form.get("conta")).toBe("98765-4");
    expect(form.getAll("fileInput")).toHaveLength(1);
  });

  test("leaves conta out when blank, so the printed account is used", () => {
    const form = buildConvertFormData(params("  "), [statement()]);
    expect(form.has("conta")).toBe(false);
  });
});

describe("ofxAccountRefusal", () => {
  // NEEDS_ACCOUNT and INVALID_ACCOUNT in ConvertPDFToOfx.java.
  test.each([
    'OFX não gerado: este extrato não imprime o número da conta. Preencha o campo "Número da conta" com a conta cadastrada no Questor e converta de novo.',
    'OFX não gerado: corrija o campo "Número da conta". Use só números, ponto, hífen e o dígito X, como a conta está cadastrada no Questor.',
    // Several statements: the reasons come joined, one per file.
    'a.pdf: OFX não gerado: este extrato não imprime o número da conta. Preencha o campo "Número da conta" com a conta cadastrada no Questor e converta de novo.',
  ])("highlights the field for %s", (message) => {
    expect(ofxAccountRefusal(message)).toBe(true);
  });

  test.each([
    null,
    "",
    "OFX não gerado: Layout desconhecido.",
    "OFX não gerado: Não achei o número da conta no cabeçalho.",
  ])("leaves other refusals as they are: %s", (message) => {
    expect(ofxAccountRefusal(message)).toBe(false);
  });
});

describe("convertProcessor for PDF → OFX with a typed account", () => {
  beforeEach(() => {
    vi.mocked(apiClient.post).mockReset();
  });

  test("refuses several statements, which would all get the same account", async () => {
    await expect(
      convertProcessor(params("98765-4"), [statement(), statement("b.pdf")]),
    ).rejects.toThrow(/one statement/);
    expect(apiClient.post).not.toHaveBeenCalled();
  });

  test("several statements without an account still go one by one", async () => {
    vi.mocked(apiClient.post).mockResolvedValue({
      data: new Blob(["OFXHEADER:100"]),
      headers: {},
    });

    await convertProcessor(params(""), [statement(), statement("b.pdf")]);

    expect(apiClient.post).toHaveBeenCalledTimes(2);
  });
});
