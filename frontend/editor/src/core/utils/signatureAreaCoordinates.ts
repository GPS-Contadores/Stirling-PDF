import { Rectangle } from "@app/utils/cropCoordinates";

/**
 * Where the visible certificate signature goes, as fractions (0-1) of the page
 * as the reader sees it (inside the CropBox, after /Rotate), with the origin at
 * the top-left corner. This is what /api/v1/security/cert-sign expects in
 * signatureX/Y/Width/Height; the backend converts it to PDF user space.
 */
export interface SignatureArea {
  x: number;
  y: number;
  width: number;
  height: number;
}

const clamp01 = (value: number) => Math.min(1, Math.max(0, value));

/**
 * Converts an area to the bottom-left-origin rectangle CropAreaSelector works
 * with, in units of the displayed page.
 */
export function signatureAreaToRectangle(
  area: SignatureArea,
  pageWidth: number,
  pageHeight: number,
): Rectangle {
  return {
    x: area.x * pageWidth,
    y: (1 - area.y - area.height) * pageHeight,
    width: area.width * pageWidth,
    height: area.height * pageHeight,
  };
}

/** Inverse of signatureAreaToRectangle, kept inside the page. */
export function rectangleToSignatureArea(
  rect: Rectangle,
  pageWidth: number,
  pageHeight: number,
): SignatureArea {
  const x = clamp01(rect.x / pageWidth);
  const y = clamp01(1 - (rect.y + rect.height) / pageHeight);
  return {
    x,
    y,
    width: Math.min(clamp01(rect.width / pageWidth), 1 - x),
    height: Math.min(clamp01(rect.height / pageHeight), 1 - y),
  };
}
