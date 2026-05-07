import { createFileRoute, Link } from "@tanstack/react-router";
import { Footer } from "@/components/Footer";
import { ArrowLeft } from "lucide-react";

export const Route = createFileRoute("/privacy")({ component: Privacy });

function Privacy() {
  return (
    <div className="flex min-h-screen flex-col bg-background">
      <header className="border-b border-border px-4 py-3">
        <Link to="/" className="inline-flex items-center gap-2 text-sm text-muted-foreground hover:text-foreground">
          <ArrowLeft className="h-4 w-4" /> Home
        </Link>
      </header>
      <main className="mx-auto w-full max-w-3xl flex-1 px-6 py-10">
        <h1 className="text-3xl font-bold">Privacy Policy</h1>
        <p className="mt-2 text-sm text-muted-foreground">Last updated: {new Date().toLocaleDateString()}</p>

        <section className="mt-6 space-y-4 text-sm leading-relaxed">
          <h2 className="text-lg font-semibold">What we collect</h2>
          <p>Account info (email, display name), chat messages, uploaded files, language and theme preferences, and usage metrics needed to deliver the Service.</p>

          <h2 className="text-lg font-semibold">How we use it</h2>
          <p>To generate AI responses, maintain your chat history, enforce usage limits, process payments, send announcements, and improve the Service.</p>

          <h2 className="text-lg font-semibold">AI processing</h2>
          <p>Your prompts are sent to third-party AI providers to generate responses. We do not sell your data. Providers may temporarily process content as required to return a response.</p>

          <h2 className="text-lg font-semibold">Storage and security</h2>
          <p>Data is stored on encrypted, access-controlled databases. Row-level security restricts your data to your account. Uploaded files are stored in private buckets.</p>

          <h2 className="text-lg font-semibold">Payments</h2>
          <p>Payments are processed by Paystack. We store transaction references and status, not your card details.</p>

          <h2 className="text-lg font-semibold">Your rights</h2>
          <p>You can edit your profile, change language, export by copying messages, and delete your account at any time from Settings. Account deletion permanently removes your data.</p>

          <h2 className="text-lg font-semibold">Children</h2>
          <p>HI THERE is not directed to children under 13. Do not use the Service if you are under 13.</p>

          <h2 className="text-lg font-semibold">Contact</h2>
          <p>For privacy questions, use the in-app contact form on the Contact page.</p>
        </section>
      </main>
      <Footer />
    </div>
  );
}