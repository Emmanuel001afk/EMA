import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { supabase } from "@/integrations/supabase/client";
import { Footer } from "@/components/Footer";
import { ArrowLeft, Sun, Moon, LogOut, Trash2, CreditCard, Check } from "lucide-react";
import { useTheme } from "@/lib/theme";
import { LANGUAGES } from "@/lib/languages";

export const Route = createFileRoute("/settings")({ component: Settings });

function Settings() {
  const navigate = useNavigate();
  const { theme, setTheme } = useTheme();
  const [userId, setUserId] = useState<string | null>(null);
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [language, setLanguage] = useState("en");
  const [plan, setPlan] = useState("free");
  const [emailNotifications, setEmailNotifications] = useState(true);
  const [newPassword, setNewPassword] = useState("");
  const [newEmail, setNewEmail] = useState("");
  const [msg, setMsg] = useState<string | null>(null);
  const [paystackPublic, setPaystackPublic] = useState("");
  const [prices, setPrices] = useState({ basic: 2000, premium: 5000 });

  useEffect(() => {
    (async () => {
      const { data: s } = await supabase.auth.getSession();
      if (!s.session) { navigate({ to: "/auth" }); return; }
      setUserId(s.session.user.id);
      setEmail(s.session.user.email || "");
      const { data: p } = await supabase.from("profiles").select("*").eq("user_id", s.session.user.id).maybeSingle();
      if (p) {
        setDisplayName(p.display_name || "");
        setLanguage(p.language || "en");
        setPlan(p.subscription_plan || "free");
        setTheme((p.theme as any) || "dark");
      }
      const { data: settingsRows } = await supabase.from("app_settings").select("key,value");
      const map: Record<string, any> = {};
      for (const r of settingsRows ?? []) map[r.key] = r.value;
      setPaystackPublic(map.paystack_public_key || "");
      setPrices({ basic: Number(map.basic_price_naira || 2000), premium: Number(map.premium_price_naira || 5000) });
    })();
  }, [navigate, setTheme]);

  const flash = (m: string) => { setMsg(m); setTimeout(() => setMsg(null), 2500); };

  const saveProfile = async () => {
    if (!userId) return;
    await supabase.from("profiles").update({ display_name: displayName, language, theme }).eq("user_id", userId);
    flash("Profile saved");
  };

  const changeEmail = async () => {
    if (!newEmail) return;
    const { error } = await supabase.auth.updateUser({ email: newEmail });
    if (error) return flash(error.message);
    flash("Check your new email to confirm the change.");
    setNewEmail("");
  };
  const changePassword = async () => {
    if (newPassword.length < 6) return flash("Password must be at least 6 characters");
    const { error } = await supabase.auth.updateUser({ password: newPassword });
    if (error) return flash(error.message);
    flash("Password updated"); setNewPassword("");
  };

  const deleteAccount = async () => {
    if (!confirm("Permanently delete your account and all data? This cannot be undone.")) return;
    if (!userId) return;
    // Delete profile cascade-ish: messages/conversations owned by user
    await supabase.from("messages").delete().eq("user_id", userId);
    await supabase.from("conversations").delete().eq("user_id", userId);
    await supabase.from("notifications").delete().eq("user_id", userId);
    await supabase.from("profiles").delete().eq("user_id", userId);
    await supabase.auth.signOut();
    navigate({ to: "/" });
  };

  const signOut = async () => { await supabase.auth.signOut(); navigate({ to: "/" }); };

  const subscribe = (which: "basic" | "premium") => {
    if (!paystackPublic) return flash("Payments not configured yet. Please try again later.");
    if (!email) return flash("Missing email");
    const amount = (which === "basic" ? prices.basic : prices.premium) * 100;
    const ref = `HT-${Date.now()}-${Math.random().toString(36).slice(2,8)}`;
    // @ts-ignore
    const PaystackPop = (window as any).PaystackPop;
    const startCheckout = () => {
      // @ts-ignore
      const handler = (window as any).PaystackPop.setup({
        key: paystackPublic, email, amount, currency: "NGN", ref,
        metadata: { plan: which, user_id: userId },
        callback: async (resp: any) => {
          await supabase.functions.invoke("paystack-verify", { body: { reference: resp.reference, plan: which } });
          flash("Subscription activated!"); setPlan(which);
        },
        onClose: () => flash("Payment cancelled"),
      });
      handler.openIframe();
    };
    if (PaystackPop) startCheckout();
    else {
      const s = document.createElement("script");
      s.src = "https://js.paystack.co/v1/inline.js";
      s.onload = startCheckout;
      document.body.appendChild(s);
    }
  };

  return (
    <div className="flex min-h-screen flex-col bg-background">
      <header className="border-b border-border px-4 py-3">
        <Link to="/chat" className="inline-flex items-center gap-2 text-sm text-muted-foreground hover:text-foreground">
          <ArrowLeft className="h-4 w-4" /> Back to chat
        </Link>
      </header>
      <main className="mx-auto w-full max-w-2xl flex-1 px-4 py-8 sm:px-6">
        <h1 className="text-3xl font-bold">Settings</h1>
        {msg && <div className="mt-3 rounded-md bg-primary/10 px-3 py-2 text-sm text-primary">{msg}</div>}

        <Section title="Profile">
          <Field label="Display name">
            <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} className="input" />
          </Field>
          <Field label="Language (AI responds in this language)">
            <select value={language} onChange={(e) => setLanguage(e.target.value)} className="input">
              {LANGUAGES.map((l) => <option key={l.code} value={l.code}>{l.label}</option>)}
            </select>
          </Field>
          <button onClick={saveProfile} className="btn-primary">Save profile</button>
        </Section>

        <Section title="Appearance">
          <div className="flex gap-2">
            <button onClick={() => setTheme("dark")}
              className={`btn-outline flex-1 ${theme === "dark" ? "border-primary" : ""}`}>
              <Moon className="h-4 w-4" /> Dark
            </button>
            <button onClick={() => setTheme("light")}
              className={`btn-outline flex-1 ${theme === "light" ? "border-primary" : ""}`}>
              <Sun className="h-4 w-4" /> Light
            </button>
          </div>
        </Section>

        <Section title="Account">
          <Field label="Current email"><input value={email} disabled className="input opacity-60" /></Field>
          <Field label="Change email">
            <div className="flex gap-2">
              <input value={newEmail} onChange={(e) => setNewEmail(e.target.value)} placeholder="new@email.com" className="input flex-1" />
              <button onClick={changeEmail} className="btn-primary">Update</button>
            </div>
          </Field>
          <Field label="Change password">
            <div className="flex gap-2">
              <input type="password" value={newPassword} onChange={(e) => setNewPassword(e.target.value)} placeholder="New password" className="input flex-1" />
              <button onClick={changePassword} className="btn-primary">Update</button>
            </div>
          </Field>
        </Section>

        <Section title="Notifications">
          <label className="flex cursor-pointer items-center justify-between rounded-md border border-border bg-card p-3 text-sm">
            <span>Show in-app notifications</span>
            <input type="checkbox" checked={emailNotifications} onChange={(e) => setEmailNotifications(e.target.checked)} />
          </label>
        </Section>

        <Section title="Subscription">
          <p className="text-sm">Current plan: <span className="font-semibold capitalize">{plan}</span></p>
          <div className="grid gap-3 sm:grid-cols-2">
            <PlanCard name="Basic" price={prices.basic} active={plan === "basic"} features={["Unlimited messages", "Priority responses"]} onChoose={() => subscribe("basic")} />
            <PlanCard name="Premium" price={prices.premium} active={plan === "premium"} features={["Unlimited messages", "File & image uploads", "Camera input"]} onChoose={() => subscribe("premium")} />
          </div>
        </Section>

        <Section title="Danger zone">
          <button onClick={signOut} className="btn-outline w-full"><LogOut className="h-4 w-4" /> Sign out</button>
          <button onClick={deleteAccount} className="btn-outline w-full text-destructive"><Trash2 className="h-4 w-4" /> Delete account</button>
        </Section>
      </main>
      <Footer />
      <style>{`
        .input { width:100%; border-radius:0.5rem; border:1px solid var(--input); background:var(--background); padding:0.5rem 0.75rem; font-size:0.875rem; outline:none; }
        .input:focus { box-shadow: 0 0 0 2px var(--ring); }
        .btn-primary { display:inline-flex; align-items:center; gap:0.5rem; border-radius:0.5rem; background:var(--primary); color:var(--primary-foreground); padding:0.5rem 0.875rem; font-size:0.875rem; font-weight:600; }
        .btn-outline { display:inline-flex; align-items:center; justify-content:center; gap:0.5rem; border-radius:0.5rem; border:1px solid var(--border); background:var(--card); padding:0.5rem 0.875rem; font-size:0.875rem; font-weight:500; }
      `}</style>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="mt-6 rounded-2xl border border-border bg-card p-5">
      <h2 className="text-base font-semibold">{title}</h2>
      <div className="mt-4 space-y-3">{children}</div>
    </section>
  );
}
function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <label className="block text-sm"><span className="mb-1 block text-muted-foreground">{label}</span>{children}</label>;
}
function PlanCard({ name, price, features, active, onChoose }:{name:string;price:number;features:string[];active:boolean;onChoose:()=>void}) {
  return (
    <div className={`rounded-xl border p-4 ${active ? "border-primary bg-primary/5" : "border-border"}`}>
      <div className="flex items-baseline justify-between">
        <h3 className="font-semibold">{name}</h3>
        <span className="text-sm">₦{price.toLocaleString()}<span className="text-muted-foreground">/mo</span></span>
      </div>
      <ul className="mt-3 space-y-1 text-xs text-muted-foreground">
        {features.map((f) => <li key={f} className="flex items-center gap-1.5"><Check className="h-3 w-3 text-primary" />{f}</li>)}
      </ul>
      <button onClick={onChoose} disabled={active}
        className="mt-4 inline-flex w-full items-center justify-center gap-2 rounded-md bg-primary px-3 py-2 text-xs font-semibold text-primary-foreground disabled:opacity-50">
        <CreditCard className="h-3 w-3" /> {active ? "Current plan" : `Subscribe`}
      </button>
    </div>
  );
}