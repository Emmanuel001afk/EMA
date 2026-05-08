import { useEffect, useState } from "react";
import { X, Calendar, Plus, Trash2, Check } from "lucide-react";
import { supabase } from "@/integrations/supabase/client";

type A = { id: string; subject: string; title: string; due_date: string; done: boolean };

export function AssignmentTrackerModal({ open, onClose, userId }: { open: boolean; onClose: () => void; userId: string | null }) {
  const [items, setItems] = useState<A[]>([]);
  const [subject, setSubject] = useState("");
  const [title, setTitle] = useState("");
  const [due, setDue] = useState("");

  const load = async () => {
    if (!userId) return;
    const { data } = await supabase.from("assignments").select("*").order("due_date", { ascending: true });
    setItems((data ?? []) as A[]);
  };
  useEffect(() => { if (open) load(); /* eslint-disable-next-line */ }, [open, userId]);

  const add = async () => {
    if (!userId || !subject.trim() || !title.trim() || !due) return;
    await supabase.from("assignments").insert({ user_id: userId, subject, title, due_date: new Date(due).toISOString() });
    setSubject(""); setTitle(""); setDue(""); load();
  };
  const remove = async (id: string) => { await supabase.from("assignments").delete().eq("id", id); load(); };
  const toggle = async (a: A) => { await supabase.from("assignments").update({ done: !a.done }).eq("id", a.id); load(); };

  if (!open) return null;
  return (
    <div className="fixed inset-0 z-[70] grid place-items-center bg-black/60 p-3">
      <div className="w-full max-w-md rounded-2xl border border-border bg-card p-5 shadow-2xl">
        <div className="mb-3 flex items-center justify-between">
          <h3 className="inline-flex items-center gap-2 text-sm font-semibold"><Calendar className="h-4 w-4" /> Assignments</h3>
          <button onClick={onClose} className="rounded p-1 hover:bg-accent"><X className="h-4 w-4" /></button>
        </div>

        <div className="space-y-2">
          <input value={subject} onChange={(e) => setSubject(e.target.value)} placeholder="Subject (e.g. CSC 301)" className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm" />
          <input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Assignment title" className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm" />
          <input type="datetime-local" value={due} onChange={(e) => setDue(e.target.value)} className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm" />
          <button onClick={add} className="inline-flex w-full items-center justify-center gap-1.5 rounded-md bg-primary py-2 text-sm font-semibold text-primary-foreground"><Plus className="h-4 w-4" /> Add</button>
        </div>

        <div className="mt-4 max-h-72 space-y-1.5 overflow-y-auto">
          {items.length === 0 && <p className="py-4 text-center text-xs text-muted-foreground">Nothing scheduled.</p>}
          {items.map((a) => {
            const overdue = !a.done && new Date(a.due_date) < new Date();
            return (
              <div key={a.id} className={`rounded-md border p-2 text-xs ${a.done ? "opacity-50" : overdue ? "border-destructive/40 bg-destructive/5" : "border-border bg-background"}`}>
                <div className="flex items-center justify-between">
                  <div className="min-w-0">
                    <div className="font-semibold truncate">{a.subject}</div>
                    <div className="truncate text-muted-foreground">{a.title}</div>
                    <div className="text-[10px] opacity-70">due {new Date(a.due_date).toLocaleString()}</div>
                  </div>
                  <div className="flex shrink-0 gap-1">
                    <button onClick={() => toggle(a)} className="rounded p-1 hover:bg-accent" title="Toggle done"><Check className="h-3.5 w-3.5" /></button>
                    <button onClick={() => remove(a.id)} className="rounded p-1 text-destructive hover:bg-destructive/10"><Trash2 className="h-3.5 w-3.5" /></button>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
