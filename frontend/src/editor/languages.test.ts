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

  it("stays fast on a huge unbroken token (minified / base64 paste)", () => {
    const t0 = performance.now();
    detectLanguage("x".repeat(70_000));
    expect(performance.now() - t0).toBeLessThan(1000);
  });
});

it("reads a plain Java class as Java, not TypeScript", () => {
  const java = `package demo;
import java.util.List;
public class Main {
  public static void main(String[] args) {
    List<String> xs = List.of("a", "b");
    for (String x : xs) { System.out.println(x); }
  }
}
`;
  expect(detectLanguage(java).id).toBe("java");
});

it("detects on the code lines when a document is mostly comments", () => {
  const chatty = Array.from({ length: 30 }, (_, i) => `# chat line ${i} about nothing in particular`).join("\n");
  const java = `${chatty}
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

List<String> lines = Files.readAllLines(Paths.get("file.txt"));
`;
  expect(detectLanguage(java).id).toBe("java");
});

it("still reads a mostly-comment script from the whole text", () => {
  const py = `# This script does one thing.
# It is documented far more than it is written.
# Run it with python3.
# It prints a greeting.
print("hello")
`;
  expect(detectLanguage(py).id).toBe("python");
});

it("keeps preprocessor lines as code when stripping comments", () => {
  const c = `// utilities
// more notes
#include <stdio.h>
#include <stdlib.h>
int main(void) { printf("hi\\n"); return 0; }
`;
  expect(["c", "cpp"]).toContain(detectLanguage(c).id);
});

it("treats chat prose as plain text instead of naming a random language", () => {
  const prose = `is the theme light on your screen? nope, dark here
this means personalisation works!! indeed it does
find me a new bug, you are the man of taste! please test
yeh bta jhn pe c sharp auto likha h neeche why the options are so transparent
`;
  expect(detectLanguage(prose).id).toBe("plaintext");
});

it("still detects short real code", () => {
  expect(detectLanguage('print("hello")\n').id).toBe("python");
  expect(detectLanguage("SELECT id, name FROM users WHERE active = 1;\n").id).toBe("sql");
});
