import { createFileRoute, Link } from "@tanstack/react-router";
import { Footer } from "@/components/Footer";
import { ArrowLeft } from "lucide-react";

export const Route = createFileRoute("/faq")({ component: FAQ });

const items = [
  { q: "How does Hitab work?", a: "Type your question, get an AI response. Your chat history is saved automatically and you can revisit any conversation." },
  { q: "How do I subscribe?", a: "Open Settings → Subscription and choose Basic (₦2,000/month) or Premium (₦5,000/month). Payments are handled securely by Paystack." },
  { q: "What's the difference between plans?", a: "Free: 20 messages/day. Basic: unlimited messages. Premium: unlimited messages plus file & image uploads for the AI to analyze." },
  { q: "How do I upload files or images?", a: "On the Premium plan, click the paperclip icon in the chat composer to upload PDF, Word, Excel, or image files. On mobile you can also use your camera." },
  { q: "How do I change the language?", a: "Open Settings → Language and choose your preferred language. The AI will respond in that language." },
  { q: "Can I translate a response?", a: "Yes — every AI message has a translate button that translates instantly to your selected language." },
  { q: "How do I switch dark/light mode?", a: "Open Settings → Appearance to toggle between dark and light themes." },
  { q: "How do I contact support?", a: "Open the Contact page from the footer or your profile menu and submit a message." },
];

function FAQ() {
  return (
    <div className="flex min-h-screen flex-col bg-background">
      <header className="border-b border-border px-4 py-3">
        <Link to="/" className="inline-flex items-center gap-2 text-sm text-muted-foreground hover:text-foreground">
          <ArrowLeft className="h-4 w-4" /> Home
        </Link>
      </header>
      <main className="mx-auto w-full max-w-2xl flex-1 px-6 py-10">
        <h1 className="text-3xl font-bold">FAQ</h1>
        <p className="mt-1 text-sm text-muted-foreground">Common questions about Hitab.</p>
        <div className="mt-6 space-y-3">
          {items.map((it) => (
            <details key={it.q} className="group rounded-xl border border-border bg-card p-4 open:shadow-md">
              <summary className="cursor-pointer list-none text-sm font-semibold">
                {it.q}
              </summary>
              <p className="mt-2 text-sm text-muted-foreground">{it.a}</p>
            </details>
          ))}
        </div>
      </main>
      <Footer />
    </div>
  );
}
