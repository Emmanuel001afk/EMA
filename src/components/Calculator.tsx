import { useState } from "react";
import { X, Calculator as CalcIcon } from "lucide-react";

export function CalculatorModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [expr, setExpr] = useState("");
  const [result, setResult] = useState("");
  if (!open) return null;
  const safeEval = (e: string) => {
    // Allow digits, operators, parens, decimal, percent and Math.* functions
    const cleaned = e.replace(/[^0-9+\-*/().,%\s]/g, "").replace(/,/g, ".");
    if (!cleaned) return "";
    try {
      // eslint-disable-next-line no-new-func
      const v = Function(`"use strict"; return (${cleaned.replace(/%/g, "/100")});`)();
      return String(v);
    } catch { return "Error"; }
  };
  const press = (k: string) => {
    if (k === "=") { setResult(safeEval(expr)); return; }
    if (k === "C") { setExpr(""); setResult(""); return; }
    if (k === "←") { setExpr(expr.slice(0, -1)); return; }
    setExpr(expr + k);
  };
  const keys = ["7","8","9","/","4","5","6","*","1","2","3","-","0",".","%","+"];
  return (
    <div className="fixed inset-0 z-[70] grid place-items-center bg-black/60 p-3">
      <div className="w-full max-w-xs rounded-2xl border border-border bg-card p-4 shadow-2xl">
        <div className="mb-2 flex items-center justify-between">
          <h3 className="inline-flex items-center gap-2 text-sm font-semibold"><CalcIcon className="h-4 w-4" /> Calculator</h3>
          <button onClick={onClose} className="rounded p-1 hover:bg-accent"><X className="h-4 w-4" /></button>
        </div>
        <input value={expr} onChange={(e) => setExpr(e.target.value)} placeholder="0"
          className="w-full rounded-md border border-input bg-background px-3 py-2 text-right text-lg font-mono" />
        <div className="mt-1 text-right text-sm text-muted-foreground">{result || " "}</div>
        <div className="mt-3 grid grid-cols-4 gap-2">
          {keys.map((k) => (
            <button key={k} onClick={() => press(k)} className="rounded-md border border-border bg-background py-2 font-mono hover:bg-accent">{k}</button>
          ))}
          <button onClick={() => press("←")} className="rounded-md border border-border bg-background py-2 font-mono hover:bg-accent">←</button>
          <button onClick={() => press("C")} className="rounded-md border border-border bg-destructive/15 py-2 font-mono text-destructive hover:bg-destructive/25">C</button>
          <button onClick={() => press("=")} className="col-span-2 rounded-md bg-primary py-2 font-mono text-primary-foreground">=</button>
        </div>
      </div>
    </div>
  );
}
