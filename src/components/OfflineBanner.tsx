import { useOnline } from "@/lib/online";
import { WifiOff } from "lucide-react";
export function OfflineBanner() {
  const online = useOnline();
  if (online) return null;
  return (
    <div className="fixed left-1/2 top-3 z-[100] -translate-x-1/2 rounded-full border border-destructive/40 bg-destructive/15 px-4 py-1.5 text-xs text-destructive shadow-lg backdrop-blur">
      <span className="inline-flex items-center gap-1.5"><WifiOff className="h-3 w-3" /> You're offline — we'll reconnect automatically.</span>
    </div>
  );
}
