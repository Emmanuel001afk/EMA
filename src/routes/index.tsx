import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { useEffect } from "react";
import { supabase } from "@/integrations/supabase/client";
import { Footer } from "@/components/Footer";
import aemLogo from "@/assets/aem-logo.png";

export const Route = createFileRoute("/")({
  component: Landing,
  head: () => ({
    meta: [
      { title: "HITAB — Hi There AI Buddy" },
      { name: "description", content: "A private AI workspace. Chat, code, generate, translate — one continuous stream." },
    ],
  }),
});

function Landing() {
  const navigate = useNavigate();

  useEffect(() => {
    supabase.auth.getSession().then(({ data }) => {
      if (data.session) navigate({ to: "/chat" });
    });
  }, [navigate]);

  return (
    <div className="min-h-screen w-full bg-background text-foreground flex flex-col">
      {/* Nav */}
      <header className="w-full border-b border-border">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-6 py-4">
          <Link to="/" className="flex items-center gap-2.5">
            <img src={aemLogo} alt="Hitab" width={32} height={32} className="h-8 w-8 object-contain" />
            <span className="text-lg font-semibold tracking-tight">Hitab</span>
          </Link>
          <nav className="hidden md:flex gap-8 text-sm text-muted-foreground">
            <a href="#capabilities" className="hover:text-foreground transition-colors">Capabilities</a>
            <a href="#principles" className="hover:text-foreground transition-colors">Principles</a>
            <a href="#pricing" className="hover:text-foreground transition-colors">Pricing</a>
          </nav>
          <div className="flex items-center gap-3">
            <Link to="/auth" className="text-sm text-muted-foreground hover:text-foreground">Sign in</Link>
            <Link to="/auth" className="rounded-full bg-foreground text-background px-4 py-2 text-sm font-medium hover:opacity-90 transition-opacity">
              Get started
            </Link>
          </div>
        </div>
      </header>

      <main className="flex-1">
        {/* Hero */}
        <section className="mx-auto max-w-6xl px-6 pt-20 pb-24 md:pt-32 md:pb-32">
          <div className="max-w-3xl">
            <div className="inline-flex items-center gap-2 rounded-full border border-border px-3 py-1 text-xs text-muted-foreground mb-8">
              <span className="h-1.5 w-1.5 rounded-full bg-accent" />
              A new kind of AI workspace
            </div>
            <h1 className="font-serif font-bold text-7xl sm:text-8xl md:text-[10rem] leading-[0.9] tracking-[-0.04em] mb-4">
              HITAB
            </h1>
            <p className="text-base md:text-lg text-muted-foreground/80 tracking-wide mb-10">
              Hi There AI Buddy.
            </p>
            <p className="text-lg md:text-xl leading-relaxed text-muted-foreground max-w-xl mb-10">
              One private workspace. Every modality. Hitab reads your files,
              writes your code, generates images, and speaks 40+ languages — presented as one voice.
            </p>
            <div className="flex flex-wrap gap-3">
              <Link to="/auth" className="rounded-full bg-foreground text-background px-6 py-3 text-sm font-medium hover:opacity-90 transition-opacity">
                Start chatting — free
              </Link>
              <a href="#capabilities" className="rounded-full border border-border px-6 py-3 text-sm font-medium hover:bg-muted transition-colors">
                See what it does
              </a>
            </div>
          </div>
        </section>

        {/* Big mark */}
        <section className="border-y border-border bg-muted/30">
          <div className="mx-auto max-w-6xl px-6 py-16 md:py-24 grid md:grid-cols-2 gap-12 items-center">
            <div className="flex justify-center md:justify-start">
              <img src={aemLogo} alt="" className="h-56 w-56 md:h-72 md:w-72 object-contain" />
            </div>
            <div>
              <p className="text-xs uppercase tracking-[0.25em] text-muted-foreground mb-4">The mark</p>
              <h2 className="font-serif text-4xl md:text-5xl italic mb-6">One symbol. Every answer.</h2>
              <p className="text-muted-foreground leading-relaxed">
                Hitab doesn&rsquo;t front for a single model. When you ask something, the best available
                engine responds — routed silently, presented as one voice. You get the answer, never
                the plumbing.
              </p>
            </div>
          </div>
        </section>

        {/* Capabilities */}
        <section id="capabilities" className="mx-auto max-w-6xl px-6 py-20 md:py-28">
          <p className="text-xs uppercase tracking-[0.25em] text-muted-foreground mb-4">Capabilities</p>
          <h2 className="font-serif text-4xl md:text-5xl mb-14 max-w-2xl">Everything you&rsquo;d open five apps for — in one.</h2>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-x-10 gap-y-14">
            {[
              { n: "01", title: "Multi-engine chat", body: "Automatic routing across a private ensemble of top-tier engines. Failover is invisible; the conversation never breaks." },
              { n: "02", title: "Files & vision", body: "Drop PDFs, ZIPs, docs, code, images. Hitab reads the bytes, not a summary — and cites exactly what it saw." },
              { n: "03", title: "Image generation", body: "Generate, edit and iterate images inline. Aspect ratios, quality tiers, variants — no separate tool." },
              { n: "04", title: "Voice & speech", body: "Speak to it, listen back. Live voice mode with tone controls, in every language it writes." },
              { n: "05", title: "Web-aware", body: "Optional live search grounds answers in current sources, with inline citations you can click." },
              { n: "06", title: "Private by default", body: "Your workspace, your data, your rules. Row-level security. Your keys, your control." },
            ].map((c) => (
              <article key={c.n}>
                <div className="font-mono text-xs text-muted-foreground mb-3 tracking-[0.2em]">{c.n}</div>
                <h3 className="text-xl font-semibold mb-2 tracking-tight">{c.title}</h3>
                <p className="text-sm leading-relaxed text-muted-foreground">{c.body}</p>
              </article>
            ))}
          </div>
        </section>

        {/* Principles */}
        <section id="principles" className="border-t border-border">
          <div className="mx-auto max-w-6xl px-6 py-20 md:py-28">
            <p className="text-xs uppercase tracking-[0.25em] text-muted-foreground mb-4">Principles</p>
            <h2 className="font-serif text-4xl md:text-5xl mb-14 max-w-2xl">Built on three commitments.</h2>
            <div className="grid grid-cols-1 md:grid-cols-3 divide-y md:divide-y-0 md:divide-x divide-border">
              {[
                { title: "Continuity", body: "No dead ends. When one model can&rsquo;t answer, another does — silently, in the same turn." },
                { title: "Neutrality", body: "You&rsquo;re never told which model spoke. Hitab is the interface; the ensemble is a private detail." },
                { title: "Ownership", body: "Your data lives in your database. Your keys are your keys. Nothing is rented." },
              ].map((p, i) => (
                <div key={p.title} className={"py-6 md:py-0 " + (i === 0 ? "md:pr-8" : i === 1 ? "md:px-8" : "md:pl-8")}>
                  <div className="font-mono text-xs text-accent mb-3 tracking-[0.2em]">— {String(i + 1).padStart(2, "0")}</div>
                  <h3 className="font-serif text-2xl italic mb-3">{p.title}</h3>
                  <p className="text-sm leading-relaxed text-muted-foreground" dangerouslySetInnerHTML={{ __html: p.body }} />
                </div>
              ))}
            </div>
          </div>
        </section>

        {/* CTA */}
        <section id="pricing" className="border-t border-border bg-foreground text-background">
          <div className="mx-auto max-w-6xl px-6 py-20 md:py-28 text-center">
            <h2 className="font-serif text-4xl md:text-6xl mb-6 tracking-[-0.02em]">
              Open the workspace.
            </h2>
            <p className="text-lg text-background/70 max-w-xl mx-auto mb-10">
              Free to start. No credit card. Sign in and everything is already wired.
            </p>
            <Link to="/auth" className="inline-block rounded-full bg-background text-foreground px-8 py-4 text-sm font-medium hover:opacity-90 transition-opacity">
              Start chatting →
            </Link>
          </div>
        </section>
      </main>

      <Footer />
    </div>
  );
}
