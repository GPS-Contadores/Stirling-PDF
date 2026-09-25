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

/** Fractions (0-1) of a page with a top-left origin. */
export interface PagePoint {
  x: number;
  y: number;
}

const clamp01 = (value: number) => Math.min(1, Math.max(0, value));
const normalizeTurns = (turns: number) => ((turns % 4) + 4) % 4;

/**
 * Where a point of an unrotated page ends up once the page is turned
 * clockwise by `turns` quarter turns (EmbedPDF's 0-3 rotation).
 */
export function rotatePoint(point: PagePoint, turns: number): PagePoint {
  switch (normalizeTurns(turns)) {
    case 1:
      return { x: 1 - point.y, y: point.x };
    case 2:
      return { x: 1 - point.x, y: 1 - point.y };
    case 3:
      return { x: point.y, y: 1 - point.x };
    default:
      return { x: point.x, y: point.y };
  }
}

/** Inverse of rotatePoint. */
export function unrotatePoint(point: PagePoint, turns: number): PagePoint {
  return rotatePoint(point, 4 - normalizeTurns(turns));
}

function rotateArea(area: SignatureArea, turns: number): SignatureArea {
  const a = rotatePoint({ x: area.x, y: area.y }, turns);
  const b = rotatePoint(
    { x: area.x + area.width, y: area.y + area.height },
    turns,
  );
  return {
    x: Math.min(a.x, b.x),
    y: Math.min(a.y, b.y),
    width: Math.abs(a.x - b.x),
    height: Math.abs(a.y - b.y),
  };
}

/**
 * The viewer lays each page out unrotated and turns it with CSS, so what the
 * user draws comes in fractions of the unrotated page. The API wants the page
 * as displayed, after its /Rotate.
 */
export function pageAreaToDisplayedArea(
  area: SignatureArea,
  pageRotation: number,
): SignatureArea {
  return rotateArea(area, pageRotation);
}

/** Inverse of pageAreaToDisplayedArea. */
export function displayedAreaToPageArea(
  area: SignatureArea,
  pageRotation: number,
): SignatureArea {
  return rotateArea(area, 4 - normalizeTurns(pageRotation));
}

/** The rectangle spanned by two corners, kept inside the page. */
export function areaFromCorners(a: PagePoint, b: PagePoint): SignatureArea {
  const x1 = clamp01(Math.min(a.x, b.x));
  const y1 = clamp01(Math.min(a.y, b.y));
  const x2 = clamp01(Math.max(a.x, b.x));
  const y2 = clamp01(Math.max(a.y, b.y));
  return { x: x1, y: y1, width: x2 - x1, height: y2 - y1 };
}

/** Moves an area by a delta without letting it leave the page. */
export function moveArea(
  area: SignatureArea,
  dx: number,
  dy: number,
): SignatureArea {
  return {
    ...area,
    x: Math.min(Math.max(0, area.x + dx), 1 - area.width),
    y: Math.min(Math.max(0, area.y + dy), 1 - area.height),
  };
}
