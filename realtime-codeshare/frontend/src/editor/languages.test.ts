import { describe, expect, it } from "vitest";
import { detectLanguage } from "./languages";

const TANTU = {
  shapes: `enum Shape:
    Circle(Float)
    Rect(Float, Float)

fn area(s: Shape) -> Float:
    match s:
        Circle(r)  => 3.14159 * r * r
        Rect(w, h) => w * h

fn main() -> Unit:
    print(area(Circle(2.0)))
    print(area(Rect(3.0, 4.0)))
`,
  greet: `fn greet(name: Str) -> Str:
    return "Hello, " + name + "!"

fn main() -> Unit:
    print(greet("Tantu"))
    let nums = [1, 2, 3, 4, 5]
    var total = 0
    for n in nums:
        total = total + n * n
    print(total)
`,
  worker: `fn worker(jobs: Chan[Int], out: Chan[Int]) -> Unit:
    loop:
        match recv(jobs):
            Some(n) => send(out, n * n)
            None    => break
`,
};

// Existing languages must keep detecting as before Tantu was registered.
const OTHERS: [string, string][] = [
  ["javascript", `import { useState } from "react";\nexport function counter() {\n  const [n, setN] = useState(0);\n  return n + 1;\n}\nconsole.log(counter());`],
  ["python", `def area(r: float) -> float:\n    return 3.14159 * r * r\n\nif __name__ == "__main__":\n    print(area(2.0))`],
  // A realistic file: a bare hello-world class already scores as TypeScript without Tantu.
  ["java", `package com.example;\n\nimport java.util.ArrayList;\nimport java.util.List;\n\npublic class Main {\n    private final List<String> names = new ArrayList<>();\n\n    @Override\n    public String toString() {\n        return String.join(", ", names);\n    }\n\n    public static void main(String[] args) {\n        Main m = new Main();\n        System.out.println(m);\n    }\n}`],
  ["sql", `SELECT id, name FROM users WHERE created_at > NOW() - INTERVAL '1 day' ORDER BY name;`],
  ["css", `.btn:hover {\n  color: #fff;\n  background: rgb(0, 0, 0);\n  padding: 4px 8px;\n}`],
];

describe("detectLanguage", () => {
  it.each(Object.entries(TANTU))("detects Tantu (%s)", (_name, code) => {
    expect(detectLanguage(code).id).toBe("tantu");
  });

  it.each(OTHERS)("still detects %s", (id, code) => {
    expect(detectLanguage(code).id).toBe(id);
  });
});
