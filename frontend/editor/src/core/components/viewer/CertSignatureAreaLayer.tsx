import { memo, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useDocumentState } from "@embedpdf/core/react";
import { useInteractionManagerCapability } from "@embedpdf/plugin-interaction-manager/react";
import { useCertSignatureArea } from "@app/contexts/CertSignatureAreaContext";
import {
  PagePoint,
  SignatureArea,
  areaFromCorners,
  displayedAreaToPageArea,
  moveArea,
  pageAreaToDisplayedArea,
  unrotatePoint,
} from "@app/utils/signatureAreaCoordinates";
import {
  Z_INDEX_SIGNATURE_OVERLAY,
  Z_INDEX_SIGNATURE_OVERLAY_HANDLE,
} from "@app/styles/zIndex";

// Box a click (no drag) places: the backend's default 200x50 pt signature.
const DEFAULT_WIDTH_PT = 200;
const DEFAULT_HEIGHT_PT = 50;
// Below this a drag counts as a click; a resize this small is dropped.
const MIN_SIZE_PX = 12;

const HANDLES = [
  { corner: "nw", left: 0, top: 0 },
  { corner: "ne", left: 1, top: 0 },
  { corner: "sw", left: 0, top: 1 },
  { corner: "se", left: 1, top: 1 },
] as const;

type Corner = (typeof HANDLES)[number]["corner"];

interface CertSignatureAreaLayerProps {
  documentId: string;
  pageIndex: number;
  /** Rendered (unrotated) page size from the Scroller, in pixels. */
  pageWidth: number;
  pageHeight: number;
}

/**
 * Per-page layer where the certificate-signing tool's visible signature area
 * is drawn, moved and resized. The page div it lives in is laid out unrotated
 * and turned with CSS, so the box is kept in fractions of the unrotated page
 * and converted to the displayed page (what the API wants) on commit.
 */
