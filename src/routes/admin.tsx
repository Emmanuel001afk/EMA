import { createFileRoute, useNavigate, Link } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { supabase } from "@/integrations/supabase/client";
import { Sparkles, ArrowLeft, Check, Users, Megaphone, CreditCard, Settings as Cog, Activity } from "lucide-react";
import { Footer } from "@/components/Footer";

export const Route = createFileRoute("/admin")({ component: AdminPage });

const PROVIDERS = [
  { id: "gemini", name: "Provider A" },
  { id: "groq", name: "Provider B" },
  { id: "mistral", name: "Provider C" },
] as const;

type Tab = "overview" | "providers" | "users" | "announce" | "settings" | "transactions";

function AdminPage() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(true);
  const [authorized, setAuthorized] = useState(false);
  const [tab, setTab] = useState<Tab>("overview");

  // settings
  const [settings, setSettings] = useState<Record<string, any>>({});
  const [saving, setSaving] = useState<string | null>(null);
  const [savedAt, setSavedAt] = useState<number | null>(null);

  // users
  const [users, setUsers] = useState<any[]>([]);
  // announcements
  const [annTitle, setAnnTitle] = useState("");
  const [annBody, setAnnBody] = useState("");
  const [announcements, setAnnouncements] = useState<any[]>([]);
  // metrics
  const [metrics, setMetrics] = useState({
    totalUsers: 0, msgsToday: 0, totalMsgs: 0, totalRevenue: 0, txCount: 0,
  });
  const [transactions, setTransactions] = useState<any[]>([]);

  useEffect(() => {
    (async () => {
      const { data: s } = await supabase.auth.getSession();
      if (!s.session) { navigate({ to: "/auth" }); return; }
      const { data: roles } = await supabase.from("user_roles").select("role").eq("user_id", s.session.user.id);
      const ok = !!roles?.some((r) => r.role === "admin");
      setAuthorized(ok);
      if (ok) await loadAll();
      setLoading(false);
    })();
    // eslint-disable-next-line
  }, [navigate]);

  const loadAll = async () => {
    await Promise.all([loadSettings(), loadUsers(), loadAnnouncements(), loadMetrics(), loadTransactions()]);
  };

  const loadSettings = async () => {
    const { data } = await supabase.from("app_settings").select("key,value");
    const map: Record<string, any> = {};
    for (const r of data ?? []) map[r.key] = r.value;
    setSettings(map);
  };
  const loadUsers = async () => {
    const { data } = await supabase.from("profiles")
      .select("user_id,display_name,email,subscription_plan,banned,last_active_at,daily_message_count,created_at")
      .order("created_at", { ascending: false });
    setUsers(data ?? []);
  };
  const loadAnnouncements = async () => {
    const { data } = await supabase.from("announcements").select("*").order("created_at", { ascending: false }).limit(20);
    setAnnouncements(data ?? []);
  };
  const loadMetrics = async () => {
    const todayStart = new Date(); todayStart.setHours(0,0,0,0);
    const [u, mTotal, mToday, txAll] = await Promise.all([
      supabase.from("profiles").select("user_id", { count: "exact", head: true }),
      supabase.from("messages").select("id", { count: "exact", head: true }),
      supabase.from("messages").select("id", { count: "exact", head: true }).gte("created_at", todayStart.toISOString()),
      supabase.from("transactions").select("amount_kobo,status").eq("status","success"),
    ]);
    const totalRevenue = (txAll.data ?? []).reduce((s, t: any) => s + (t.amount_kobo || 0), 0) / 100;
    setMetrics({
      totalUsers: u.count ?? 0, totalMsgs: mTotal.count ?? 0,
      msgsToday: mToday.count ?? 0, totalRevenue, txCount: (txAll.data ?? []).length,
    });
  };
  const loadTransactions = async () => {
    const { data } = await supabase.from("transactions").select("*").order("created_at", { ascending: false }).limit(50);
    setTransactions(data ?? []);
  };

  const updateSetting = async (key: string, value: any) => {
    setSaving(key);
    const { error } = await supabase
      .from("app_settings")
      .upsert({ key, value, updated_at: new Date().toISOString() }, { onConflict: "key" });
    setSaving(null);
    if (!error) {
      setSettings((s) => ({ ...s, [key]: value }));
      setSavedAt(Date.now()); setTimeout(() => setSavedAt(null), 2000);
    }
  };

  const switchProvider = (id: string) => updateSetting("active_provider", id);

  const sendAnnouncement = async () => {
    if (!annTitle.trim() || !annBody.trim()) return;
    await supabase.from("announcements").insert({ title: annTitle, body: annBody });
    setAnnTitle(""); setAnnBody(""); await loadAnnouncements();
  };

  const setUserPlan = async (uid: string, plan: string) => {
    await supabase.from("profiles").update({ subscription_plan: plan }).eq("user_id", uid);
    await loadUsers();
  };
  const banUser = async (uid: string, banned: boolean) => {
    await supabase.from("profiles").update({ banned }).eq("user_id", uid);
    await loadUsers();
  };
  const deleteUser = async (uid: string) => {
    if (!confirm("Delete this user's profile and data? Auth account remains until purged separately.")) return;
    await supabase.from("messages").delete().eq("user_id", uid);
    await supabase.from("conversations").delete().eq("user_id", uid);
    await supabase.from("notifications").delete().eq("user_id", uid);
    await supabase.from("profiles").delete().eq("user_id", uid);
    await loadUsers();
  };

  if (loading) {
    return <div className="grid min-h-screen place-items-center bg-background text-muted-foreground">Loading…</div>;
  }
  if (!authorized) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center bg-background px-4 text-center">
        <h1 className="text-2xl font-bold">Admins only</h1>
        <p className="mt-2 text-sm text-muted-foreground">You don't have access to this page.</p>
        <Link to="/chat" className="mt-6 rounded-md bg-primary px-4 py-2 text-sm font-semibold text-primary-foreground">
          Back to chat
        </Link>
      </div>
    );
  }

  const active = (settings.active_provider as string) || "gemini";

  return (
    <div className="flex min-h-screen flex-col bg-background">
      <header className="flex items-center justify-between border-b border-border px-3 py-3 sm:px-4">
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

      <div className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-4 px-3 py-6 sm:px-6 md:flex-row">
        <nav className="flex shrink-0 flex-row gap-1 overflow-x-auto rounded-xl border border-border bg-card p-2 md:w-56 md:flex-col">
          {[
            { id: "overview", label: "Overview", icon: Activity },
            { id: "providers", label: "AI Provider", icon: Sparkles },
            { id: "users", label: "Users", icon: Users },
            { id: "announce", label: "Announcements", icon: Megaphone },
            { id: "transactions", label: "Revenue", icon: CreditCard },
            { id: "settings", label: "Settings", icon: Cog },
          ].map((t) => (
            <button key={t.id} onClick={() => setTab(t.id as Tab)}
              className={`inline-flex items-center gap-2 rounded-md px-3 py-2 text-sm font-medium transition ${
                tab === t.id ? "bg-primary text-primary-foreground" : "hover:bg-accent"
              }`}>
              <t.icon className="h-4 w-4" /> {t.label}
            </button>
          ))}
        </nav>

        <main className="min-w-0 flex-1 space-y-4">
          {savedAt && <div className="rounded-md bg-primary/10 px-3 py-2 text-sm text-primary">Saved.</div>}

          {tab === "overview" && (
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
              <Stat label="Users" value={metrics.totalUsers} />
              <Stat label="Messages today" value={metrics.msgsToday} />
              <Stat label="Total messages" value={metrics.totalMsgs} />
              <Stat label="Total revenue" value={`₦${metrics.totalRevenue.toLocaleString()}`} />
              <Stat label="Transactions" value={metrics.txCount} />
              <Stat label="Active provider" value={active} />
            </div>
          )}

          {tab === "providers" && (
            <div className="space-y-3">
              <h2 className="text-lg font-semibold">AI Provider</h2>
              <p className="text-xs text-muted-foreground">The active provider is tried first; failures auto-failover to the next.</p>
              {PROVIDERS.map((p) => {
                const isActive = p.id === active;
                return (
                  <div key={p.id} className={`flex items-center justify-between gap-4 rounded-xl border p-4 ${isActive ? "border-primary bg-card shadow-lg" : "border-border bg-card/60"}`}>
                    <div>
                      <div className="flex items-center gap-2">
                        <h3 className="font-semibold">{p.name}</h3>
                        {isActive && <span className="inline-flex items-center gap-1 rounded-full bg-primary/15 px-2 py-0.5 text-xs text-primary"><Check className="h-3 w-3" /> Active</span>}
                      </div>
                    </div>
                    <button disabled={isActive || saving === p.id} onClick={() => switchProvider(p.id)}
                      className="rounded-md border border-border bg-background px-3 py-1.5 text-xs font-medium hover:bg-accent disabled:opacity-40">
                      {isActive ? "Active" : saving === "active_provider" ? "…" : "Switch"}
                    </button>
                  </div>
                );
              })}
            </div>
          )}

          {tab === "users" && (
            <div className="space-y-3">
              <h2 className="text-lg font-semibold">Users ({users.length})</h2>
              <div className="overflow-x-auto rounded-xl border border-border bg-card">
                <table className="min-w-full text-sm">
                  <thead className="bg-muted text-xs uppercase text-muted-foreground">
                    <tr>
                      <th className="px-3 py-2 text-left">User</th>
                      <th className="px-3 py-2 text-left">Plan</th>
                      <th className="px-3 py-2 text-left">Last active</th>
                      <th className="px-3 py-2 text-left">Status</th>
                      <th className="px-3 py-2 text-right">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {users.map((u) => (
                      <tr key={u.user_id} className="border-t border-border">
                        <td className="px-3 py-2"><div className="font-medium">{u.display_name}</div><div className="text-xs text-muted-foreground">{u.email}</div></td>
                        <td className="px-3 py-2">
                          <select value={u.subscription_plan} onChange={(e) => setUserPlan(u.user_id, e.target.value)}
                            className="rounded border border-input bg-background px-2 py-1 text-xs">
                            <option value="free">Free</option>
                            <option value="basic">Basic</option>
                            <option value="premium">Premium</option>
                          </select>
                        </td>
                        <td className="px-3 py-2 text-xs">{u.last_active_at ? new Date(u.last_active_at).toLocaleString() : "—"}</td>
                        <td className="px-3 py-2">
                          {u.banned ? <span className="rounded bg-destructive/15 px-2 py-0.5 text-xs text-destructive">Banned</span> : <span className="rounded bg-primary/15 px-2 py-0.5 text-xs text-primary">Active</span>}
                        </td>
                        <td className="px-3 py-2 text-right">
                          <div className="inline-flex gap-1">
                            <button onClick={() => banUser(u.user_id, !u.banned)} className="rounded border border-border px-2 py-1 text-xs hover:bg-accent">{u.banned ? "Unban" : "Ban"}</button>
                            <button onClick={() => deleteUser(u.user_id)} className="rounded border border-destructive/40 px-2 py-1 text-xs text-destructive hover:bg-destructive/10">Delete</button>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {tab === "announce" && (
            <div className="space-y-3">
              <h2 className="text-lg font-semibold">Send announcement</h2>
              <div className="rounded-xl border border-border bg-card p-4 space-y-2">
                <input value={annTitle} onChange={(e) => setAnnTitle(e.target.value)} placeholder="Title"
                  className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm" />
                <textarea value={annBody} onChange={(e) => setAnnBody(e.target.value)} placeholder="Message body" rows={4}
                  className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm" />
                <button onClick={sendAnnouncement}
                  className="rounded-md bg-primary px-4 py-2 text-sm font-semibold text-primary-foreground">
                  Broadcast to all users
                </button>
              </div>
              <h3 className="mt-6 text-sm font-semibold">Recent announcements</h3>
              <div className="space-y-2">
                {announcements.map((a) => (
                  <div key={a.id} className="rounded-lg border border-border bg-card p-3 text-sm">
                    <div className="flex justify-between"><span className="font-semibold">{a.title}</span><span className="text-xs text-muted-foreground">{new Date(a.created_at).toLocaleString()}</span></div>
                    <p className="mt-1 text-muted-foreground">{a.body}</p>
                  </div>
                ))}
                {announcements.length === 0 && <p className="text-xs text-muted-foreground">No announcements yet.</p>}
              </div>
            </div>
          )}

          {tab === "transactions" && (
            <div className="space-y-3">
              <h2 className="text-lg font-semibold">Transactions ({transactions.length})</h2>
              <p className="text-sm">Total revenue: <span className="font-bold">₦{metrics.totalRevenue.toLocaleString()}</span></p>
              <div className="overflow-x-auto rounded-xl border border-border bg-card">
                <table className="min-w-full text-sm">
                  <thead className="bg-muted text-xs uppercase text-muted-foreground">
                    <tr><th className="px-3 py-2 text-left">Reference</th><th className="px-3 py-2 text-left">Plan</th><th className="px-3 py-2 text-left">Amount</th><th className="px-3 py-2 text-left">Status</th><th className="px-3 py-2 text-left">Date</th></tr>
                  </thead>
                  <tbody>
                    {transactions.map((t) => (
                      <tr key={t.id} className="border-t border-border">
                        <td className="px-3 py-2 font-mono text-xs">{t.reference}</td>
                        <td className="px-3 py-2 capitalize">{t.plan}</td>
                        <td className="px-3 py-2">₦{(t.amount_kobo/100).toLocaleString()}</td>
                        <td className="px-3 py-2"><span className={`rounded px-2 py-0.5 text-xs ${t.status === "success" ? "bg-primary/15 text-primary" : "bg-muted"}`}>{t.status}</span></td>
                        <td className="px-3 py-2 text-xs">{new Date(t.created_at).toLocaleString()}</td>
                      </tr>
                    ))}
                    {transactions.length === 0 && <tr><td colSpan={5} className="px-3 py-6 text-center text-xs text-muted-foreground">No transactions yet.</td></tr>}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {tab === "settings" && (
            <div className="space-y-4">
              <h2 className="text-lg font-semibold">Platform Settings</h2>
              <SettingRow label="Customer care email" value={settings.contact_email || ""} onSave={(v) => updateSetting("contact_email", v)} />
              <SettingRow label="Customer care phone" value={settings.contact_phone || ""} onSave={(v) => updateSetting("contact_phone", v)} />
              <h3 className="pt-4 text-sm font-semibold uppercase text-muted-foreground">Paystack</h3>
              <SettingRow label="Paystack public key" value={settings.paystack_public_key || ""} onSave={(v) => updateSetting("paystack_public_key", v)} placeholder="pk_live_..." />
              <SettingRow label="Paystack secret key" value={settings.paystack_secret_key || ""} onSave={(v) => updateSetting("paystack_secret_key", v)} placeholder="sk_live_..." secret />
              <SettingRow label="Paystack webhook secret" value={settings.paystack_webhook_secret || ""} onSave={(v) => updateSetting("paystack_webhook_secret", v)} secret />
              <h3 className="pt-4 text-sm font-semibold uppercase text-muted-foreground">Flutterwave</h3>
              <SettingRow label="Flutterwave public key" value={settings.flutterwave_public_key || ""} onSave={(v) => updateSetting("flutterwave_public_key", v)} placeholder="FLWPUBK-..." />
              <SettingRow label="Flutterwave secret key" value={settings.flutterwave_secret_key || ""} onSave={(v) => updateSetting("flutterwave_secret_key", v)} placeholder="FLWSECK-..." secret />
              <SettingRow label="Flutterwave webhook secret hash" value={settings.flutterwave_webhook_secret || ""} onSave={(v) => updateSetting("flutterwave_webhook_secret", v)} secret />
              <h3 className="pt-4 text-sm font-semibold uppercase text-muted-foreground">Custom API Keys</h3>
              <SettingRow label="Custom slot 1 — name" value={settings.custom_api_1_name || ""} onSave={(v) => updateSetting("custom_api_1_name", v)} />
              <SettingRow label="Custom slot 1 — key" value={settings.custom_api_1_key || ""} onSave={(v) => updateSetting("custom_api_1_key", v)} secret />
              <SettingRow label="Custom slot 2 — name" value={settings.custom_api_2_name || ""} onSave={(v) => updateSetting("custom_api_2_name", v)} />
              <SettingRow label="Custom slot 2 — key" value={settings.custom_api_2_key || ""} onSave={(v) => updateSetting("custom_api_2_key", v)} secret />
              <SettingRow label="Custom slot 3 — name" value={settings.custom_api_3_name || ""} onSave={(v) => updateSetting("custom_api_3_name", v)} />
              <SettingRow label="Custom slot 3 — key" value={settings.custom_api_3_key || ""} onSave={(v) => updateSetting("custom_api_3_key", v)} secret />
              <h3 className="pt-4 text-sm font-semibold uppercase text-muted-foreground">Limits & Pricing</h3>
              <SettingRow label="Free daily message limit" value={String(settings.daily_free_limit ?? 20)} onSave={(v) => updateSetting("daily_free_limit", Number(v) || 20)} />
              <SettingRow label="Basic plan price (₦)" value={String(settings.basic_price_naira ?? 2000)} onSave={(v) => updateSetting("basic_price_naira", Number(v) || 2000)} />
              <SettingRow label="Premium plan price (₦)" value={String(settings.premium_price_naira ?? 5000)} onSave={(v) => updateSetting("premium_price_naira", Number(v) || 5000)} />
            </div>
          )}
        </main>
      </div>
      <Footer />
    </div>
  );
}

function Stat({ label, value }: { label: string; value: any }) {
  return (
    <div className="rounded-xl border border-border bg-card p-4">
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="mt-1 text-2xl font-bold">{value}</p>
    </div>
  );
}

function SettingRow({ label, value, onSave, placeholder, secret }:
  { label: string; value: string; onSave: (v: string) => void; placeholder?: string; secret?: boolean }) {
  const [v, setV] = useState(value);
  useEffect(() => setV(value), [value]);
  return (
    <div className="rounded-xl border border-border bg-card p-4">
      <label className="text-sm font-medium">{label}</label>
      <div className="mt-2 flex gap-2">
        <input type={secret ? "password" : "text"} value={v} onChange={(e) => setV(e.target.value)} placeholder={placeholder}
          className="flex-1 rounded-md border border-input bg-background px-3 py-2 text-sm" />
        <button onClick={() => onSave(v)}
          className="rounded-md bg-primary px-3 py-2 text-sm font-semibold text-primary-foreground">Save</button>
      </div>
    </div>
  );
}