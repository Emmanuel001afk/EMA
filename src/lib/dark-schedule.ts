import { useEffect } from "react";
import { useTheme } from "@/lib/theme";

// Watches a HH:MM start/end and applies dark theme between start and end (overnight supported).
export function useDarkSchedule(enabled: boolean, start: string, end: string) {
  const { setTheme } = useTheme();
  useEffect(() => {
    if (!enabled) return;
    const tick = () => {
      const now = new Date();
      const mins = now.getHours() * 60 + now.getMinutes();
      const [sh, sm] = start.split(":").map(Number);
      const [eh, em] = end.split(":").map(Number);
      const s = sh * 60 + sm, e = eh * 60 + em;
      const isDark = s < e ? mins >= s && mins < e : mins >= s || mins < e;
      setTheme(isDark ? "dark" : "light");
    };
    tick();
    const id = setInterval(tick, 60_000);
    return () => clearInterval(id);
  }, [enabled, start, end, setTheme]);
}
