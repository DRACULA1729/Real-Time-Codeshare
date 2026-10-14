import { describe, expect, it } from "vitest";
import { MAX_BASENAME_LEN, displayFileName, sanitizeBaseName } from "./fileName";

// Same case matrix as the backend's FileNameSanitizeTest — the two sanitizers
// must stay identical so the server's rename echo never visibly "corrects" the
// optimistic local value.
describe("sanitizeBaseName", () => {
  it("maps blank input to empty", () => {
    expect(sanitizeBaseName("")).toBe("");
    expect(sanitizeBaseName("   ")).toBe("");
  });

  it("passes a plain name through", () => {
    expect(sanitizeBaseName("MyClass")).toBe("MyClass");
  });

  it("cuts everything from the first dot", () => {
    expect(sanitizeBaseName("my.file.java")).toBe("my");
    expect(sanitizeBaseName("../evil")).toBe("");
    expect(sanitizeBaseName(".hidden")).toBe("");
  });

  it("strips path separators and control chars", () => {
    expect(sanitizeBaseName("a/b\\c")).toBe("abc");
    expect(sanitizeBaseName("a\u0001bcd\ne")).toBe("abcde");
  });

  it("trims surrounding whitespace after stripping", () => {
    expect(sanitizeBaseName("  my file  ")).toBe("my file");
    expect(sanitizeBaseName("  my.file.java  ")).toBe("my");
  });

  it("caps length at 64", () => {
    expect(sanitizeBaseName("x".repeat(65))).toHaveLength(MAX_BASENAME_LEN);
  });
});

describe("displayFileName", () => {
  it("falls back to untitled when the base is empty or blank", () => {
    expect(displayFileName("", "txt")).toBe("untitled.txt");
    expect(displayFileName("  ", "py")).toBe("untitled.py");
  });

  it("joins base and detected extension", () => {
    expect(displayFileName("MyClass", "java")).toBe("MyClass.java");
  });
});
