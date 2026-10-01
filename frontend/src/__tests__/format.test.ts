import { describe, expect, it } from "vitest";
import { compactCount, plural, relativeTime } from "@/lib/format";

const NOW = Date.UTC(2026, 8, 24, 12, 0, 0);

describe("relativeTime", () => {
  it.each([
    [NOW - 10_000, "now"],
    [NOW - 5 * 60_000, "5m"],
    [NOW - 3 * 3_600_000, "3h"],
    [NOW - 2 * 86_400_000, "2d"],
  ])("formats %s as %s", (ts, expected) => {
    expect(relativeTime(ts, NOW)).toBe(expected);
  });

  it("uses a short date after a week, adding the year only for other years", () => {
    expect(relativeTime(Date.UTC(2026, 2, 4, 12), NOW)).toBe("Mar 4");
    expect(relativeTime(Date.UTC(2024, 2, 4, 12), NOW)).toBe("Mar 4, 2024");
  });

  it("treats future timestamps (clock skew) as now", () => {
    expect(relativeTime(NOW + 60_000, NOW)).toBe("now");
  });
});

describe("compactCount", () => {
  it.each([
    [0, "0"],
    [9_999, "9,999"],
    [12_300, "12.3K"],
    [10_000, "10K"],
    [250_000, "250K"],
    [4_500_000, "4.5M"],
    [2_000_000, "2M"],
  ])("%d -> %s", (n, expected) => expect(compactCount(n)).toBe(expected));
});

describe("plural", () => {
  it("picks singular only for exactly one", () => {
    expect(plural(1, "post")).toBe("post");
    expect(plural(0, "post")).toBe("posts");
    expect(plural(2, "person", "people")).toBe("people");
  });
});
