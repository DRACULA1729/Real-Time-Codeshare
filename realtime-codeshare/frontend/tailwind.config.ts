import type { Config } from "tailwindcss";

// Synchronous Mono tokens. Colors resolve to CSS custom properties (channel
// triples like "15 17 21") defined per-theme in src/styles/theme.css, so the
// same utility classes render light or dark depending on <html data-theme>.
const v = (name: string) => `rgb(var(--${name}) / <alpha-value>)`;

export default {
  darkMode: ["selector", '[data-theme="dark"]'],
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        "surface-page": v("surface-page"),
        "surface-raised": v("surface-raised"),
        "surface-container": v("surface-container"),
        "surface-container-lowest": v("surface-container-lowest"),
        "surface-container-low": v("surface-container-low"),
        "surface-container-high": v("surface-container-high"),
        "surface-container-highest": v("surface-container-highest"),
        "surface-variant": v("surface-variant"),
        "border-subtle": v("border-subtle"),
        outline: v("outline"),
        "outline-variant": v("outline-variant"),
        "text-primary": v("text-primary"),
        "text-muted": v("text-muted"),
        "on-surface": v("on-surface"),
        "on-surface-variant": v("on-surface-variant"),
        primary: v("primary"),
        "on-primary": v("on-primary"),
        "primary-container": v("primary-container"),
        secondary: v("secondary"),
        "on-secondary": v("on-secondary"),
        "secondary-container": v("secondary-container"),
        tertiary: v("tertiary"),
        success: v("success"),
        danger: v("danger"),
        error: v("error"),
        "editor-bg": v("editor-bg"),
        "input-bg": v("input-bg"),
        "participant-1": v("participant-1"),
        "participant-2": v("participant-2"),
        "participant-3": v("participant-3"),
        "participant-4": v("participant-4"),
      },
      fontFamily: {
        sans: ['"Geist Sans"', "system-ui", "sans-serif"],
        mono: ['"JetBrains Mono"', "ui-monospace", "monospace"],
      },
      fontSize: {
        "headline-lg": ["30px", { lineHeight: "36px", fontWeight: "600", letterSpacing: "-0.02em" }],
        "headline-md": ["24px", { lineHeight: "32px", fontWeight: "600", letterSpacing: "-0.01em" }],
        "body-lg": ["16px", { lineHeight: "24px", fontWeight: "400" }],
        "body-md": ["14px", { lineHeight: "20px", fontWeight: "400" }],
        "code-lg": ["16px", { lineHeight: "26px", fontWeight: "400" }],
        "code-md": ["14px", { lineHeight: "22px", fontWeight: "400" }],
        "label-caps": ["11px", { lineHeight: "16px", fontWeight: "600", letterSpacing: "0.05em" }],
      },
      borderRadius: {
        sm: "0.25rem",
        DEFAULT: "0.5rem",
        md: "0.75rem",
        lg: "1rem",
        xl: "1.5rem",
        full: "9999px",
      },
      spacing: {
        gutter: "16px",
        "margin-mobile": "16px",
        "margin-desktop": "24px",
        "editor-padding": "32px",
      },
      maxWidth: {
        "container-max": "1440px",
      },
      transitionTimingFunction: {
        "ease-out-snappy": "cubic-bezier(0, 0, 0.2, 1)",
      },
      keyframes: {
        "fade-in": { from: { opacity: "0" }, to: { opacity: "1" } },
        "zoom-in": { from: { opacity: "0", transform: "scale(0.97)" }, to: { opacity: "1", transform: "scale(1)" } },
        "slide-in-left": { from: { opacity: "0", transform: "translateX(-8px)" }, to: { opacity: "1", transform: "translateX(0)" } },
        "spin-ring": { to: { transform: "rotate(360deg)" } },
        "pulse-soft": { "0%,100%": { opacity: "1" }, "50%": { opacity: "0.6" } },
      },
      animation: {
        "fade-in": "fade-in 150ms ease-out",
        "zoom-in": "zoom-in 150ms ease-out",
        "slide-in-left": "slide-in-left 200ms ease-out",
        "pulse-soft": "pulse-soft 2s ease-in-out infinite",
      },
    },
  },
  plugins: [],
} satisfies Config;
