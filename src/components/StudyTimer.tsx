import { useEffect, useRef, useState } from "react";
import { X, Timer, Play, Pause, RotateCcw } from "lucide-react";
import { supabase } from "@/integrations/supabase/client";

export function StudyTimerModal({ open, onClose, userId }: { open: boolean; onClose: () => void; userId: string | null }) {
  const [study, setStudy] = useState(25);
  const [brk, setBrk] = useState(5);
  const [phase, setPhase] = useState<"study" | "break">("study");
  const [secs, setSecs] = useState(25 * 60);
  const [running, setRunning] = useState(false);
  const ref = useRef<number | null>(null);

  useEffect(() => { setSecs((phase === "study" ? study : brk) * 60); }, [study, brk, phase]);

  useEffect(() => {
    if (!running) return;
    ref.current = window.setInterval(() => {
      setSecs((s) => {
        if (s <= 1) {
          // phase complete
          if (phase === "study") {
            setPhase("break");
            getTipAndNotify(userId);
          } else {
            setPhase("study");
          }
          return (phase === "study" ? brk : study) * 60;
        }
        return s - 1;
      });
    }, 1000);
    return () => { if (ref.current) window.clearInterval(ref.current); };
  }, [running, phase, study, brk, userId]);

  const mm = String(Math.floor(secs / 60)).padStart(2, "0");
  const ss = String(secs % 60).padStart(2, "0");
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-[70] grid place-items-center bg-black/60 p-3">
      <div className="w-full max-w-sm rounded-2xl border border-border bg-card p-5 shadow-2xl">
        <div className="mb-3 flex items-center justify-between">
          <h3 className="inline-flex items-center gap-2 text-sm font-semibold"><Timer className="h-4 w-4" /> Study Timer</h3>
          <button onClick={onClose} className="rounded p-1 hover:bg-accent"><X className="h-4 w-4" /></button>
        </div>
        <p className="text-center text-xs uppercase text-muted-foreground">{phase === "study" ? "Focus" : "Break"}</p>
        <p className="text-center font-mono text-5xl font-bold">{mm}:{ss}</p>
        <div className="mt-4 grid grid-cols-2 gap-2 text-xs">
          <label>Study (min) <input type="number" min={1} max={120} value={study} onChange={(e) => setStudy(Number(e.target.value) || 25)} className="mt-1 w-full rounded-md border border-input bg-background px-2 py-1" /></label>
          <label>Break (min) <input type="number" min={1} max={60} value={brk} onChange={(e) => setBrk(Number(e.target.value) || 5)} className="mt-1 w-full rounded-md border border-input bg-background px-2 py-1" /></label>
        </div>
        <div className="mt-4 flex gap-2">
          <button onClick={() => setRunning((v) => !v)} className="flex-1 inline-flex items-center justify-center gap-1.5 rounded-md bg-primary py-2 text-sm font-semibold text-primary-foreground">
            {running ? <><Pause className="h-4 w-4" /> Pause</> : <><Play className="h-4 w-4" /> Start</>}
          </button>
          <button onClick={() => { setRunning(false); setPhase("study"); setSecs(study * 60); }} className="rounded-md border border-border bg-card px-3 py-2 text-sm">
            <RotateCcw className="h-4 w-4" />
          </button>
        </div>
      </div>
    </div>
  );
}

async function getTipAndNotify(userId: string | null) {
  if (!userId) return;
  const tips = [
    "Stand up and stretch — your brain loves blood flow! 💪",
    "Drink some water. Hydrated brain = sharp brain.",
    "Step outside for 60 seconds. Daylight resets focus.",
    "Quick win: review what you just learned in 30 seconds.",
    "Breathe deep — 4 in, 4 hold, 4 out. Reset.",
  ];
  const body = tips[Math.floor(Math.random() * tips.length)];
  await supabase.from("notifications").insert({
    user_id: userId, title: "Break time! ☕", body, kind: "system",
  });
}