export const CertSignatureAreaLayer = memo(function CertSignatureAreaLayer({
  documentId,
  pageIndex,
  pageWidth,
  pageHeight,
}: CertSignatureAreaLayerProps) {
  const { t } = useTranslation();
  const { placement } = useCertSignatureArea();
  const documentState = useDocumentState(documentId);
  const { provides: interactionManager } = useInteractionManagerCapability();
  const layerRef = useRef<HTMLDivElement>(null);
  // Box being drawn, moved or resized; committed on pointer up.
  const [draft, setDraft] = useState<SignatureArea | null>(null);

  const page = documentState?.document?.pages?.[pageIndex];
  const pageRotation = page?.rotation ?? 0;
  // What the user sees also includes the viewer's own rotation.
  const viewRotation = (pageRotation + (documentState?.rotation ?? 0)) % 4;

  const committed =
    placement?.area && placement.pageIndex === pageIndex
      ? displayedAreaToPageArea(placement.area, pageRotation)
      : null;
  const box = draft ?? committed;

  const latest = useRef({
    placement,
    committed,
    pageRotation,
    viewRotation,
    pageSize: page?.size,
    pageWidth,
    pageHeight,
    interactionManager,
  });
  latest.current = {
    placement,
    committed,
    pageRotation,
    viewRotation,
    pageSize: page?.size,
    pageWidth,
    pageHeight,
    interactionManager,
  };

  const active = !!placement && !placement.disabled;

  useEffect(() => {
    const layer = layerRef.current;
    if (!layer || !active) return;

    // Screen point -> fractions of the unrotated page. The layer's bounding
    // box is the page as turned on screen.
    const toPagePoint = (event: PointerEvent): PagePoint => {
      const rect = layer.getBoundingClientRect();
      return unrotatePoint(
        {
          x: (event.clientX - rect.left) / (rect.width || 1),
          y: (event.clientY - rect.top) / (rect.height || 1),
        },
        latest.current.viewRotation,
      );
    };

    const isTooSmall = (area: SignatureArea) =>
      area.width * latest.current.pageWidth < MIN_SIZE_PX ||
      area.height * latest.current.pageHeight < MIN_SIZE_PX;

    const defaultAreaAt = (center: PagePoint): SignatureArea | null => {
      const size = latest.current.pageSize;
      if (!size?.width || !size?.height) return null;
      // The signature is drawn upright on the displayed page, so on a page
      // turned a quarter the box is taller than wide in unrotated space.
      const sideways = latest.current.pageRotation % 2 === 1;
      const width =
        Math.min(sideways ? DEFAULT_HEIGHT_PT : DEFAULT_WIDTH_PT, size.width) /
        size.width;
      const height =
        Math.min(sideways ? DEFAULT_WIDTH_PT : DEFAULT_HEIGHT_PT, size.height) /
        size.height;
      return moveArea(
        { x: 0, y: 0, width, height },
        center.x - width / 2,
        center.y - height / 2,
      );
    };

    const handlePointerDown = (event: PointerEvent) => {
      if (event.button !== 0) return;
      // Native listener on the target itself, so the viewer's own pointer
      // handlers (text selection, pan) never see this gesture.
      event.preventDefault();
      event.stopPropagation();

      const start = toPagePoint(event);
      const target = event.target as HTMLElement;
      const corner = target.dataset.certSignHandle as Corner | undefined;
      const onBox = !!target.closest("[data-cert-sign-box]");
      const current = latest.current.committed;

      let update: (point: PagePoint) => SignatureArea;
      let finish: (area: SignatureArea) => SignatureArea | null;

      if (corner && current) {
        const fixed = {
          x: corner.includes("w") ? current.x + current.width : current.x,
          y: corner.includes("n") ? current.y + current.height : current.y,
        };
        update = (point) => areaFromCorners(fixed, point);
        finish = (area) => (isTooSmall(area) ? null : area);
      } else if (onBox && current) {
        update = (point) =>
          moveArea(current, point.x - start.x, point.y - start.y);
        finish = (area) => area;
      } else {
        update = (point) => areaFromCorners(start, point);
        finish = (area) => (isTooSmall(area) ? defaultAreaAt(start) : area);
      }

      let area = update(start);
      setDraft(area);
      latest.current.interactionManager?.pause();

      const handleMove = (moveEvent: PointerEvent) => {
        area = update(toPagePoint(moveEvent));
        setDraft(area);
      };
      const handleUp = () => {
        window.removeEventListener("pointermove", handleMove);
        window.removeEventListener("pointerup", handleUp);
        window.removeEventListener("pointercancel", handleUp);
        latest.current.interactionManager?.resume();
        window.getSelection()?.removeAllRanges();
        setDraft(null);
        const result = finish(area);
        if (result) {
          latest.current.placement?.onChange(
            pageIndex,
            pageAreaToDisplayedArea(result, latest.current.pageRotation),
          );
        }
      };

      window.addEventListener("pointermove", handleMove);
      window.addEventListener("pointerup", handleUp);
      window.addEventListener("pointercancel", handleUp);
    };

    layer.addEventListener("pointerdown", handlePointerDown);
    return () => layer.removeEventListener("pointerdown", handlePointerDown);
  }, [active, pageIndex]);

  if (!placement) return null;

  // Handle cursors follow the corner as seen on screen.
  const quarterTurned = viewRotation % 2 === 1;
  const handleCursor = (corner: Corner) =>
    (corner === "nw" || corner === "se") !== quarterTurned
      ? "nwse-resize"
      : "nesw-resize";

  return (
    <div
      ref={layerRef}
      data-testid="cert-sign-area-layer"
      style={{
        position: "absolute",
        inset: 0,
        zIndex: Z_INDEX_SIGNATURE_OVERLAY,
        cursor: active ? "crosshair" : "default",
        pointerEvents: active ? "auto" : "none",
        touchAction: "none",
      }}
    >
      {box && (
        <div
          data-cert-sign-box="true"
          style={{
            position: "absolute",
            left: `${box.x * 100}%`,
            top: `${box.y * 100}%`,
            width: `${box.width * 100}%`,
            height: `${box.height * 100}%`,
            border: "2px dashed var(--mantine-primary-color-filled)",
            backgroundColor: "var(--mantine-primary-color-light)",
            boxSizing: "border-box",
            cursor: active ? "move" : "default",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            overflow: "visible",
          }}
        >
          <span
            style={{
              // Upright on screen whatever the page rotation.
              transform: `rotate(${-viewRotation * 90}deg)`,
              whiteSpace: "nowrap",
              fontSize: "0.7rem",
              fontWeight: 600,
              color: "var(--mantine-primary-color-filled)",
              pointerEvents: "none",
            }}
          >
            {t("certSign.appearance.area.boxLabel", "Digital signature")}
          </span>
          {active &&
            !draft &&
            HANDLES.map((handle) => (
              <div
                key={handle.corner}
                data-cert-sign-handle={handle.corner}
                style={{
                  position: "absolute",
                  left: `calc(${handle.left * 100}% - 5px)`,
                  top: `calc(${handle.top * 100}% - 5px)`,
                  width: 10,
                  height: 10,
                  backgroundColor: "var(--mantine-primary-color-filled)",
                  border: "1px solid white",
                  cursor: handleCursor(handle.corner),
                  zIndex: Z_INDEX_SIGNATURE_OVERLAY_HANDLE,
                }}
              />
            ))}
        </div>
      )}
    </div>
  );
});
