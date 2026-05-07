import { useEffect, useState } from "react";
import { Bell, X } from "lucide-react";
import { supabase } from "@/integrations/supabase/client";

type Notif = { id: string; title: string; body: string; read: boolean; created_at: string; kind: string };

export function NotificationBell({ userId }: { userId: string }) {
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<Notif[]>([]);
  const unread = items.filter((i) => !i.read).length;

  const load = async () => {
    const { data } = await supabase.from("notifications").select("*")
      .eq("user_id", userId).order("created_at", { ascending: false }).limit(30);
    setItems((data ?? []) as Notif[]);
  };

  useEffect(() => {
    load();
    const ch = supabase.channel("notif-" + userId)
      .on("postgres_changes",
        { event: "INSERT", schema: "public", table: "notifications", filter: `user_id=eq.${userId}` },
        () => load(),
      ).subscribe();
    return () => { supabase.removeChannel(ch); };
    // eslint-disable-next-line
  }, [userId]);

  const markAll = async () => {
    await supabase.from("notifications").update({ read: true }).eq("user_id", userId).eq("read", false);
    load();
  };

  return (
    <div className="relative">
      <button onClick={() => setOpen((v) => !v)}
        className="relative inline-flex h-8 w-8 items-center justify-center rounded-md border border-border bg-card hover:bg-accent">
        <Bell className="h-4 w-4" />
        {unread > 0 && <span className="absolute -right-1 -top-1 grid h-4 min-w-4 place-items-center rounded-full bg-primary px-1 text-[10px] font-bold text-primary-foreground">{unread}</span>}
      </button>
      {open && (
        <div className="absolute right-0 top-10 z-50 w-80 max-w-[calc(100vw-2rem)] rounded-xl border border-border bg-popover p-2 shadow-2xl">
          <div className="flex items-center justify-between px-2 py-1.5">
            <h3 className="text-sm font-semibold">Notifications</h3>
            <div className="flex items-center gap-1">
              {unread > 0 && <button onClick={markAll} className="text-xs text-primary hover:underline">Mark read</button>}
              <button onClick={() => setOpen(false)} className="rounded p-1 hover:bg-accent"><X className="h-3 w-3" /></button>
            </div>
          </div>
          <div className="max-h-80 overflow-y-auto">
            {items.length === 0 && <p className="px-2 py-6 text-center text-xs text-muted-foreground">No notifications yet.</p>}
            {items.map((n) => (
              <div key={n.id} className={`rounded-md px-2 py-2 text-xs ${n.read ? "" : "bg-primary/5"}`}>
                <div className="flex items-center justify-between">
                  <p className="font-semibold">{n.title}</p>
                  <span className="text-[10px] text-muted-foreground">{new Date(n.created_at).toLocaleDateString()}</span>
                </div>
                <p className="mt-0.5 whitespace-pre-wrap text-muted-foreground">{n.body}</p>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}