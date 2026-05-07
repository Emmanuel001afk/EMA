import { createFileRoute, useNavigate, Link } from "@tanstack/react-router";
import { useEffect, useRef, useState } from "react";
import { supabase } from "@/integrations/supabase/client";
import { Sparkles, Send, Plus, Copy, LogOut, Shield, Check } from "lucide-react";
import { Footer } from "@/components/Footer";

export const Route = createFileRoute("/chat")({
  component: ChatPage,
});

type Msg = {
  id: string;
  role: "user" | "assistant";
  content: string;
  created_at: string;
  provider?: string | null;
};
type Conv = { id: string; title: string; updated_at: string };

function ChatPage() {
  const navigate = useNavigate();
  const [userId, setUserId] = useState<string | null>(null);
  const [isAdmin, setIsAdmin] = useState(false);
  const [conversations, setConversations] = useState<Conv[]>([]);
  const [activeConvId, setActiveConvId] = useState<string | null>(null);
  const [messages, setMessages] = useState<Msg[]>([]);
  const [input, setInput] = useState("");
  const [sending, setSending] = useState(false);
  const [activeProvider, setActiveProvider] = useState<string>("gemini");
  const [lastProvider, setLastProvider] = useState<string | null>(null);
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [typing, setTyping] = useState("");
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    supabase.auth.getSession().then(({ data }) => {
      if (!data.session) {
        navigate({ to: "/auth" });
        return;
      }
      setUserId(data.session.user.id);
    });
    const { data: sub } = supabase.auth.onAuthStateChange((_e, session) => {
      if (!session) navigate({ to: "/auth" });
    });
    return () => sub.subscription.unsubscribe();
  }, [navigate]);

  useEffect(() => {
    if (!userId) return;
    (async () => {
      const { data: roles } = await supabase
        .from("user_roles")
        .select("role")
        .eq("user_id", userId);
      setIsAdmin(!!roles?.some((r) => r.role === "admin"));

      const { data: setting } = await supabase
        .from("app_settings")
        .select("value")
        .eq("key", "active_provider")
        .maybeSingle();
      if (setting?.value) setActiveProvider(setting.value as string);

      await loadConversations();
    })();
  }, [userId]);

  const loadConversations = async () => {
    const { data } = await supabase
      .from("conversations")
      .select("id,title,updated_at")
      .order("updated_at", { ascending: false });
    setConversations(data ?? []);
    if (data && data.length && !activeConvId) {
      setActiveConvId(data[0].id);
    }
  };

  useEffect(() => {
    if (!activeConvId) {
      setMessages([]);
      return;
    }
    (async () => {
      const { data } = await supabase
        .from("messages")
        .select("*")
        .eq("conversation_id", activeConvId)
        .order("created_at", { ascending: true });
      setMessages((data ?? []) as Msg[]);
    })();
  }, [activeConvId]);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: "smooth" });
  }, [messages, typing]);

  const newChat = async () => {
    if (!userId) return;
    const { data, error } = await supabase
      .from("conversations")
      .insert({ user_id: userId, title: "New Chat" })
      .select()
      .single();
    if (!error && data) {
      setConversations((c) => [data as Conv, ...c]);
      setActiveConvId(data.id);
      setMessages([]);
    }
  };

  const send = async () => {
    if (!input.trim() || !userId || sending) return;
    let convId = activeConvId;
    if (!convId) {
      const { data } = await supabase
        .from("conversations")
        .insert({ user_id: userId, title: input.slice(0, 40) })
        .select()
        .single();
      if (!data) return;
      convId = data.id;
      setActiveConvId(convId);
      setConversations((c) => [data as Conv, ...c]);
    }
    const text = input.trim();
    setInput("");
    setSending(true);

    const userMsg: Msg = {
      id: crypto.randomUUID(),
      role: "user",
      content: text,
      created_at: new Date().toISOString(),
    };
    setMessages((m) => [...m, userMsg]);

    await supabase.from("messages").insert({
      conversation_id: convId,
      user_id: userId,
      role: "user",
      content: text,
    });

    // Update title if first message
    if (messages.length === 0) {
      await supabase.from("conversations").update({ title: text.slice(0, 40) }).eq("id", convId);
      setConversations((cs) => cs.map((c) => (c.id === convId ? { ...c, title: text.slice(0, 40) } : c)));
    }

    try {
      const history = [...messages, userMsg].map((m) => ({ role: m.role, content: m.content }));
      const { data, error } = await supabase.functions.invoke("chat", {
        body: { messages: history },
      });
      if (error) throw error;
      if (data?.error) throw new Error(data.error);
      const reply: string = data.content || "(no response)";
      const provider: string = data.provider;
      setLastProvider(provider);

      // Typewriter effect
      const aid = crypto.randomUUID();
      let i = 0;
      setTyping("");
      await new Promise<void>((resolve) => {
        const iv = setInterval(() => {
          i += Math.max(2, Math.floor(reply.length / 80));
          setTyping(reply.slice(0, i));
          if (i >= reply.length) {
            clearInterval(iv);
            resolve();
          }
        }, 20);
      });
      setTyping("");

      const aMsg: Msg = {
        id: aid,
        role: "assistant",
        content: reply,
        created_at: new Date().toISOString(),
        provider,
      };
      setMessages((m) => [...m, aMsg]);
      await supabase.from("messages").insert({
        conversation_id: convId,
        user_id: userId,
        role: "assistant",
        content: reply,
        provider,
      });
      await supabase.from("conversations").update({ updated_at: new Date().toISOString() }).eq("id", convId);
    } catch (e: any) {
      setMessages((m) => [
        ...m,
        {
          id: crypto.randomUUID(),
          role: "assistant",
          content: `⚠️ ${e.message || "Failed to get response"}`,
          created_at: new Date().toISOString(),
        },
      ]);
    } finally {
      setSending(false);
    }
  };

  const copy = async (id: string, text: string) => {
    await navigator.clipboard.writeText(text);
    setCopiedId(id);
    setTimeout(() => setCopiedId(null), 1500);
  };

  const signOut = async () => {
    await supabase.auth.signOut();
    navigate({ to: "/" });
  };

  const fmtTime = (s: string) =>
    new Date(s).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });

  if (!userId) return null;

  return (
    <div className="flex h-screen flex-col bg-background">
      {/* Header */}
      <header className="flex items-center justify-between border-b border-border px-4 py-3">
        <Link to="/" className="flex items-center gap-2">
          <div className="grid h-8 w-8 place-items-center rounded-lg bg-gradient-to-br from-primary to-accent text-primary-foreground">
            <Sparkles className="h-4 w-4" />
          </div>
          <span className="text-base font-bold tracking-tight">HI THERE</span>
        </Link>
        <div className="flex items-center gap-2">
          <span
            title="Active AI provider"
            className="hidden items-center gap-1.5 rounded-full border border-border bg-card px-2.5 py-1 text-xs sm:inline-flex"
          >
            <span className="h-2 w-2 rounded-full bg-primary" />
            {lastProvider ? `Using: ${lastProvider}` : `Active: ${activeProvider}`}
          </span>
          {isAdmin && (
            <Link
              to="/admin"
              className="inline-flex items-center gap-1 rounded-md border border-border bg-card px-2.5 py-1 text-xs hover:bg-accent"
            >
              <Shield className="h-3 w-3" /> Admin
            </Link>
          )}
          <button
            onClick={signOut}
            className="inline-flex items-center gap-1 rounded-md border border-border bg-card px-2.5 py-1 text-xs hover:bg-accent"
          >
            <LogOut className="h-3 w-3" /> Sign out
          </button>
        </div>
      </header>

      <div className="flex flex-1 overflow-hidden">
        {/* Sidebar */}
        <aside className="hidden w-64 shrink-0 flex-col border-r border-border bg-sidebar p-3 md:flex">
          <button
            onClick={newChat}
            className="mb-3 inline-flex items-center justify-center gap-2 rounded-md bg-primary px-3 py-2 text-sm font-semibold text-primary-foreground hover:opacity-90"
          >
            <Plus className="h-4 w-4" /> New Chat
          </button>
          <div className="flex-1 space-y-1 overflow-y-auto">
            {conversations.map((c) => (
              <button
                key={c.id}
                onClick={() => setActiveConvId(c.id)}
                className={`block w-full truncate rounded-md px-3 py-2 text-left text-sm transition ${
                  c.id === activeConvId
                    ? "bg-accent text-accent-foreground"
                    : "hover:bg-sidebar-accent"
                }`}
              >
                {c.title}
              </button>
            ))}
            {conversations.length === 0 && (
              <p className="px-2 py-4 text-xs text-muted-foreground">No chats yet.</p>
            )}
          </div>
        </aside>

        {/* Main */}
        <main className="flex flex-1 flex-col">
          <div ref={scrollRef} className="flex-1 overflow-y-auto px-4 py-6">
            <div className="mx-auto max-w-3xl space-y-4">
              {messages.length === 0 && !typing && (
                <div className="rounded-2xl border border-border bg-card p-8 text-center">
                  <div className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-2xl bg-gradient-to-br from-primary to-accent">
                    <Sparkles className="h-6 w-6 text-primary-foreground" />
                  </div>
                  <h2 className="text-2xl font-bold">Hi there 👋</h2>
                  <p className="mt-2 text-sm text-muted-foreground">
                    Ask me anything. I'll switch AI providers automatically if one is unavailable.
                  </p>
                  <div className="mt-6 flex md:hidden">
                    <button
                      onClick={newChat}
                      className="mx-auto inline-flex items-center gap-2 rounded-md bg-primary px-3 py-2 text-sm font-semibold text-primary-foreground"
                    >
                      <Plus className="h-4 w-4" /> New Chat
                    </button>
                  </div>
                </div>
              )}

              {messages.map((m) => (
                <div
                  key={m.id}
                  className={`flex ${m.role === "user" ? "justify-end" : "justify-start"}`}
                >
                  <div
                    className={`group max-w-[85%] rounded-2xl px-4 py-3 text-sm ${
                      m.role === "user"
                        ? "bg-primary text-primary-foreground"
                        : "border border-border bg-card"
                    }`}
                  >
                    <div className="whitespace-pre-wrap">{m.content}</div>
                    <div className="mt-1.5 flex items-center justify-between gap-3 text-[10px] opacity-70">
                      <span>
                        {fmtTime(m.created_at)}
                        {m.provider ? ` · ${m.provider}` : ""}
                      </span>
                      {m.role === "assistant" && (
                        <button
                          onClick={() => copy(m.id, m.content)}
                          className="inline-flex items-center gap-1 rounded px-1 py-0.5 hover:bg-accent"
                        >
                          {copiedId === m.id ? (
                            <>
                              <Check className="h-3 w-3" /> Copied
                            </>
                          ) : (
                            <>
                              <Copy className="h-3 w-3" /> Copy
                            </>
                          )}
                        </button>
                      )}
                    </div>
                  </div>
                </div>
              ))}

              {typing && (
                <div className="flex justify-start">
                  <div className="max-w-[85%] rounded-2xl border border-border bg-card px-4 py-3 text-sm">
                    <div className="whitespace-pre-wrap">
                      {typing}
                      <span className="ml-0.5 inline-block h-4 w-1 animate-pulse bg-foreground align-middle" />
                    </div>
                  </div>
                </div>
              )}

              {sending && !typing && (
                <div className="flex justify-start">
                  <div className="rounded-2xl border border-border bg-card px-4 py-3 text-sm text-muted-foreground">
                    <span className="inline-flex gap-1">
                      <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-foreground [animation-delay:-0.3s]" />
                      <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-foreground [animation-delay:-0.15s]" />
                      <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-foreground" />
                    </span>
                  </div>
                </div>
              )}
            </div>
          </div>

          {/* Composer */}
          <div className="border-t border-border bg-background/80 p-3 backdrop-blur">
            <div className="mx-auto flex max-w-3xl items-end gap-2">
              <textarea
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && !e.shiftKey) {
                    e.preventDefault();
                    send();
                  }
                }}
                rows={1}
                placeholder="Message HI THERE…"
                className="max-h-40 min-h-[44px] flex-1 resize-none rounded-xl border border-input bg-card px-4 py-3 text-sm outline-none focus:ring-2 focus:ring-ring"
              />
              <button
                onClick={send}
                disabled={sending || !input.trim()}
                className="grid h-11 w-11 place-items-center rounded-xl bg-primary text-primary-foreground hover:opacity-90 disabled:opacity-40"
              >
                <Send className="h-4 w-4" />
              </button>
            </div>
          </div>
          <Footer />
        </main>
      </div>
    </div>
  );
}