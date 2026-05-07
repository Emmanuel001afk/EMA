import { createFileRoute, useNavigate, Link } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { supabase } from "@/integrations/supabase/client";
import { Sparkles, ArrowLeft, Check } from "lucide-react";
import { Footer } from "@/components/Footer";

export const Route = createFileRoute("/admin")({
  component: AdminPage,
});

const PROVIDERS = [
  { id: "gemini", name: "Google Gemini", desc: "Primary — fast & free." },
  { id: "groq", name: "Groq", desc: "Secondary — ultra-low latency." },
  { id: "mistral", name: "Mistral AI", desc: "Tertiary — strong reasoning." },
] as const;

function AdminPage() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(true);
  const [authorized, setAuthorized] = useState(false);
  const [active, setActive] = useState<string>("gemini");
  const [saving, setSaving] = useState<string | null>(null);
  const [savedAt, setSavedAt] = useState<number | null>(null);

  useEffect(() => {
    (async () => {
      const { data: s } = await supabase.auth.getSession();
      if (!s.session) {
        navigate({ to: "/auth" });
        return;
      }
      const { data: roles } = await supabase
        .from("user_roles")
        .select("role")
        .eq("user_id", s.session.user.id);
      const ok = !!roles?.some((r) => r.role === "admin");
      setAuthorized(ok);
      const { data: setting } = await supabase
        .from("app_settings")
        .select("value")
        .eq("key", "active_provider")
        .maybeSingle();
      if (setting?.value) setActive(setting.value as string);
      setLoading(false);
    })();
  }, [navigate]);

  const switchTo = async (id: string) => {
    setSaving(id);
    const { error } = await supabase
      .from("app_settings")
      .update({ value: id as any })
      .eq("key", "active_provider");
    setSaving(null);
    if (!error) {
      setActive(id);
      setSavedAt(Date.now());
    }
  };

  if (loading) {
    return (
      <div className="grid min-h-screen place-items-center bg-background text-muted-foreground">
        Loading…
      </div>
    );
  }

  if (!authorized) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center bg-background px-4 text-center">
        <h1 className="text-2xl font-bold">Admins only</h1>
        <p className="mt-2 text-sm text-muted-foreground">
          You don't have access to this page.
        </p>
        <Link
          to="/chat"
          className="mt-6 rounded-md bg-primary px-4 py-2 text-sm font-semibold text-primary-foreground"
        >
          Back to chat
        </Link>
      </div>
    );
  }

  return (
    <div className="flex min-h-screen flex-col bg-background">
      <header className="flex items-center justify-between border-b border-border px-4 py-3">
        <Link to="/chat" className="flex items-center gap-2 text-sm text-muted-foreground hover:text-foreground">
          <ArrowLeft className="h-4 w-4" /> Back to chat
        </Link>
        <div className="flex items-center gap-2">
          <div className="grid h-8 w-8 place-items-center rounded-lg bg-gradient-to-br from-primary to-accent text-primary-foreground">
            <Sparkles className="h-4 w-4" />
          </div>
          <span className="text-base font-bold tracking-tight">HI THERE Admin</span>
        </div>
      </header>

      <main className="mx-auto w-full max-w-2xl flex-1 px-6 py-10">
        <h1 className="text-2xl font-bold">AI Provider</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          The active provider is tried first. If it fails, requests automatically fall back to the
          next provider in order.
        </p>

        <div className="mt-6 space-y-3">
          {PROVIDERS.map((p) => {
            const isActive = p.id === active;
            return (
              <div
                key={p.id}
                className={`flex items-center justify-between gap-4 rounded-xl border p-4 transition ${
                  isActive ? "border-primary bg-card shadow-lg" : "border-border bg-card/60"
                }`}
              >
                <div>
                  <div className="flex items-center gap-2">
                    <h3 className="font-semibold">{p.name}</h3>
                    {isActive && (
                      <span className="inline-flex items-center gap-1 rounded-full bg-primary/15 px-2 py-0.5 text-xs text-primary">
                        <Check className="h-3 w-3" /> Active
                      </span>
                    )}
                  </div>
                  <p className="mt-0.5 text-xs text-muted-foreground">{p.desc}</p>
                </div>
                <button
                  disabled={isActive || saving === p.id}
                  onClick={() => switchTo(p.id)}
                  className="rounded-md border border-border bg-background px-3 py-1.5 text-xs font-medium hover:bg-accent disabled:opacity-40"
                >
                  {isActive ? "Active" : saving === p.id ? "Switching…" : "Switch to"}
                </button>
              </div>
            );
          })}
        </div>

        {savedAt && (
          <p className="mt-4 text-xs text-primary">Saved. Active provider updated.</p>
        )}

        <div className="mt-10 rounded-xl border border-border bg-card p-5 text-sm text-muted-foreground">
          <h3 className="mb-2 font-semibold text-foreground">Failover order</h3>
          <ol className="list-inside list-decimal space-y-1">
            <li>Active provider ({active})</li>
            <li>Then the remaining providers in default order: Gemini → Groq → Mistral</li>
          </ol>
        </div>
      </main>
      <Footer />
    </div>
  );
}