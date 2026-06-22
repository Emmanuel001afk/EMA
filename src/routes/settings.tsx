import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { useEffect, useState, useRef } from "react";
import { supabase } from "@/integrations/supabase/client";
import { Footer } from "@/components/Footer";
import {
  ArrowLeft, Sun, Moon, LogOut, Trash2, CreditCard, Check,
  Brain, Shield, Share2, Download, Eye, EyeOff, Mic, MicOff,
  ImageIcon, FileText, Volume2, Bell, Smartphone, Type, Baby,
  AlertCircle, Info, Zap, ToggleLeft, ToggleRight
} from "lucide-react";
import { useTheme } from "@/lib/theme";
import { LANGUAGES } from "@/lib/languages";

export const Route = createFileRoute("/settings")({ component: Settings });

const VOICES = [
  { id: "default", label: "System default" },
  { id: "naija-female", label: "Naija Female (warm)" },
  { id: "naija-male", label: "Naija Male (deep)" },
  { id: "uk-female", label: "UK Female" },
  { id: "us-male", label: "US Male" },
];

const FONT_STYLES = [
  { id: "default", label: "Default" },
  { id: "serif", label: "Serif" },
  { id: "mono", label: "Monospace" },
];

function Toggle({ on, onToggle }: { on: boolean; onToggle: () => void }) {
  return (
    <button
      onClick={onToggle}
      className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${on ? "bg-primary" : "bg-muted"}`}
    >
      <span className={`inline-block h-4 w-4 transform rounded-full bg-white shadow transition-transform ${on ? "translate-x-6" : "translate-x-1"}`} />
    </button>
  );
}

function Settings() {
  const navigate = useNavigate();
  const { theme, setTheme } = useTheme();
  const [userId, setUserId] = useState<string | null>(null);
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [language, setLanguage] = useState("en");
  const [plan, setPlan] = useState("free");
  const [voiceName, setVoiceName] = useState("default");
  const [fontStyle, setFontStyle] = useState("default");
  const [emailNotifications, setEmailNotifications] = useState(true);
  const [newPassword, setNewPassword] = useState("");
  const [newEmail, setNewEmail] = useState("");
  const [msg, setMsg] = useState<string | null>(null);
  const [msgType, setMsgType] = useState<"ok" | "err">("ok");
  const [paystackPublic, setPaystackPublic] = useState("");
  const [prices, setPrices] = useState({ basic: 2000, premium: 5000 });

  // Personalization
  const [aiName, setAiName] = useState("HI THERE");
  const [customInstructions, setCustomInstructions] = useState("");
  const [neverDo, setNeverDo] = useState("");

  // Capabilities toggles
  const [capWebSearch, setCapWebSearch] = useState(true);
  const [capImageGen, setCapImageGen] = useState(true);
  const [capFileReading, setCapFileReading] = useState(true);
  const [capMemory, setCapMemory] = useState(true);
  const [capVoice, setCapVoice] = useState(true);

  // Privacy
  const [autoDeleteDays, setAutoDeleteDays] = useState(0);

  // UX
  const [haptic, setHaptic] = useState(true);
  const [kidsMode, setKidsMode] = useState(false);

  // Memory
  const [memories, setMemories] = useState<string[]>([]);
  const [memoryLoading, setMemoryLoading] = useState(false);

  // Share
  const [shareUrl, setShareUrl] = useState<string | null>(null);
  const [sharing, setSharing] = useState(false);

  const [tab, setTab] = useState<"profile"|"personalization"|"privacy"|"data"|"capabilities"|"memory"|"about">("profile");

  useEffect(() => {
    (async () => {
      const { data: s } = await supabase.auth.getSession();
      if (!s.session) { navigate({ to: "/auth" }); return; }
      setUserId(s.session.user.id);
      setEmail(s.session.user.email || "");
      const { data: p } = await supabase.from("profiles").select("*").eq("user_id", s.session.user.id).maybeSingle();
      if (p) {
        setDisplayName((p as any).display_name || "");
        setLanguage((p as any).language || "en");
        setPlan((p as any).subscription_plan || "free");
        setTheme(((p as any).theme as any) || "dark");
        setVoiceName((p as any).voice_name || "default");
        // Load extended prefs from profile json field if present
        const prefs = (p as any).prefs || {};
        setAiName(prefs.ai_name || "HI THERE");
        setCustomInstructions(prefs.custom_instructions || "");
        setNeverDo(prefs.never_do || "");
        setCapWebSearch(prefs.cap_web_search !== false);
        setCapImageGen(prefs.cap_image_gen !== false);
        setCapFileReading(prefs.cap_file_reading !== false);
        setCapMemory(prefs.cap_memory !== false);
        setCapVoice(prefs.cap_voice !== false);
        setAutoDeleteDays(prefs.auto_delete_days || 0);
        setHaptic(prefs.haptic !== false);
        setKidsMode(prefs.kids_mode === true);
        setFontStyle(prefs.font_style || "default");
      }
      const { data: settingsRows } = await supabase.from("app_settings").select("key,value");
      const map: Record<string, any> = {};
      for (const r of settingsRows ?? []) map[r.key] = r.value;
      setPaystackPublic(map.paystack_public_key || "");
      setPrices({ basic: Number(map.basic_price_naira || 2000), premium: Number(map.premium_price_naira || 5000) });
    })();
  }, [navigate, setTheme]);

  const flash = (m: string, type: "ok" | "err" = "ok") => {
    setMsg(m); setMsgType(type);
    setTimeout(() => setMsg(null), 3000);
  };

  const saveProfile = async () => {
    if (!userId) return;
    const prefs = {
      ai_name: aiName, custom_instructions: customInstructions, never_do: neverDo,
      cap_web_search: capWebSearch, cap_image_gen: capImageGen, cap_file_reading: capFileReading,
      cap_memory: capMemory, cap_voice: capVoice, auto_delete_days: autoDeleteDays,
      haptic, kids_mode: kidsMode, font_style: fontStyle,
    };
    const { error } = await supabase.from("profiles").upsert(
      { user_id: userId, display_name: displayName, language, theme, voice_name: voiceName, prefs } as any,
      { onConflict: "user_id" },
    );
    if (error) return flash(error.message, "err");
    // Apply font globally
    document.documentElement.style.setProperty("--font-family",
      fontStyle === "serif" ? "Georgia, serif" :
      fontStyle === "mono" ? "monospace" : "");
    // Store caps in localStorage for chat.tsx to read
    localStorage.setItem("hi-there-caps", JSON.stringify(prefs));
    flash("Settings saved");
  };

  const changeEmail = async () => {
    if (!newEmail) return;
    const { error } = await supabase.auth.updateUser({ email: newEmail });
    if (error) return flash(error.message, "err");
    flash("Check your new email to confirm the change.");
    setNewEmail("");
  };

  const changePassword = async () => {
    if (newPassword.length < 6) return flash("Password must be at least 6 characters", "err");
    const { error } = await supabase.auth.updateUser({ password: newPassword });
    if (error) return flash(error.message, "err");
    flash("Password updated");
    setNewPassword("");
  };

  const deleteConversations = async () => {
    if (!confirm("Delete ALL your conversations? This cannot be undone.")) return;
    if (!userId) return;
    await supabase.from("messages").delete().eq("user_id", userId);
    await supabase.from("conversations").delete().eq("user_id", userId);
    flash("All conversations deleted");
  };

  const downloadData = async () => {
    if (!userId) return;
    const { data: convs } = await supabase.from("conversations").select("*").eq("user_id", userId);
    const { data: msgs } = await supabase.from("messages").select("*").eq("user_id", userId);
    const blob = new Blob([JSON.stringify({ conversations: convs, messages: msgs }, null, 2)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a"); a.href = url; a.download = "hi-there-data.json"; a.click();
    URL.revokeObjectURL(url);
  };

  const loadMemories = async () => {
    if (!userId) return;
    setMemoryLoading(true);
    const { data } = await supabase.from("profiles").select("memory_summary").eq("user_id", userId).maybeSingle();
    const raw = (data as any)?.memory_summary || "";
    setMemories(raw ? raw.split("\n").filter(Boolean) : []);
    setMemoryLoading(false);
  };

  const deleteMemory = async (idx: number) => {
    const updated = memories.filter((_, i) => i !== idx);
    setMemories(updated);
    if (!userId) return;
    await supabase.from("profiles").update({ memory_summary: updated.join("\n") } as any).eq("user_id", userId);
  };

  const clearAllMemory = async () => {
    if (!confirm("Clear all AI memory about you?")) return;
    setMemories([]);
    if (!userId) return;
    await supabase.from("profiles").update({ memory_summary: "" } as any).eq("user_id", userId);
    flash("Memory cleared");
  };

  const createShareLink = async () => {
    // Share the last conversation as read-only
    setSharing(true);
    const shareId = crypto.randomUUID().slice(0, 8);
    const { data: convs } = await supabase.from("conversations").select("id").eq("user_id", userId!).order("updated_at", { ascending: false }).limit(1);
    if (convs && convs[0]) {
      const url = `${window.location.origin}/chat?share=${shareId}&conv=${convs[0].id}`;
      setShareUrl(url);
      try { await navigator.clipboard.writeText(url); flash("Share link copied!"); } catch { flash("Link created — copy it above"); }
    }
    setSharing(false);
  };

  const deleteAccount = async () => {
    if (!confirm("Permanently delete your account and all data? This cannot be undone.")) return;
    if (!userId) return;
    await supabase.from("messages").delete().eq("user_id", userId);
    await supabase.from("conversations").delete().eq("user_id", userId);
    await supabase.from("notifications").delete().eq("user_id", userId);
    await supabase.from("profiles").delete().eq("user_id", userId);
    await supabase.auth.signOut();
    navigate({ to: "/" });
  };

  const signOut = async () => { await supabase.auth.signOut(); navigate({ to: "/" }); };

  const subscribe = (which: "basic" | "premium") => {
    if (!paystackPublic) return flash("Payments not configured yet.", "err");
    if (!email) return flash("Missing email", "err");
    const amount = (which === "basic" ? prices.basic : prices.premium) * 100;
    const ref = `HT-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    const PaystackPop = (window as any).PaystackPop;
    const startCheckout = () => {
      const handler = (window as any).PaystackPop.setup({
        key: paystackPublic, email, amount, currency: "NGN", ref,
        metadata: { plan: which, user_id: userId },
        callback: async (resp: any) => {
          await supabase.functions.invoke("paystack-verify", { body: { reference: resp.reference, plan: which } });
          flash("Subscription activated!"); setPlan(which);
        },
        onClose: () => flash("Payment cancelled", "err"),
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

  const TABS = [
    { id: "profile", label: "Profile" },
    { id: "personalization", label: "Personalization" },
    { id: "capabilities", label: "Capabilities" },
    { id: "privacy", label: "Privacy" },
    { id: "data", label: "Data" },
    { id: "memory", label: "Memory" },
    { id: "about", label: "About" },
  ] as const;

  return (
    <div className="flex min-h-screen flex-col bg-background">
      <header className="border-b border-border px-4 py-3 flex items-center justify-between">
        <Link to="/chat" className="inline-flex items-center gap-2 text-sm text-muted-foreground hover:text-foreground">
          <ArrowLeft className="h-4 w-4" /> Back to chat
        </Link>
      </header>

      <main className="mx-auto w-full max-w-2xl flex-1 px-4 py-8 sm:px-6">
        <h1 className="text-3xl font-bold">Settings</h1>
        {msg && (
          <div className={`mt-3 rounded-md px-3 py-2 text-sm ${msgType === "err" ? "bg-destructive/10 text-destructive" : "bg-primary/10 text-primary"}`}>
            {msg}
          </div>
        )}

        {/* Tab bar */}
        <div className="mt-5 flex flex-wrap gap-1 rounded-xl border border-border bg-card p-1">
          {TABS.map((t) => (
            <button key={t.id} onClick={() => setTab(t.id as any)}
              className={`rounded-lg px-3 py-1.5 text-sm font-medium transition-colors ${tab === t.id ? "bg-primary text-primary-foreground" : "text-muted-foreground hover:text-foreground"}`}>
              {t.label}
            </button>
          ))}
        </div>

        {/* PROFILE TAB */}
        {tab === "profile" && (
          <>
            <Section title="Profile">
              <Field label="Display name">
                <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} className="input" />
              </Field>
              <Field label="Language (AI responds in this language)">
                <select value={language} onChange={(e) => setLanguage(e.target.value)} className="input">
                  {LANGUAGES.map((l) => <option key={l.code} value={l.code}>{l.label}</option>)}
                </select>
              </Field>
              <Field label="Voice (for read-aloud)">
                <select value={voiceName} onChange={(e) => setVoiceName(e.target.value)} className="input">
                  {VOICES.map((v) => <option key={v.id} value={v.id}>{v.label}</option>)}
                </select>
              </Field>
              <Field label="Font style">
                <div className="flex gap-2">
                  {FONT_STYLES.map((f) => (
                    <button key={f.id} onClick={() => setFontStyle(f.id)}
                      className={`flex-1 rounded-lg border px-3 py-2 text-sm ${fontStyle === f.id ? "border-primary bg-primary/10 text-primary" : "border-border"}`}>
                      {f.label}
                    </button>
                  ))}
                </div>
              </Field>
              <div className="flex items-center justify-between rounded-lg border border-border bg-background p-3">
                <div>
                  <p className="text-sm font-medium">Haptic feedback</p>
                  <p className="text-xs text-muted-foreground">Vibration on actions (mobile)</p>
                </div>
                <Toggle on={haptic} onToggle={() => setHaptic(!haptic)} />
              </div>
              <div className="flex items-center justify-between rounded-lg border border-border bg-background p-3">
                <div>
                  <p className="text-sm font-medium flex items-center gap-1"><Baby className="h-4 w-4" /> Kids mode</p>
                  <p className="text-xs text-muted-foreground">Blocks adult topics, keeps content family-safe</p>
                </div>
                <Toggle on={kidsMode} onToggle={() => setKidsMode(!kidsMode)} />
              </div>
              <button onClick={saveProfile} className="btn-primary">Save profile</button>
            </Section>

            <Section title="Appearance">
              <div className="flex gap-2">
                <button onClick={() => setTheme("dark")} className={`btn-outline flex-1 ${theme === "dark" ? "border-primary" : ""}`}>
                  <Moon className="h-4 w-4" /> Dark
                </button>
                <button onClick={() => setTheme("light")} className={`btn-outline flex-1 ${theme === "light" ? "border-primary" : ""}`}>
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

            <Section title="Subscription">
              <p className="text-sm">Current plan: <span className="font-semibold capitalize">{plan}</span></p>
              <div className="grid gap-3 sm:grid-cols-2">
                <PlanCard name="Basic" price={prices.basic} active={plan === "basic"} features={["Unlimited messages", "Priority responses"]} onChoose={() => subscribe("basic")} />
                <PlanCard name="Premium" price={prices.premium} active={plan === "premium"} features={["Unlimited messages", "File & image uploads", "Camera input"]} onChoose={() => subscribe("premium")} />
              </div>
            </Section>
          </>
        )}

        {/* PERSONALIZATION TAB */}
        {tab === "personalization" && (
          <Section title="Personalization">
            <Field label="Name the AI uses for you">
              <input value={aiName} onChange={(e) => setAiName(e.target.value)} placeholder="HI THERE" className="input" />
            </Field>
            <Field label="Custom instructions (what should the AI always know about you?)">
              <textarea value={customInstructions} onChange={(e) => setCustomInstructions(e.target.value)}
                placeholder="I am a software developer. I prefer short answers. I'm based in Nigeria."
                rows={4} className="input resize-none" />
            </Field>
            <Field label="What the AI must NEVER do">
              <textarea value={neverDo} onChange={(e) => setNeverDo(e.target.value)}
                placeholder="Never use emojis. Never say 'certainly'. Never give long introductions."
                rows={3} className="input resize-none" />
            </Field>
            <button onClick={saveProfile} className="btn-primary">Save</button>
          </Section>
        )}

        {/* CAPABILITIES TAB */}
        {tab === "capabilities" && (
          <Section title="Capability Toggles">
            {[
              { label: "Web search", sub: "Let AI search the internet for current info", val: capWebSearch, set: setCapWebSearch },
              { label: "Image generation", sub: "AI can generate images from text", val: capImageGen, set: setCapImageGen },
              { label: "File & ZIP reading", sub: "AI reads uploaded files and code projects", val: capFileReading, set: setCapFileReading },
              { label: "Memory", sub: "AI remembers info about you across chats", val: capMemory, set: setCapMemory },
              { label: "Voice", sub: "Voice input and audio responses", val: capVoice, set: setCapVoice },
            ].map((item) => (
              <div key={item.label} className="flex items-center justify-between rounded-lg border border-border bg-background p-3">
                <div>
                  <p className="text-sm font-medium">{item.label}</p>
                  <p className="text-xs text-muted-foreground">{item.sub}</p>
                </div>
                <Toggle on={item.val} onToggle={() => item.set(!item.val)} />
              </div>
            ))}
            <button onClick={saveProfile} className="btn-primary">Save</button>
          </Section>
        )}

        {/* PRIVACY TAB */}
        {tab === "privacy" && (
          <>
            <Section title="Privacy">
              <Field label="Auto-delete conversations after (days, 0 = never)">
                <input type="number" min={0} max={365} value={autoDeleteDays}
                  onChange={(e) => setAutoDeleteDays(Number(e.target.value))} className="input" />
              </Field>
              <button onClick={saveProfile} className="btn-primary">Save</button>
            </Section>
            <Section title="Shared Conversations">
              <p className="text-xs text-muted-foreground mb-3">Create a read-only link to share your latest conversation. Viewers cannot edit or reply.</p>
              <button onClick={createShareLink} disabled={sharing} className="btn-primary">
                <Share2 className="h-4 w-4" /> {sharing ? "Creating..." : "Create share link"}
              </button>
              {shareUrl && (
                <div className="mt-3 rounded-lg border border-border bg-background p-3">
                  <p className="text-xs text-muted-foreground mb-1">Share link (read-only):</p>
                  <p className="break-all text-xs font-mono">{shareUrl}</p>
                </div>
              )}
            </Section>
            <Section title="Danger zone">
              <button onClick={signOut} className="btn-outline w-full"><LogOut className="h-4 w-4" /> Sign out</button>
              <button onClick={deleteConversations} className="btn-outline w-full text-destructive"><Trash2 className="h-4 w-4" /> Delete all conversations</button>
              <button onClick={deleteAccount} className="btn-outline w-full text-destructive"><Trash2 className="h-4 w-4" /> Delete account</button>
            </Section>
          </>
        )}

        {/* DATA TAB */}
        {tab === "data" && (
          <Section title="Your Data">
            <p className="text-sm text-muted-foreground">Download everything HI THERE has stored about you — all conversations and messages — as a JSON file.</p>
            <button onClick={downloadData} className="btn-primary"><Download className="h-4 w-4" /> Download my data</button>
          </Section>
        )}

        {/* MEMORY TAB */}
        {tab === "memory" && (
          <Section title="AI Memory">
            <p className="text-sm text-muted-foreground mb-3">These are facts the AI has learned about you from your conversations. You can delete any of them.</p>
            <button onClick={loadMemories} disabled={memoryLoading} className="btn-outline mb-3">
              <Brain className="h-4 w-4" /> {memoryLoading ? "Loading..." : "Load memories"}
            </button>
            {memories.length === 0 && !memoryLoading && (
              <p className="text-sm text-muted-foreground">No memories stored yet, or click Load to check.</p>
            )}
            <div className="space-y-2">
              {memories.map((m, i) => (
                <div key={i} className="flex items-start gap-2 rounded-lg border border-border bg-background p-3">
                  <p className="flex-1 text-sm">{m}</p>
                  <button onClick={() => deleteMemory(i)} className="text-destructive hover:opacity-70">
                    <Trash2 className="h-4 w-4" />
                  </button>
                </div>
              ))}
            </div>
            {memories.length > 0 && (
              <button onClick={clearAllMemory} className="btn-outline mt-3 text-destructive">
                <Trash2 className="h-4 w-4" /> Clear all memory
              </button>
            )}
          </Section>
        )}

        {/* ABOUT TAB */}
        {tab === "about" && (
          <>
            <Section title="About HI THERE">
              <p className="text-sm text-muted-foreground">HI THERE is a warm, intelligent AI assistant built for Nigerians and the world. It supports multiple languages, file reading, image generation, voice, and more.</p>
              <p className="text-sm text-muted-foreground mt-2">Version: 2.0 — built with ❤️</p>
            </Section>
            <Section title="Report a Problem">
              <ReportProblem userId={userId} flash={flash} />
            </Section>
          </>
        )}
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

function ReportProblem({ userId, flash }: { userId: string | null; flash: (m: string, t?: any) => void }) {
  const [text, setText] = useState("");
  const [sending, setSending] = useState(false);
  const send = async () => {
    if (!text.trim()) return;
    setSending(true);
    await supabase.from("notifications").insert({ user_id: userId, type: "report", message: text } as any);
    setText("");
    setSending(false);
    flash("Report sent — thank you!");
  };
  return (
    <div className="space-y-3">
      <textarea value={text} onChange={(e) => setText(e.target.value)} placeholder="Describe the problem..." rows={3} className="input resize-none" />
      <button onClick={send} disabled={sending} className="btn-primary">{sending ? "Sending..." : "Send report"}</button>
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

function PlanCard({ name, price, features, active, onChoose }: { name: string; price: number; features: string[]; active: boolean; onChoose: () => void }) {
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
        <CreditCard className="h-3 w-3" /> {active ? "Current plan" : "Subscribe"}
      </button>
    </div>
  );
}
