// Apply the persisted/OS theme before first paint to avoid a flash. Kept as an
// external same-origin script (not inline) so the Content-Security-Policy can be
// a strict `script-src 'self'` with no 'unsafe-inline'.
(function () {
  try {
    var stored = localStorage.getItem("cs-theme");
    var dark = stored ? stored === "dark" : window.matchMedia("(prefers-color-scheme: dark)").matches;
    document.documentElement.setAttribute("data-theme", dark ? "dark" : "light");
  } catch (e) {
    document.documentElement.setAttribute("data-theme", "dark");
  }
})();
