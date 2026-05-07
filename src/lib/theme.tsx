import { createContext, useContext, useEffect, useState } from "react";

type Theme = "dark" | "light";
const ThemeCtx = createContext<{ theme: Theme; setTheme: (t: Theme) => void }>({
  theme: "dark", setTheme: () => {},
});

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const [theme, setThemeState] = useState<Theme>("dark");
  useEffect(() => {
    const saved = (typeof localStorage !== "undefined" && localStorage.getItem("hi-there-theme")) as Theme | null;
    if (saved === "light" || saved === "dark") setThemeState(saved);
  }, []);
  useEffect(() => {
    if (typeof document === "undefined") return;
    document.documentElement.classList.toggle("dark", theme === "dark");
    try { localStorage.setItem("hi-there-theme", theme); } catch {}
  }, [theme]);
  return <ThemeCtx.Provider value={{ theme, setTheme: setThemeState }}>{children}</ThemeCtx.Provider>;
}
export const useTheme = () => useContext(ThemeCtx);