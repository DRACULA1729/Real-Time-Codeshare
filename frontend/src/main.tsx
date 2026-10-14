import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

// Self-hosted fonts + icons — bundled at build time, zero network at runtime.
import "@fontsource/geist-sans/400.css";
import "@fontsource/geist-sans/600.css";
import "@fontsource/geist-sans/700.css";
import "@fontsource/jetbrains-mono/400.css";
import "@fontsource/jetbrains-mono/600.css";
import "material-symbols/outlined.css";

import "./styles/theme.css";
import "./index.css";
import { ThemeProvider } from "./theme/ThemeProvider";
import { App } from "./App";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ThemeProvider>
      <App />
    </ThemeProvider>
  </StrictMode>,
);
