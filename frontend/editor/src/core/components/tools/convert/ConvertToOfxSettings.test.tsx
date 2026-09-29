import { describe, expect, test, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { MantineProvider } from "@mantine/core";
import ConvertToOfxSettings from "@app/components/tools/convert/ConvertToOfxSettings";
import { defaultParameters } from "@app/hooks/tools/convert/useConvertParameters";

const NEEDS_ACCOUNT =
  'OFX não gerado: este extrato não imprime o número da conta. Preencha o campo "Número da conta" com a conta cadastrada no Questor e converta de novo.';

const renderSettings = (
  error: string | null = null,
  onParameterChange = vi.fn(),
) => {
  render(
    <MantineProvider>
      <ConvertToOfxSettings
        parameters={{
          ...defaultParameters,
          fromExtension: "pdf",
          toExtension: "ofx",
        }}
        onParameterChange={onParameterChange}
        error={error}
      />
    </MantineProvider>,
  );
  return onParameterChange;
};

describe("ConvertToOfxSettings", () => {
  test("shows the refusal on the field", () => {
    renderSettings(NEEDS_ACCOUNT);
    expect(screen.getByText(NEEDS_ACCOUNT)).toBeTruthy();
    expect(
      screen.getByTestId("ofx-conta-input").getAttribute("aria-invalid"),
    ).toBe("true");
  });

  test("no refusal, no error on the field", () => {
    renderSettings();
    expect(
      screen.getByTestId("ofx-conta-input").getAttribute("aria-invalid"),
    ).toBe("false");
  });

  test("keeps only what an account number has", () => {
    const onParameterChange = renderSettings();
    fireEvent.change(screen.getByTestId("ofx-conta-input"), {
      target: { value: "27.346-5/a\r\n" },
    });
    expect(onParameterChange).toHaveBeenCalledWith("ofxOptions", {
      conta: "27.346-5",
    });
  });
});
