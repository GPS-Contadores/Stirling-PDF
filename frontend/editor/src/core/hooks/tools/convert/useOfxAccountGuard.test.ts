import { describe, expect, test, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { useConvertParameters } from "@app/hooks/tools/convert/useConvertParameters";
import { useOfxAccountGuard } from "@app/hooks/tools/convert/useOfxAccountGuard";

const NEEDS_ACCOUNT =
  'OFX não gerado: este extrato não imprime o número da conta. Preencha o campo "Número da conta" com a conta cadastrada no Questor e converta de novo.';

interface Props {
  errorMessage: string | null;
  convertedCount: number;
  selectionKey: string;
}

const setup = (reopenSettings = vi.fn()) => {
  const hook = renderHook<
    {
      params: ReturnType<typeof useConvertParameters>;
      accountError: string | null;
    },
    Props
  >(
    (props) => {
      const params = useConvertParameters();
      const accountError = useOfxAccountGuard({
        parameters: params.parameters,
        updateParameter: params.updateParameter,
        reopenSettings,
        ...props,
      });
      return { params, accountError };
    },
    {
      initialProps: {
        errorMessage: null,
        convertedCount: 0,
        selectionKey: "bb-site.pdf",
      },
    },
  );
  act(() => {
    hook.result.current.params.updateParameter("fromExtension", "pdf");
    hook.result.current.params.updateParameter("toExtension", "ofx");
    hook.result.current.params.updateParameter("ofxOptions", {
      conta: "98765-4",
    });
  });
  return hook;
};

const conta = (hook: ReturnType<typeof setup>) =>
  hook.result.current.params.parameters.ofxOptions?.conta;

describe("useOfxAccountGuard", () => {
  test("another statement selected: the typed account is forgotten", () => {
    // It would replace the account printed on the next statement, balance
    // check passed, with no warning.
    const hook = setup();
    expect(conta(hook)).toBe("98765-4");

    hook.rerender({
      errorMessage: null,
      convertedCount: 0,
      selectionKey: "outro-banco.pdf",
    });

    expect(conta(hook)).toBe("");
  });

  test("a conversion that produced files forgets the account", () => {
    const hook = setup();

    hook.rerender({
      errorMessage: null,
      convertedCount: 1,
      selectionKey: "bb-site.pdf",
    });

    expect(conta(hook)).toBe("");
  });

  test("an account refusal reopens the settings with the message on the field", () => {
    const reopenSettings = vi.fn();
    const hook = setup(reopenSettings);

    hook.rerender({
      errorMessage: NEEDS_ACCOUNT,
      convertedCount: 0,
      selectionKey: "bb-site.pdf",
    });

    expect(hook.result.current.accountError).toBe(NEEDS_ACCOUNT);
    expect(reopenSettings).toHaveBeenCalledTimes(1);
    // Refused, not converted: the account typed is still there to fix.
    expect(conta(hook)).toBe("98765-4");
  });

  test("the message on the field goes away with another selection", () => {
    const hook = setup();
    hook.rerender({
      errorMessage: NEEDS_ACCOUNT,
      convertedCount: 0,
      selectionKey: "bb-site.pdf",
    });

    hook.rerender({
      errorMessage: null,
      convertedCount: 0,
      selectionKey: "outro-banco.pdf",
    });

    expect(hook.result.current.accountError).toBeNull();
  });

  test("other refusals stay in the results", () => {
    const reopenSettings = vi.fn();
    const hook = setup(reopenSettings);

    hook.rerender({
      errorMessage: "OFX não gerado: Layout desconhecido.",
      convertedCount: 0,
      selectionKey: "bb-site.pdf",
    });

    expect(hook.result.current.accountError).toBeNull();
    expect(reopenSettings).not.toHaveBeenCalled();
  });
});
