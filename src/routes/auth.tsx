import { createFileRoute, useNavigate, Link } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { supabase } from "@/integrations/supabase/client";
import { lovable } from "@/integrations/lovable";
import { Sparkles } from "lucide-react";
import { Footer } from "@/components/Footer";
import aemLogo from "@/assets/aem-logo.png";

export const Route = createFileRoute("/auth")({
  component: AuthPage,
});

function AuthPage() {
  const navigate = useNavigate();
  const [mode, setMode] = useState<"signin" | "signup">("signin");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [name, setName] = useState("");
  const [loading, setLoading] = useState(false);
  const [settingUp, setSettingUp] = useState(false);
  const [setupStep, setSetupStep] = useState<1 | 2>(1);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    supabase.auth.getSession().then(({ data }) => {
      if (data.session) navigate({ to: "/chat" });
    });
  }, [navigate]);

  // Two-phase silent Puter setup (sign-up then confirmation). Runs on both
  // account creation and sign-in, so returning users also get a fresh session
  // if their previous temp user expired. Never blocks -- image/video features
  // simply fall back to the free engines if this fails.
  const runPuterSetup = async () => {
    setSetupStep(1);
    setSettingUp(true);
    try {
      const w = window as any;
      const start = Date.now();
      while (!w.puter?.auth?.signIn && Date.now() - start < 6000) {
        await new Promise((r) => setTimeout(r, 150));
      }
      if (!w.puter?.auth?.signIn) return;
      // Phase 1: sign-up (creates a silent temp user if none exists yet).
      try {
        await w.puter.auth.signIn({ attempt_temp_user_creation: true });
      } catch { /* swallow -- phase 2 will re-check */ }
      // Small pause so the cover screen doesn't flicker past too fast.
      await new Promise((r) => setTimeout(r, 700));
      // Phase 2: confirmation -- verify the session is actually live, and
      // retry once if the first attempt didn't stick.
      setSetupStep(2);
      try {
        const ok = await w.puter.auth.isSignedIn?.();
        if (!ok) {
          await w.puter.auth.signIn({ attempt_temp_user_creation: true });
        }
      } catch { /* silent */ }
      await new Promise((r) => setTimeout(r, 500));
    } finally {
      setSettingUp(false);
    }
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    if (mode === "signup") {
      const rules = [
        { ok: password.length >= 8, msg: "at least 8 characters" },
        { ok: /[A-Z]/.test(password), msg: "an uppercase letter" },
        { ok: /[a-z]/.test(password), msg: "a lowercase letter" },
        { ok: /[0-9]/.test(password), msg: "a number" },
        { ok: /[^A-Za-z0-9]/.test(password), msg: "a special character" },
      ];
      const missing = rules.filter((r) => !r.ok).map((r) => r.msg);
      if (missing.length) {
        setError(`Password needs ${missing.join(", ")}.`);
        return;
      }
      if (!name.trim()) {
        setError("Please enter your display name.");
        return;
      }
    }
    setLoading(true);
    try {
      if (mode === "signup") {
        const redirectUrl = `${window.location.origin}/chat`;
        const { error } = await supabase.auth.signUp({
          email,
          password,
          options: { emailRedirectTo: redirectUrl, data: { display_name: name || email.split("@")[0] } },
        });
        if (error) throw error;
        await runPuterSetup();
      } else {
        const { error } = await supabase.auth.signInWithPassword({ email, password });
        if (error) throw error;
        await runPuterSetup();
      }
      navigate({ to: "/chat" });
    } catch (err: any) {
      setError(err.message || "Something went wrong");
    } finally {
      setLoading(false);
    }
  };

  const signInWithGoogle = async () => {
    setError(null);
    // Kick the Puter setup off before the redirect so returning Google users
    // land in /chat with a live temp session already primed.
    await runPuterSetup();
    const result = await lovable.auth.signInWithOAuth("google", {
      redirect_uri: `${window.location.origin}/chat`,
    });
    if (result.error) {
      setError(result.error instanceof Error ? result.error.message : String(result.error));
      return;
    }
    if (result.redirected) return;
    navigate({ to: "/chat" });
  };

  return (
    <div className="flex min-h-screen flex-col bg-background">
      {settingUp && (
        <div className="fixed inset-0 z-50 grid place-items-center bg-background/95 backdrop-blur-sm">
          <div className="flex w-full max-w-xs flex-col items-center gap-4 px-6 text-center">
            <img src={aemLogo} alt="Hitab" className="h-16 w-16 animate-pulse rounded-2xl" />
            <p className="text-lg font-bold tracking-tight">
              {setupStep === 1
                ? `Setting up your AI, ${name || "there"}...`
                : `Almost ready, ${name || "there"}...`}
            </p>
            <p className="text-sm text-muted-foreground">
              {setupStep === 1 ? "Creating your private AI workspace." : "Confirming access to premium models."}
            </p>
            <div className="flex items-center gap-2 text-xs text-muted-foreground">
              <span className={setupStep >= 1 ? "text-foreground" : ""}>① Sign up</span>
              <span className="opacity-40">→</span>
              <span className={setupStep >= 2 ? "text-foreground" : ""}>② Confirm</span>
            </div>
            <div className="h-1 w-full overflow-hidden rounded-full bg-muted">
              <div
                className="h-full bg-primary transition-all duration-500"
                style={{ width: setupStep === 1 ? "50%" : "100%" }}
              />
            </div>
          </div>
        </div>
      )}
      <header className="px-6 py-4">
        <Link to="/" className="inline-flex items-center gap-2">
          <img src={aemLogo} alt="Hitab" className="h-9 w-9 rounded-lg" />
          <span className="text-lg font-bold tracking-tight">Hitab</span>
        </Link>
      </header>

      <main className="mx-auto flex w-full max-w-md flex-1 flex-col justify-center px-6 py-8">
        <div className="rounded-2xl border border-border bg-card p-6 shadow-2xl">
          <h1 className="text-2xl font-bold">{mode === "signin" ? "Welcome back" : "Create your account"}</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            {mode === "signin" ? "Sign in to continue chatting." : "Start chatting in seconds."}
          </p>

          <form onSubmit={submit} className="mt-6 space-y-3">
            {mode === "signup" && (
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="Display name"
                className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-ring"
              />
            )}
            <input
              type="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@email.com"
              className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-ring"
            />
            <input
              type="password"
              required
              minLength={8}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder={mode === "signup" ? "Password (8+ chars, A-a-1-!)" : "Password"}
              className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-ring"
            />
            {error && <p className="text-sm text-destructive">{error}</p>}
            <button
              type="submit"
              disabled={loading}
              className="w-full rounded-md bg-primary py-2 text-sm font-semibold text-primary-foreground hover:opacity-90 disabled:opacity-50"
            >
              {loading ? "Please wait…" : mode === "signin" ? "Sign in" : "Create account"}
            </button>
          </form>

          <div className="my-4 flex items-center gap-2 text-xs text-muted-foreground">
            <div className="h-px flex-1 bg-border" /> or <div className="h-px flex-1 bg-border" />
          </div>
          <button
            onClick={signInWithGoogle}
            type="button"
            className="flex w-full items-center justify-center gap-2 rounded-md border border-input bg-background py-2 text-sm font-medium hover:bg-accent"
          >
            <svg className="h-4 w-4" viewBox="0 0 24 24"><path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 0 1-2.2 3.32v2.76h3.56c2.08-1.92 3.28-4.74 3.28-8.09z"/><path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.56-2.76c-.99.66-2.25 1.06-3.72 1.06-2.86 0-5.28-1.93-6.15-4.53H2.18v2.84A11 11 0 0 0 12 23z"/><path fill="#FBBC05" d="M5.85 14.11A6.6 6.6 0 0 1 5.5 12c0-.73.13-1.44.35-2.11V7.05H2.18A11 11 0 0 0 1 12c0 1.77.42 3.45 1.18 4.95l3.67-2.84z"/><path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1A11 11 0 0 0 2.18 7.05l3.67 2.84C6.72 7.31 9.14 5.38 12 5.38z"/></svg>
            Continue with Google
          </button>

          <button
            onClick={() => setMode(mode === "signin" ? "signup" : "signin")}
            className="mt-4 w-full text-center text-sm text-muted-foreground hover:text-foreground"
          >
            {mode === "signin" ? "Don't have an account? Sign up" : "Already have an account? Sign in"}
          </button>
        </div>
      </main>
      <Footer />
    </div>
  );
}
