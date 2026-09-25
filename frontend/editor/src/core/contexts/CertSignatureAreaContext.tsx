import React, { createContext, useContext, useMemo, useState } from "react";
import type { SignatureArea } from "@app/utils/signatureAreaCoordinates";

/**
 * What the certificate-signing tool asks the main viewer to show while the
 * user picks where the visible signature goes.
 */
export interface CertSignatureAreaPlacement {
  /** 0-based page the area belongs to. */
  pageIndex: number;
  /** Fractions of the page as displayed (after /Rotate); null = default position. */
  area: SignatureArea | null;
  /** Called with the page and area the user drew, moved or resized. */
  onChange: (pageIndex: number, area: SignatureArea) => void;
  disabled?: boolean;
}

interface CertSignatureAreaContextValue {
  placement: CertSignatureAreaPlacement | null;
  setPlacement: React.Dispatch<
    React.SetStateAction<CertSignatureAreaPlacement | null>
  >;
}

const CertSignatureAreaContext = createContext<
  CertSignatureAreaContextValue | undefined
>(undefined);

export function CertSignatureAreaProvider({
  children,
}: {
  children: React.ReactNode;
}) {
  const [placement, setPlacement] = useState<CertSignatureAreaPlacement | null>(
    null,
  );

  const value = useMemo<CertSignatureAreaContextValue>(
    () => ({ placement, setPlacement }),
    [placement],
  );

  return (
    <CertSignatureAreaContext.Provider value={value}>
      {children}
    </CertSignatureAreaContext.Provider>
  );
}

/** Safe outside the provider (e.g. a standalone viewer): nothing to place. */
export function useCertSignatureArea(): CertSignatureAreaContextValue {
  return (
    useContext(CertSignatureAreaContext) ?? {
      placement: null,
      setPlacement: () => {},
    }
  );
}
