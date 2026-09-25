import { describe, expect, test } from "vitest";
import {
  rectangleToSignatureArea,
  signatureAreaToRectangle,
} from "@app/utils/signatureAreaCoordinates";

// A landscape page as displayed: 842 wide, 595 tall.
const WIDTH = 842;
const HEIGHT = 595;

describe("signature area coordinates", () => {
  test("top-left fractions become a bottom-left rectangle", () => {
    const rect = signatureAreaToRectangle(
      { x: 0.5, y: 0.1, width: 0.25, height: 0.2 },
      WIDTH,
      HEIGHT,
    );

    expect(rect.x).toBeCloseTo(421);
    expect(rect.y).toBeCloseTo(0.7 * HEIGHT);
    expect(rect.width).toBeCloseTo(210.5);
    expect(rect.height).toBeCloseTo(119);
  });

  test("round trip keeps the area", () => {
    const area = { x: 0.12, y: 0.8, width: 0.3, height: 0.15 };

    const back = rectangleToSignatureArea(
      signatureAreaToRectangle(area, WIDTH, HEIGHT),
      WIDTH,
      HEIGHT,
    );

    expect(back.x).toBeCloseTo(area.x);
    expect(back.y).toBeCloseTo(area.y);
    expect(back.width).toBeCloseTo(area.width);
    expect(back.height).toBeCloseTo(area.height);
  });

  test("a rectangle spilling past the page is clamped inside it", () => {
    const area = rectangleToSignatureArea(
      { x: WIDTH - 50, y: -10, width: 100, height: 60 },
      WIDTH,
      HEIGHT,
    );

    expect(area.x + area.width).toBeLessThanOrEqual(1);
    expect(area.y + area.height).toBeLessThanOrEqual(1);
    expect(area.y).toBeGreaterThanOrEqual(0);
  });
});
