import { describe, expect, test } from "vitest";
import {
  areaFromCorners,
  displayedAreaToPageArea,
  moveArea,
  pageAreaToDisplayedArea,
  rotatePoint,
  unrotatePoint,
} from "@app/utils/signatureAreaCoordinates";

const expectArea = (
  actual: { x: number; y: number; width: number; height: number },
  expected: { x: number; y: number; width: number; height: number },
) => {
  expect(actual.x).toBeCloseTo(expected.x);
  expect(actual.y).toBeCloseTo(expected.y);
  expect(actual.width).toBeCloseTo(expected.width);
  expect(actual.height).toBeCloseTo(expected.height);
};

// A box near the top-left corner of the unrotated page.
const AREA = { x: 0.1, y: 0.2, width: 0.3, height: 0.1 };

describe("signature area coordinates", () => {
  test("a page without /Rotate keeps the area", () => {
    expectArea(pageAreaToDisplayedArea(AREA, 0), AREA);
  });

  test("/Rotate 90 turns the top-left corner into the top-right", () => {
    // Same matrix the viewer applies: (x, y) -> (h - y, x).
    expectArea(pageAreaToDisplayedArea(AREA, 1), {
      x: 1 - 0.3,
      y: 0.1,
      width: 0.1,
      height: 0.3,
    });
  });

  test("/Rotate 180 mirrors both axes", () => {
    expectArea(pageAreaToDisplayedArea(AREA, 2), {
      x: 1 - 0.4,
      y: 1 - 0.3,
      width: 0.3,
      height: 0.1,
    });
  });

  test("/Rotate 270 turns the top-left corner into the bottom-left", () => {
    expectArea(pageAreaToDisplayedArea(AREA, 3), {
      x: 0.2,
      y: 1 - 0.4,
      width: 0.1,
      height: 0.3,
    });
  });

  test.each([0, 1, 2, 3])("round trip at %i quarter turns", (turns) => {
    expectArea(
      displayedAreaToPageArea(pageAreaToDisplayedArea(AREA, turns), turns),
      AREA,
    );
    const point = { x: 0.15, y: 0.7 };
    const back = unrotatePoint(rotatePoint(point, turns), turns);
    expect(back.x).toBeCloseTo(point.x);
    expect(back.y).toBeCloseTo(point.y);
  });

  test("corners dragged past the page are clamped inside it", () => {
    expectArea(areaFromCorners({ x: 0.9, y: -0.2 }, { x: 1.3, y: 0.1 }), {
      x: 0.9,
      y: 0,
      width: 0.1,
      height: 0.1,
    });
  });

  test("moving stops at the page edge", () => {
    expectArea(moveArea(AREA, 0.8, -0.5), {
      x: 0.7,
      y: 0,
      width: 0.3,
      height: 0.1,
    });
  });
});
