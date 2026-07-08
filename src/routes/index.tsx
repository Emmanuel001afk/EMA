import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { useEffect } from "react";
import { supabase } from "@/integrations/supabase/client";
import { Footer } from "@/components/Footer";

export const Route = createFileRoute("/")({
  component: Landing,
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
      <div className="flex-1 w-full flex flex-col p-6 md:p-12 overflow-hidden">
        {/* Header */}
        <header className="w-full flex flex-wrap justify-between items-end gap-4 border-b border-border pb-4 mb-12 md:mb-16">
          <div className="font-serif text-3xl italic tracking-tight">Hitab</div>
          <nav className="hidden md:flex gap-8 text-[11px] uppercase tracking-[0.25em] font-medium text-muted-foreground">
            <a href="#engine" className="hover:text-primary transition-colors">The Engine</a>
            <a href="#dispatch" className="hover:text-primary transition-colors">Dispatch</a>
            <a href="#journal" className="hover:text-primary transition-colors">Journal</a>
          </nav>
          <div className="flex gap-4 md:gap-6 items-center">
            <Link to="/auth" className="text-[11px] uppercase tracking-[0.25em] hover:opacity-70 transition-opacity">
              Sign in
            </Link>
            <Link
              to="/auth"
              className="bg-primary text-primary-foreground px-5 py-2.5 text-[11px] uppercase tracking-[0.25em] font-bold hover:bg-accent transition-all"
            >
              Start Chatting
            </Link>
          </div>
        </header>

        {/* Hero */}
        <main className="grid grid-cols-1 lg:grid-cols-12 gap-10 items-start">
          <div className="lg:col-span-8">
            <span className="text-primary font-mono text-xs md:text-sm mb-4 block tracking-[0.25em] italic">
              Issue No. 002 — Intelligent Infinity
            </span>
            <h1 className="font-serif text-6xl sm:text-7xl md:text-[9rem] lg:text-[10rem] leading-[0.85] tracking-[-0.04em] mb-10">
              AI that <br />
              <span className="italic text-primary">never stops.</span>
            </h1>

            <div className="max-w-xl border-l border-border pl-6 md:pl-8">
              <p className="text-base md:text-xl leading-relaxed font-light mb-8 text-foreground/80">
                A continuous stream of cognition. Hitab rewrites the paradigm of interaction —
                a relentless, evolving intelligence built for the modern architect of thought.
                Chat, generate, translate, read files, ship faster.
              </p>
              <div className="flex flex-wrap gap-3">
                <Link
                  to="/auth"
                  className="border border-primary text-primary px-6 md:px-8 py-3 md:py-4 text-xs uppercase tracking-[0.25em] font-bold hover:bg-primary hover:text-primary-foreground transition-all"
                >
                  Explore the Model
                </Link>
                <a
                  href="#engine"
                  className="px-6 md:px-8 py-3 md:py-4 text-xs uppercase tracking-[0.25em] font-bold text-muted-foreground hover:text-foreground transition-colors"
                >
                  How it works →
                </a>
              </div>
            </div>
          </div>

          {/* Sidebar */}
          <aside id="dispatch" className="lg:col-span-4 mt-8 lg:mt-0 flex flex-col gap-6">
            <div className="bg-card p-6 md:p-8 relative overflow-hidden">
              <div className="absolute top-0 right-0 p-4 font-mono text-primary text-xs">01</div>
              <h3 className="font-serif text-2xl md:text-3xl mb-4 italic">Non-stop Synthesis</h3>
              <p className="text-sm leading-relaxed text-muted-foreground mb-6">
                Unlike static assistants, Hitab processes context in a perpetual loop —
                your creative momentum is never broken by latency or memory decay.
              </p>
              <div className="w-full h-px bg-primary/20 mb-6" />
              <div className="flex justify-between items-center text-[10px] uppercase tracking-[0.25em]">
                <span className="text-muted-foreground">Neural Latency</span>
                <span className="text-primary">0.02ms</span>
              </div>
            </div>

            <div className="border border-border p-6 md:p-8">
              <div className="flex gap-2 items-center mb-5">
                <div className="w-2 h-2 rounded-full bg-primary animate-pulse" />
                <span className="text-[10px] uppercase tracking-[0.25em] font-bold">Live stream active</span>
              </div>
              <blockquote className="font-serif text-xl md:text-2xl italic leading-tight">
                &ldquo;The most profound technologies are those that disappear into the fabric of everyday life.&rdquo;
              </blockquote>
              <cite className="not-italic block mt-4 text-[10px] uppercase tracking-[0.3em] text-primary">
                — Weiser&rsquo;s Legacy
              </cite>
            </div>
          </aside>
        </main>

        {/* Engine bars */}
        <section id="engine" className="mt-20 md:mt-28 pt-10 grid grid-cols-2 md:grid-cols-4 gap-6 border-t border-border">
          {[
            { n: "01", label: "Stability", pct: "75%" },
            { n: "02", label: "Logic", pct: "100%" },
            { n: "03", label: "Creativity", pct: "50%" },
            { n: "04", label: "Speed", pct: "90%" },
          ].map((m) => (
            <div key={m.n} className="flex flex-col gap-3">
              <span className="text-[10px] text-primary uppercase font-bold tracking-[0.2em]">
                {m.n} {m.label}
              </span>
              <div className="h-1 bg-secondary w-full">
                <div className="h-full bg-primary" style={{ width: m.pct }} />
              </div>
            </div>
          ))}
        </section>

        {/* Journal / feature grid */}
        <section id="journal" className="mt-20 md:mt-28 grid grid-cols-1 md:grid-cols-3 gap-8 md:gap-12">
          {[
            {
              n: "I.",
              title: "Multi-model",
              body: "Route to GPT, Gemini, Claude, Groq, Mistral — automatic failover keeps the answer flowing.",
            },
            {
              n: "II.",
              title: "Files & vision",
              body: "Drop PDFs, docs, code, images. Hitab reads bytes, not summaries — and cites what it saw.",
            },
            {
              n: "III.",
              title: "Voice & tongues",
              body: "Native voice output, 40+ languages, tone controls. Speak in Yoruba, ship in English.",
            },
          ].map((c) => (
            <article key={c.n} className="border-t border-border pt-6">
              <div className="font-mono text-primary text-xs mb-3 tracking-[0.25em]">{c.n}</div>
              <h4 className="font-serif text-2xl md:text-3xl italic mb-3">{c.title}</h4>
              <p className="text-sm leading-relaxed text-muted-foreground">{c.body}</p>
            </article>
          ))}
        </section>
      </div>
      <Footer />
    </div>
  );
}
