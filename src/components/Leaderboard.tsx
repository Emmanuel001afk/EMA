import { useEffect, useState } from "react";
import { X, Trophy } from "lucide-react";
import { supabase } from "@/integrations/supabase/client";

type Row = { display_name: string; messages: number; referrals: number };
export function LeaderboardModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [rows, setRows] = useState<Row[]>([]);
  useEffect(() => {
    if (!open) return;
    (async () => {
      const { data } = await supabase.rpc("get_leaderboard");
      setRows((data ?? []) as Row[]);
    })();
  }, [open]);
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-[70] grid place-items-center bg-black/60 p-3">
      <div className="w-full max-w-md rounded-2xl border border-border bg-card p-5 shadow-2xl">
        <div className="mb-3 flex items-center justify-between">
          <h3 className="inline-flex items-center gap-2 text-sm font-semibold"><Trophy className="h-4 w-4 text-primary" /> Leaderboard</h3>
          <button onClick={onClose} className="rounded p-1 hover:bg-accent"><X className="h-4 w-4" /></button>
        </div>
        <ol className="space-y-1">
          {rows.length === 0 && <p className="py-4 text-center text-xs text-muted-foreground">No data yet.</p>}
          {rows.map((r, i) => (
            <li key={i} className={`flex items-center justify-between rounded-md px-3 py-2 text-sm ${i < 3 ? "bg-primary/10" : "bg-background"}`}>
              <span><span className="mr-2 font-mono text-xs opacity-60">#{i+1}</span>{r.display_name || "Anonymous"}</span>
              <span className="text-xs text-muted-foreground">{r.messages} msgs · {r.referrals} refs</span>
            </li>
          ))}
        </ol>
      </div>
    </div>
  );
}
