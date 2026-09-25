import { describe, expect, test } from "vitest";
import { buildCertSignFormData } from "@app/hooks/tools/certSign/useCertSignOperation";
import {
  CertSignParameters,
  defaultParameters,
} from "@app/hooks/tools/certSign/useCertSignParameters";

const pdf = () =>
  new File(["%PDF-1.4"], "doc.pdf", { type: "application/pdf" });

const params = (
  overrides: Partial<CertSignParameters>,
): CertSignParameters => ({
  ...defaultParameters,
  ...overrides,
});

describe("buildCertSignFormData - hardware cert types", () => {
  test("WINDOWS_STORE sends certType and alias, no files", () => {
    const formData = buildCertSignFormData(
      params({
        signMode: "MANUAL",
        certType: "WINDOWS_STORE",
        alias: "My Signing Cert",
      }),
      pdf(),
    );

    expect(formData.get("certType")).toBe("WINDOWS_STORE");
    expect(formData.get("alias")).toBe("My Signing Cert");
    expect(formData.get("p12File")).toBeNull();
    expect(formData.get("jksFile")).toBeNull();
  });

  test("PKCS11 sends driver path, slot, alias and PIN (as password)", () => {
    const formData = buildCertSignFormData(
      params({
        signMode: "MANUAL",
        certType: "PKCS11",
        pkcs11LibraryPath: "/usr/lib/opensc-pkcs11.so",
        pkcs11Slot: 0,
        alias: "token-cert",
        password: "1234",
      }),
      pdf(),
    );

    expect(formData.get("certType")).toBe("PKCS11");
    expect(formData.get("pkcs11LibraryPath")).toBe("/usr/lib/opensc-pkcs11.so");
    expect(formData.get("pkcs11Slot")).toBe("0");
    expect(formData.get("alias")).toBe("token-cert");
    expect(formData.get("password")).toBe("1234");
  });

  test("PKCS11 omits slot when not provided", () => {
    const formData = buildCertSignFormData(
      params({
        signMode: "MANUAL",
        certType: "PKCS11",
        pkcs11LibraryPath: "/usr/lib/opensc-pkcs11.so",
        alias: "token-cert",
        password: "1234",
      }),
      pdf(),
    );

    expect(formData.get("pkcs11Slot")).toBeNull();
  });

  test("AUTO mode still maps to SERVER without hardware fields", () => {
    const formData = buildCertSignFormData(params({ signMode: "AUTO" }), pdf());

    expect(formData.get("certType")).toBe("SERVER");
    expect(formData.get("alias")).toBeNull();
    expect(formData.get("pkcs11LibraryPath")).toBeNull();
  });
});

describe("buildCertSignFormData - signature area", () => {
  const area = { x: 0.5, y: 0.8, width: 0.3, height: 0.1 };

  test("visible signature with a drawn area sends its fractions", () => {
    const formData = buildCertSignFormData(
      params({ signMode: "AUTO", showSignature: true, signatureArea: area }),
      pdf(),
    );

    expect(formData.get("signatureX")).toBe("0.5");
    expect(formData.get("signatureY")).toBe("0.8");
    expect(formData.get("signatureWidth")).toBe("0.3");
    expect(formData.get("signatureHeight")).toBe("0.1");
  });

  test("without a drawn area no position is sent (default placement)", () => {
    const formData = buildCertSignFormData(
      params({ signMode: "AUTO", showSignature: true }),
      pdf(),
    );

    expect(formData.get("pageNumber")).toBe("1");
    expect(formData.get("signatureX")).toBeNull();
    expect(formData.get("signatureHeight")).toBeNull();
  });

  test("invisible signature ignores a leftover area", () => {
    const formData = buildCertSignFormData(
      params({ signMode: "AUTO", showSignature: false, signatureArea: area }),
      pdf(),
    );

    expect(formData.get("signatureX")).toBeNull();
  });
});
