import { createFileRoute, Link } from "@tanstack/react-router";
import { Footer } from "@/components/Footer";
import { ArrowLeft, Send } from "lucide-react";
import { useState } from "react";
import { supabase } from "@/integrations/supabase/client";

export const Route = createFileRoute("/contact")({ component: Contact });

function Contact() {
  const [name, setName] = useState("");
  const [message, setMessage] = useState("");
  const [sent, setSent] = useState(false);
  const [sending, setSending] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!message.trim()) return;
    setSending(true);
    const { data: s } = await supabase.auth.getSession();
    const userId = s.session?.user.id;
    const email = s.session?.user.email;
    await supabase.from("support_tickets").insert({
      user_id: userId, name: name || null, email: email || null, message,
    });
    if (userId) {
      await supabase.from("notifications").insert({
        user_id: userId,
        title: "Support request received",
        body: `Thanks${name ? ` ${name}` : ""}! Our team will get back to you soon.`,
        kind: "support",
      });
    }
    setSent(true); setSending(false); setMessage("");
  };

  return (
    <div className="flex min-h-screen flex-col bg-background">
      <header className="border-b border-border px-4 py-3">
        <Link to="/" className="inline-flex items-center gap-2 text-sm text-muted-foreground hover:text-foreground">
          <ArrowLeft className="h-4 w-4" /> Home
        </Link>
      </header>
      <main className="mx-auto w-full max-w-xl flex-1 px-6 py-10">
        <h1 className="text-3xl font-bold">Contact us</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Send us a message and our support team will get back to you.
        </p>
        {sent && (
          <div className="mt-4 rounded-lg border border-primary/30 bg-primary/10 p-3 text-sm">
            ✓ Your message was sent. We'll be in touch.
          </div>
        )}
        <form onSubmit={submit} className="mt-6 space-y-3 rounded-xl border border-border bg-card p-5">
          <input
            value={name} onChange={(e) => setName(e.target.value)}
            placeholder="Your name (optional)"
            className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-ring"
          />
          <textarea
            required value={message} onChange={(e) => setMessage(e.target.value)}
            placeholder="How can we help?" rows={6}
            className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-ring"
          />
          <button disabled={sending} type="submit"
            className="inline-flex items-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-semibold text-primary-foreground hover:opacity-90 disabled:opacity-50">
            <Send className="h-4 w-4" /> {sending ? "Sending…" : "Send message"}
          </button>
        </form>
      </main>
      <Footer />
    </div>
  );
}