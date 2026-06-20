import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { supabase } from "@/integrations/supabase/client";
import { ArrowLeft, MessageSquare, Calendar, Search } from "lucide-react";

export const Route = createFileRoute("/dashboard")({ component: Dashboard });

type ConvRow = {
  id: string;
  title: string;
  updated_at: string;
  created_at: string;
  last?: string;
};

function Dashboard() {
  const navigate = useNavigate();
  const [rows, setRows] = useState<ConvRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [q, setQ] = useState("");

  useEffect(() => {
    (async () => {
      const { data: s } = await supabase.auth.getSession();
      if (!s.session) {
        navigate({ to: "/auth" });
        return;
      }
      const { data: convs } = await supabase
        .from("conversations")
        .select("id,title,updated_at,created_at")
        .order("updated_at", { ascending: false })
        .limit(200);
      if (!convs) {
        setLoading(false);
        return;
      }
      // Fetch last message snippet per conversation
      const withSnippets: ConvRow[] = await Promise.all(
        convs.map(async (c) => {
          const { data: m } = await supabase
            .from("messages")
            .select("content")
            .eq("conversation_id", c.id)
            .order("created_at", { ascending: false })
            .limit(1)
            .maybeSingle();
          return { ...c, last: m?.content?.slice(0, 140) };
        }),
      );
      setRows(withSnippets);
      setLoading(false);
    })();
  }, [navigate]);

  const filtered = q.trim()
    ? rows.filter(
        (r) =>
          r.title.toLowerCase().includes(q.toLowerCase()) ||
          (r.last || "").toLowerCase().includes(q.toLowerCase()),
      )
    : rows;

  const open = (id: string) => {
    try {
      localStorage.setItem("hi-there-open-conv", id);
    } catch {
      /* ignore */
    }
    navigate({ to: "/chat" });
  };

  const fmt = (s: string) => {
    const d = new Date(s);
    const days = Math.floor((Date.now() - d.getTime()) / 86_400_000);
    if (days === 0) return "Today";
    if (days === 1) return "Yesterday";
    if (days < 7) return `${days} days ago`;
    return d.toLocaleDateString();
  };

  return (
    <div className="min-h-screen bg-background">
      <header className="sticky top-0 z-10 border-b border-border bg-background/80 px-4 py-3 backdrop-blur">
        <div className="mx-auto flex max-w-4xl items-center gap-3">
          <Link
            to="/chat"
            className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
          >
            <ArrowLeft className="h-4 w-4" /> Back
          </Link>
          <h1 className="text-xl font-bold">Projects & Conversations</h1>
        </div>
      </header>
      <main className="mx-auto max-w-4xl px-4 py-6">
        <div className="relative mb-4">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Search by title or last message…"
            className="w-full rounded-xl border border-input bg-card py-2.5 pl-10 pr-3 text-sm outline-none focus:ring-2 focus:ring-ring"
          />
        </div>
        {loading && <p className="text-sm text-muted-foreground">Loading…</p>}
        {!loading && filtered.length === 0 && (
          <p className="rounded-xl border border-dashed border-border p-6 text-center text-sm text-muted-foreground">
            No conversations yet. <Link to="/chat" className="text-primary underline">Start one</Link>.
          </p>
        )}
        <div className="grid gap-2">
          {filtered.map((c) => (
            <button
              key={c.id}
              onClick={() => open(c.id)}
              className="group rounded-xl border border-border bg-card p-4 text-left transition hover:border-primary/60 hover:bg-accent"
            >
              <div className="flex items-start justify-between gap-3">
                <div className="flex items-start gap-3 min-w-0">
                  <span className="grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-primary/10 text-primary">
                    <MessageSquare className="h-4 w-4" />
                  </span>
                  <div className="min-w-0">
                    <h3 className="truncate font-semibold">{c.title}</h3>
                    {c.last && (
                      <p className="mt-1 line-clamp-2 text-xs text-muted-foreground">
                        {c.last}
                      </p>
                    )}
                  </div>
                </div>
                <span className="inline-flex shrink-0 items-center gap-1 text-[11px] text-muted-foreground">
                  <Calendar className="h-3 w-3" />
                  {fmt(c.updated_at)}
                </span>
              </div>
            </button>
          ))}
        </div>
      </main>
    </div>
  );
}