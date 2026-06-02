import { createFileRoute, useNavigate, Link } from "@tanstack/react-router";
import { useEffect, useMemo, useRef, useState } from "react";
import { supabase } from "@/integrations/supabase/client";
import {
  Sparkles,
  Send,
  Plus,
  Copy,
  LogOut,
  Shield,
  Check,
  Menu,
  X,
  Settings as SettingsIcon,
  HelpCircle,
  Search,
  Paperclip,
  Camera,
  Languages,
  User,
  Pencil,
  Volume2,
  VolumeX,
  Share2,
  Flag,
  Trash2,
  Download,
  ImageIcon,
  FileText,
  Film,
} from "lucide-react";
import { Footer } from "@/components/Footer";
import { NotificationBell } from "@/components/NotificationBell";
import { langLabel } from "@/lib/languages";
import type { Json } from "@/integrations/supabase/types";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import remarkMath from "remark-math";
import rehypeKatex from "rehype-katex";
import "katex/dist/katex.min.css";

export const Route = createFileRoute("/chat")({ component: ChatPage });

type Attachment = {
  name: string;
  url: string;
  type: string;
  size: number;
  path?: string;
  data?: string;
};
type Msg = {
  id: string;
  role: "user" | "assistant";
  content: string;
  created_at: string;
  provider?: string | null;
  attachments?: Attachment[];
};
type Conv = { id: string; title: string; updated_at: string };

const isImageAttachment = (a: Attachment) => a.type?.startsWith("image/");
const isVideoAttachment = (a: Attachment) => a.type?.startsWith("video/");
const isAudioAttachment = (a: Attachment) => a.type?.startsWith("audio/");
const isPdfAttachment = (a: Attachment) =>
  a.type === "application/pdf" || a.name.toLowerCase().endsWith(".pdf");
const stripLocalData = (attachments: Attachment[]): Attachment[] =>
  attachments.map(({ data: _data, ...rest }) => rest);

async function fileToBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || "").split(",")[1] || "");
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
}

function base64ToBlob(base64: string, type: string) {
  const bin = atob(base64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new Blob([bytes], { type });
}

function AttachmentPreview({ attachment }: { attachment: Attachment }) {
  const src =
    attachment.data && isImageAttachment(attachment)
      ? `data:${attachment.type};base64,${attachment.data}`
      : attachment.url;

  if (isImageAttachment(attachment)) {
    return (
      <figure className="overflow-hidden rounded-xl border border-border bg-background/70">
        <img
          src={src}
          alt={attachment.name}
          className="max-h-80 w-full object-contain"
          loading="lazy"
        />
        <figcaption className="flex items-center justify-between gap-2 px-2.5 py-1.5 text-[11px] text-muted-foreground">
          <span className="truncate">
            <ImageIcon className="mr-1 inline h-3 w-3" />
            {attachment.name}
          </span>
          <a
            href={src}
            download={attachment.name}
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-1 rounded px-1.5 py-0.5 hover:bg-accent"
          >
            <Download className="h-3 w-3" /> Download
          </a>
        </figcaption>
      </figure>
    );
  }

  if (isVideoAttachment(attachment)) {
    return (
      <div className="overflow-hidden rounded-xl border border-border bg-background/70">
        <video
          src={src}
          controls
          playsInline
          preload="metadata"
          className="max-h-80 w-full bg-background"
        />
        <div className="flex items-center justify-between gap-2 px-2.5 py-1.5 text-[11px] text-muted-foreground">
          <span className="truncate">
            <Film className="mr-1 inline h-3 w-3" />
            {attachment.name}
          </span>
          <a
            href={src}
            download={attachment.name}
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-1 rounded px-1.5 py-0.5 hover:bg-accent"
          >
            <Download className="h-3 w-3" /> Download
          </a>
        </div>
      </div>
    );
  }

  if (isAudioAttachment(attachment)) {
    return (
      <div className="rounded-xl border border-border bg-background/70 p-2">
        <div className="mb-1 flex items-center justify-between gap-2 text-[11px] text-muted-foreground">
          <span className="truncate">{attachment.name}</span>
          <a
            href={src}
            download={attachment.name}
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-1 rounded px-1.5 py-0.5 hover:bg-accent"
          >
            <Download className="h-3 w-3" /> Download
          </a>
        </div>
        <audio src={src} controls className="w-full" />
      </div>
    );
  }

  if (isPdfAttachment(attachment)) {
    return (
      <div className="overflow-hidden rounded-xl border border-border bg-background/70">
        <iframe title={attachment.name} src={src} className="h-72 w-full bg-background" />
        <div className="flex items-center justify-between gap-2 px-2.5 py-1.5 text-[11px] text-muted-foreground">
          <span className="truncate">
            <FileText className="mr-1 inline h-3 w-3" />
            {attachment.name}
          </span>
          <a
            href={src}
            download={attachment.name}
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-1 rounded px-1.5 py-0.5 hover:bg-accent"
          >
            <Download className="h-3 w-3" /> Download
          </a>
        </div>
      </div>
    );
  }

  return (
    <a
      href={src}
      target="_blank"
      rel="noreferrer"
      download={attachment.name}
      className="flex items-center justify-between gap-2 rounded-xl border border-border bg-background/70 px-3 py-2 text-xs hover:bg-accent"
    >
      <span className="truncate">
        <FileText className="mr-1 inline h-3 w-3" />
        {attachment.name}
      </span>
      <Download className="h-3 w-3 shrink-0" />
    </a>
  );
}

function ChatPage() {
  const navigate = useNavigate();
  const friendlyError = (e: unknown): string => {
    const err = e as { message?: string; error?: string };
    if (typeof navigator !== "undefined" && !navigator.onLine) {
      return "You're offline. Check your internet connection and try again.";
    }
    const raw = (err?.message || err?.error || "").toString().toLowerCase();
    if (
      raw.includes("failed to fetch") ||
      raw.includes("networkerror") ||
      raw.includes("network request failed")
    ) {
      return "Network hiccup — please check your connection and try again.";
    }
    if (
      raw.includes("429") ||
      raw.includes("rate") ||
      raw.includes("cooldown") ||
      raw.includes("limit reached")
    ) {
      return (
        err?.message || "You've hit today's free limit. Please wait a bit or upgrade your plan."
      );
    }
    if (
      raw.includes("402") ||
      raw.includes("payment") ||
      raw.includes("quota") ||
      raw.includes("insufficient")
    ) {
      return "Our AI usage is temporarily exhausted. The team has been notified — please try again shortly.";
    }
    if (raw.includes("401") || raw.includes("not authenticated") || raw.includes("unauthorized")) {
      return "Your session expired. Please sign in again.";
    }
    if (
      raw.includes("non-2xx") ||
      raw.includes("all providers failed") ||
      raw.includes("502") ||
      raw.includes("503")
    ) {
      return "The AI is taking a quick break. Please try again in a moment.";
    }
    return err?.message || "Something went wrong. Please try again.";
  };
  const [userId, setUserId] = useState<string | null>(null);
  const [isAdmin, setIsAdmin] = useState(false);
  const [language, setLanguage] = useState("en");
  const [conversations, setConversations] = useState<Conv[]>([]);
  const [activeConvId, setActiveConvId] = useState<string | null>(null);
  const [messages, setMessages] = useState<Msg[]>([]);
  const [input, setInput] = useState("");
  const [sending, setSending] = useState(false);
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [translations, setTranslations] = useState<Record<string, string>>({});
  const [translatingId, setTranslatingId] = useState<string | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editingText, setEditingText] = useState("");
  const [speakingId, setSpeakingId] = useState<string | null>(null);
  const [voiceName, setVoiceName] = useState<string>("default");
  const [typing, setTyping] = useState("");
  const [search, setSearch] = useState("");
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [pendingFiles, setPendingFiles] = useState<Attachment[]>([]);
  const [error, setError] = useState<string | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const cameraRef = useRef<HTMLInputElement>(null);

  // Renders assistant/user text as markdown (with GFM tables, math via KaTeX,
  // and inline images from data URLs returned by the image-generation flow).
  const MD = ({ text, dark }: { text: string; dark?: boolean }) => (
    <div
      className={`prose prose-sm max-w-none break-words ${dark ? "prose-invert" : "dark:prose-invert"} prose-p:my-2 prose-pre:my-2 prose-headings:my-2 prose-img:my-2 prose-img:rounded-lg`}
    >
      <ReactMarkdown
        remarkPlugins={[remarkGfm, [remarkMath, { singleDollarTextMath: false }]]}
        rehypePlugins={[rehypeKatex]}
        components={{
          a: ({ href, children, ...props }) => (
            <a
              href={href}
              target="_blank"
              rel="noreferrer"
              className="break-all underline underline-offset-2"
              {...props}
            >
              {href || children}
            </a>
          ),
          img: ({ src, alt }) =>
            src ? (
              <img
                src={src}
                alt={alt || "generated media"}
                className="max-h-96 w-full rounded-xl object-contain"
                loading="lazy"
              />
            ) : null,
        }}
      >
        {text}
      </ReactMarkdown>
    </div>
  );

  const refreshAttachmentUrls = async (rows: Msg[]): Promise<Msg[]> => {
    const refreshed = await Promise.all(
      rows.map(async (m) => {
        if (!m.attachments?.length) return m;
        const attachments = await Promise.all(
          m.attachments.map(async (a) => {
            if (!a.path || a.url?.startsWith("data:")) return a;
            const { data } = await supabase.storage
              .from("chat-uploads")
              .createSignedUrl(a.path, 60 * 60 * 24 * 7);
            return { ...a, url: data?.signedUrl || a.url };
          }),
        );
        return { ...m, attachments };
      }),
    );
    return refreshed;
  };

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
      const [{ data: roles }, { data: profile }] = await Promise.all([
        supabase.from("user_roles").select("role").eq("user_id", userId),
        supabase
          .from("profiles")
          .select("subscription_plan,language,voice_name")
          .eq("user_id", userId)
          .maybeSingle(),
      ]);
      setIsAdmin(!!roles?.some((r) => r.role === "admin"));
      if (profile) {
        setLanguage(profile.language || "en");
        setVoiceName((profile as { voice_name?: string }).voice_name || "default");
      }
      await loadConversations();
    })();
    // eslint-disable-next-line
  }, [userId]);

  const loadConversations = async () => {
    const { data } = await supabase
      .from("conversations")
      .select("id,title,updated_at")
      .order("updated_at", { ascending: false });
    setConversations(data ?? []);
    if (data && data.length && !activeConvId) setActiveConvId(data[0].id);
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
      setMessages(await refreshAttachmentUrls((data ?? []) as unknown as Msg[]));
    })();
  }, [activeConvId]);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: "smooth" });
  }, [messages, typing]);

  const filteredConvs = useMemo(() => {
    if (!search.trim()) return conversations;
    const q = search.toLowerCase();
    return conversations.filter((c) => c.title.toLowerCase().includes(q));
  }, [conversations, search]);

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
      setDrawerOpen(false);
    }
  };

  const handleFiles = async (files: FileList | null) => {
    if (!files || !userId) return;
    const uploads: Attachment[] = [];
    for (const f of Array.from(files)) {
      if (f.size > 20 * 1024 * 1024) {
        setError(`${f.name} exceeds 20MB`);
        continue;
      }
      const path = `${userId}/${Date.now()}-${f.name.replace(/[^a-zA-Z0-9._-]/g, "-")}`;
      const previewData =
        f.type.startsWith("image/") && f.size <= 6 * 1024 * 1024
          ? await fileToBase64(f)
          : undefined;
      const { error: upErr } = await supabase.storage
        .from("chat-uploads")
        .upload(path, f, { contentType: f.type, upsert: true });
      if (upErr) {
        setError(
          !navigator.onLine
            ? "You're offline. Reconnect to upload files."
            : `Couldn't upload ${f.name}. Please try again.`,
        );
        continue;
      }
      const { data: signed } = await supabase.storage
        .from("chat-uploads")
        .createSignedUrl(path, 60 * 60 * 24 * 7);
      uploads.push({
        name: f.name,
        url: signed?.signedUrl || "",
        type: f.type || "application/octet-stream",
        size: f.size,
        path,
        data: previewData,
      });
    }
    setPendingFiles((p) => [...p, ...uploads]);
  };

  const send = async () => {
    if ((!input.trim() && pendingFiles.length === 0) || !userId || sending) return;
    setError(null);
    let convId = activeConvId;
    if (!convId) {
      const { data } = await supabase
        .from("conversations")
        .insert({ user_id: userId, title: input.slice(0, 40) || "New Chat" })
        .select()
        .single();
      if (!data) return;
      convId = data.id;
      setActiveConvId(convId);
      setConversations((c) => [data as Conv, ...c]);
    }
    const text = input.trim();
    const attachments = pendingFiles;
    const storedAttachments = stripLocalData(attachments);
    setInput("");
    setPendingFiles([]);
    setSending(true);

    const userMsg: Msg = {
      id: crypto.randomUUID(),
      role: "user",
      content: text,
      created_at: new Date().toISOString(),
      attachments,
    };
    setMessages((m) => [...m, userMsg]);
    await supabase.from("messages").insert({
      id: userMsg.id,
      conversation_id: convId,
      user_id: userId,
      role: "user",
      content: text,
      attachments: storedAttachments as unknown as Json,
    });

    if (messages.length === 0) {
      const title = (text || attachments[0]?.name || "New Chat").slice(0, 40);
      await supabase.from("conversations").update({ title }).eq("id", convId);
      setConversations((cs) => cs.map((c) => (c.id === convId ? { ...c, title } : c)));
    }

    try {
      const hasImages = attachments.some(isImageAttachment);
      const attachContext = attachments.length
        ? `\n\n[User attached ${attachments.length} file(s): ${attachments.map((a) => `${a.name} (${a.type})`).join(", ")}. ${hasImages ? "The image data is attached; inspect it directly and describe what you see." : "Use the visible file details when relevant."}]`
        : "";
      const history = [...messages, { ...userMsg, content: text + attachContext }].map((m) => ({
        role: m.role,
        content: m.content,
      }));
      // Detect explicit image-generation intent on the client too, so the
      // server doesn't have to guess. Triggers: leading "/image ", or natural
      // phrasing like "generate/create/draw/make an image/picture/photo of …".
      const lowered = text.toLowerCase().trim();
      const wantsImage =
        lowered.startsWith("/image ") ||
        /\b(generate|create|draw|make|design|render|produce|paint|sketch|show me)\b.*\b(image|picture|photo|pic|logo|illustration|wallpaper|art|drawing|painting|portrait|poster|flyer|banner|avatar)\b/i.test(
          lowered,
        ) ||
        /^\s*(draw|paint|sketch|illustrate|render)\b/i.test(text);
      const cleanedText = lowered.startsWith("/image ") ? text.replace(/^\/image\s+/i, "") : text;
      if (wantsImage) {
        history[history.length - 1] = { role: "user", content: cleanedText };
      }
      const { data, error } = await supabase.functions.invoke("chat", {
        body: {
          messages: history,
          language,
          mode: wantsImage ? "image" : undefined,
          attachments: attachments.map((a) => ({
            url: a.url,
            type: a.type,
            name: a.name,
            data: a.data,
          })),
        },
      });
      if (error) throw error;
      if (data?.error) throw new Error(data.error);
      let assistantAttachments: Attachment[] = [];
      let storedAssistantAttachments: Attachment[] = [];
      const reply: string = data.image_b64
        ? "Here’s the image I created:"
        : data.content || "(no response)";
      const provider: string = data.provider;

      if (data.image_b64) {
        const filename = `generated-${Date.now()}.png`;
        const path = `${userId}/generated/${filename}`;
        try {
          const blob = base64ToBlob(data.image_b64, "image/png");
          const { error: imgErr } = await supabase.storage
            .from("chat-uploads")
            .upload(path, blob, { contentType: "image/png", upsert: true });
          if (imgErr) throw imgErr;
          const { data: signed } = await supabase.storage
            .from("chat-uploads")
            .createSignedUrl(path, 60 * 60 * 24 * 7);
          assistantAttachments = [
            {
              name: filename,
              type: "image/png",
              size: blob.size,
              path,
              url: signed?.signedUrl || "",
              data: data.image_b64,
            },
          ];
          storedAssistantAttachments = stripLocalData(assistantAttachments);
        } catch {
          assistantAttachments = [
            {
              name: filename,
              type: "image/png",
              size: data.image_b64.length,
              url: `data:image/png;base64,${data.image_b64}`,
              data: data.image_b64,
            },
          ];
          storedAssistantAttachments = assistantAttachments;
        }
      }

      const aid = crypto.randomUUID();
      // Skip typewriter for image replies (don't reveal base64 char-by-char).
      if (!data.image_b64) {
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
      }

      const aMsg: Msg = {
        id: aid,
        role: "assistant",
        content: reply,
        created_at: new Date().toISOString(),
        provider,
        attachments: assistantAttachments,
      };
      setMessages((m) => [...m, aMsg]);
      await supabase.from("messages").insert({
        id: aid,
        conversation_id: convId,
        user_id: userId,
        role: "assistant",
        content: reply,
        provider,
        attachments: storedAssistantAttachments as unknown as Json,
      });
      await supabase
        .from("conversations")
        .update({ updated_at: new Date().toISOString() })
        .eq("id", convId);
    } catch (e: unknown) {
      const friendly = friendlyError(e);
      setError(friendly);
      setMessages((m) => [
        ...m,
        {
          id: crypto.randomUUID(),
          role: "assistant",
          content: `⚠️ ${friendly}`,
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

  const deleteMessage = async (m: Msg) => {
    if (!window.confirm("Delete this message?")) return;
    await supabase.from("messages").delete().eq("id", m.id);
    setMessages((ms) => ms.filter((x) => x.id !== m.id));
  };

  const deleteConversation = async (id: string) => {
    if (!window.confirm("Delete this whole chat?")) return;
    await supabase.from("conversations").delete().eq("id", id);
    setConversations((cs) => cs.filter((c) => c.id !== id));
    if (activeConvId === id) {
      const next = conversations.find((c) => c.id !== id)?.id ?? null;
      setActiveConvId(next);
      if (!next) setMessages([]);
    }
  };

  const downloadConversation = (id = activeConvId) => {
    if (!id) return;
    const conv = conversations.find((c) => c.id === id);
    const rows = id === activeConvId ? messages : [];
    const body = rows.length
      ? rows
          .map((m) => {
            const files = m.attachments?.length
              ? `\nFiles:\n${m.attachments.map((a) => `- ${a.name}: ${a.url}`).join("\n")}`
              : "";
            return `## ${m.role === "assistant" ? "HI THERE" : "You"} — ${new Date(m.created_at).toLocaleString()}\n\n${m.content}${files}`;
          })
          .join("\n\n---\n\n")
      : `Open this chat first to export its full messages.`;
    const blob = new Blob([`# ${conv?.title || "HI THERE chat"}\n\n${body}`], {
      type: "text/markdown",
    });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `${(conv?.title || "hi-there-chat").replace(/[^a-z0-9]+/gi, "-").slice(0, 50)}.md`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const startEdit = (m: Msg) => {
    setEditingId(m.id);
    setEditingText(m.content);
  };
  const saveEdit = async (m: Msg) => {
    const text = editingText.trim();
    if (!text || text === m.content) {
      setEditingId(null);
      return;
    }
    await supabase.from("messages").update({ content: text }).eq("id", m.id);
    setMessages((ms) => ms.map((x) => (x.id === m.id ? { ...x, content: text } : x)));
    setEditingId(null);
  };

  const speak = (m: Msg) => {
    if (typeof window === "undefined" || !window.speechSynthesis) {
      setError("Speech not supported in this browser");
      return;
    }
    if (speakingId === m.id) {
      window.speechSynthesis.cancel();
      setSpeakingId(null);
      return;
    }
    window.speechSynthesis.cancel();
    const u = new SpeechSynthesisUtterance(m.content);
    const voices = window.speechSynthesis.getVoices();
    const wantFemale = voiceName.toLowerCase().includes("female");
    const wantMale = voiceName.toLowerCase().includes("male");
    const pick =
      voices.find((v) => v.name.toLowerCase().includes(voiceName.toLowerCase())) ??
      voices.find((v) => wantFemale && /female|samantha|victoria|zira|karen|tessa/i.test(v.name)) ??
      voices.find((v) => wantMale && /male|daniel|alex|david|fred/i.test(v.name)) ??
      voices[0];
    if (pick) u.voice = pick;
    u.onend = () => setSpeakingId(null);
    u.onerror = () => setSpeakingId(null);
    setSpeakingId(m.id);
    window.speechSynthesis.speak(u);
  };

  const share = async (m: Msg) => {
    const media = m.attachments?.[0]?.url ? `\n\nMedia: ${m.attachments[0].url}` : "";
    const text = `From HI THERE:\n\n${m.content}${media}`;
    const nav = navigator as Navigator & { share?: (data: ShareData) => Promise<void> };
    if (nav.share) {
      try {
        await nav.share({ title: "HI THERE", text });
        return;
      } catch {
        // Fall back to copying when native sharing is cancelled or unavailable.
      }
    }
    await navigator.clipboard.writeText(text);
    setCopiedId(m.id);
    setTimeout(() => setCopiedId(null), 1500);
  };

  const report = async (m: Msg) => {
    const reason = window.prompt("Why are you reporting this response? (optional)");
    if (reason === null) return;
    await supabase.from("reports").insert({
      user_id: userId!,
      message_id: m.id,
      conversation_id: activeConvId,
      content: m.content,
      reason: reason || null,
    });
    alert("Thanks — your report was sent to our team.");
  };

  const translate = async (m: Msg) => {
    setTranslatingId(m.id);
    try {
      const { data, error } = await supabase.functions.invoke("chat", {
        body: { messages: [{ role: "user", content: m.content }], language, mode: "translate" },
      });
      if (error || data?.error) throw new Error(data?.error || error?.message);
      setTranslations((t) => ({ ...t, [m.id]: data.content }));
    } catch (e: unknown) {
      setError(friendlyError(e));
    } finally {
      setTranslatingId(null);
    }
  };

  const signOut = async () => {
    await supabase.auth.signOut();
    navigate({ to: "/" });
  };

  const fmtTime = (s: string) =>
    new Date(s).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });

  if (!userId) return null;

  const Sidebar = (
    <aside className="flex h-full w-64 shrink-0 flex-col border-r border-border bg-sidebar p-3">
      <button
        onClick={newChat}
        className="mb-3 inline-flex items-center justify-center gap-2 rounded-md bg-primary px-3 py-2 text-sm font-semibold text-primary-foreground hover:opacity-90"
      >
        <Plus className="h-4 w-4" /> New Chat
      </button>
      <div className="relative mb-2">
        <Search className="absolute left-2 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" />
        <input
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search chats…"
          className="w-full rounded-md border border-input bg-background py-1.5 pl-7 pr-2 text-xs outline-none focus:ring-2 focus:ring-ring"
        />
      </div>
      <div className="flex-1 space-y-1 overflow-y-auto">
        {filteredConvs.map((c) => (
          <div
            key={c.id}
            className={`group flex items-center gap-1 rounded-md transition ${
              c.id === activeConvId ? "bg-accent text-accent-foreground" : "hover:bg-sidebar-accent"
            }`}
          >
            <button
              onClick={() => {
                setActiveConvId(c.id);
                setDrawerOpen(false);
              }}
              className="min-w-0 flex-1 truncate px-3 py-2 text-left text-sm"
            >
              {c.title}
            </button>
            <button
              onClick={() => deleteConversation(c.id)}
              title="Delete chat"
              className="mr-1 grid h-7 w-7 shrink-0 place-items-center rounded opacity-70 hover:bg-destructive/10 hover:text-destructive sm:opacity-0 sm:group-hover:opacity-100"
            >
              <Trash2 className="h-3.5 w-3.5" />
            </button>
            {c.id === activeConvId && (
              <button
                onClick={() => downloadConversation(c.id)}
                title="Download chat"
                className="mr-1 grid h-7 w-7 shrink-0 place-items-center rounded opacity-70 hover:bg-accent sm:opacity-0 sm:group-hover:opacity-100"
              >
                <Download className="h-3.5 w-3.5" />
              </button>
            )}
          </div>
        ))}
        {filteredConvs.length === 0 && (
          <p className="px-2 py-4 text-xs text-muted-foreground">
            {search ? "No matches." : "No chats yet."}
          </p>
        )}
      </div>
    </aside>
  );

  return (
    <div className="flex h-screen flex-col bg-chat-surface bg-[radial-gradient(circle_at_top_left,var(--chat-glow),transparent_34%),linear-gradient(180deg,var(--chat-surface),var(--background))]">
      <header className="flex items-center justify-between border-b border-border bg-chat-panel px-3 py-2 shadow-sm backdrop-blur-xl sm:px-4 sm:py-3">
        <div className="flex items-center gap-2">
          <button
            onClick={() => setDrawerOpen(true)}
            className="md:hidden rounded-md p-1.5 hover:bg-accent"
          >
            <Menu className="h-5 w-5" />
          </button>
          <Link to="/" className="flex items-center gap-2">
            <div className="grid h-8 w-8 place-items-center rounded-lg bg-primary text-primary-foreground shadow-sm">
              <Sparkles className="h-4 w-4" />
            </div>
            <span className="text-base font-bold tracking-tight">HI THERE</span>
          </Link>
        </div>
        <div className="flex items-center gap-1.5 sm:gap-2">
          <NotificationBell userId={userId} />
          {isAdmin && (
            <Link
              to="/admin"
              className="inline-flex items-center gap-1 rounded-md border border-border bg-card px-2 py-1 text-xs hover:bg-accent"
            >
              <Shield className="h-3 w-3" /> <span className="hidden sm:inline">Admin</span>
            </Link>
          )}
          <div className="relative">
            <button
              onClick={() => setProfileOpen((v) => !v)}
              className="inline-flex h-8 w-8 items-center justify-center rounded-full border border-border bg-card hover:bg-accent"
            >
              <User className="h-4 w-4" />
            </button>
            {profileOpen && (
              <div className="absolute right-0 top-10 z-50 w-56 rounded-xl border border-border bg-popover/95 p-1.5 shadow-2xl backdrop-blur-xl">
                <Link
                  to="/settings"
                  onClick={() => setProfileOpen(false)}
                  className="flex items-center gap-2 rounded-md px-2.5 py-2 text-sm hover:bg-accent"
                >
                  <SettingsIcon className="h-4 w-4" /> Settings
                </Link>
                <button
                  onClick={() => {
                    setProfileOpen(false);
                    newChat();
                  }}
                  className="flex w-full items-center gap-2 rounded-md px-2.5 py-2 text-left text-sm hover:bg-accent"
                >
                  <Plus className="h-4 w-4" /> New chat
                </button>
                {activeConvId && (
                  <button
                    onClick={() => {
                      setProfileOpen(false);
                      downloadConversation(activeConvId);
                    }}
                    className="flex w-full items-center gap-2 rounded-md px-2.5 py-2 text-left text-sm hover:bg-accent"
                  >
                    <Download className="h-4 w-4" /> Download current chat
                  </button>
                )}
                {activeConvId && (
                  <button
                    onClick={() => {
                      setProfileOpen(false);
                      deleteConversation(activeConvId);
                    }}
                    className="flex w-full items-center gap-2 rounded-md px-2.5 py-2 text-left text-sm hover:bg-destructive/10 hover:text-destructive"
                  >
                    <Trash2 className="h-4 w-4" /> Delete current chat
                  </button>
                )}
                <Link
                  to="/faq"
                  onClick={() => setProfileOpen(false)}
                  className="flex items-center gap-2 rounded-md px-2.5 py-2 text-sm hover:bg-accent"
                >
                  <HelpCircle className="h-4 w-4" /> FAQ
                </Link>
                <Link
                  to="/contact"
                  onClick={() => setProfileOpen(false)}
                  className="flex items-center gap-2 rounded-md px-2.5 py-2 text-sm hover:bg-accent"
                >
                  Contact
                </Link>
                <button
                  onClick={signOut}
                  className="flex w-full items-center gap-2 rounded-md px-2.5 py-2 text-left text-sm hover:bg-accent"
                >
                  <LogOut className="h-4 w-4" /> Sign out
                </button>
              </div>
            )}
          </div>
        </div>
      </header>

      <div className="flex flex-1 overflow-hidden">
        <div className="hidden md:flex">{Sidebar}</div>
        {drawerOpen && (
          <div className="fixed inset-0 z-40 md:hidden" onClick={() => setDrawerOpen(false)}>
            <div className="absolute inset-0 bg-black/50" />
            <div className="absolute left-0 top-0 h-full" onClick={(e) => e.stopPropagation()}>
              <div className="relative h-full">
                {Sidebar}
                <button
                  onClick={() => setDrawerOpen(false)}
                  className="absolute right-2 top-2 rounded-md p-1 hover:bg-accent"
                >
                  <X className="h-4 w-4" />
                </button>
              </div>
            </div>
          </div>
        )}

        <main className="flex flex-1 flex-col">
          <div ref={scrollRef} className="flex-1 overflow-y-auto px-3 py-4 sm:px-4 sm:py-6">
            <div className="mx-auto max-w-3xl space-y-4">
              {messages.length === 0 && !typing && (
                <div className="rounded-2xl border border-border bg-card p-6 text-center sm:p-8">
                  <div className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-2xl bg-gradient-to-br from-primary to-accent">
                    <Sparkles className="h-6 w-6 text-primary-foreground" />
                  </div>
                  <h2 className="text-2xl font-bold">Hi there 👋</h2>
                  <p className="mt-2 text-sm text-muted-foreground">
                    Welcome to HI THERE — your friendly AI companion. Ask anything!
                  </p>
                </div>
              )}

              {messages.map((m) => (
                <div
                  key={m.id}
                  className={`flex ${m.role === "user" ? "justify-end" : "justify-start"}`}
                >
                  <div
                    className={`group max-w-[88%] rounded-2xl px-3.5 py-2.5 text-sm sm:max-w-[85%] sm:px-4 sm:py-3 ${
                      m.role === "user"
                        ? "bg-primary text-primary-foreground"
                        : "border border-border bg-card"
                    }`}
                  >
                    <MD text={m.content} dark={m.role === "user"} />
                    {m.role === "user" && editingId === m.id && (
                      <div className="mt-2 space-y-1">
                        <textarea
                          value={editingText}
                          onChange={(e) => setEditingText(e.target.value)}
                          rows={3}
                          className="w-full rounded-md border border-input bg-background px-2 py-1 text-sm text-foreground"
                        />
                        <div className="flex justify-end gap-1">
                          <button
                            onClick={() => setEditingId(null)}
                            className="rounded px-2 py-0.5 text-[11px] hover:bg-accent"
                          >
                            Cancel
                          </button>
                          <button
                            onClick={() => saveEdit(m)}
                            className="rounded bg-primary px-2 py-0.5 text-[11px] text-primary-foreground"
                          >
                            Save
                          </button>
                        </div>
                      </div>
                    )}
                    {m.attachments && m.attachments.length > 0 && (
                      <div className="mt-2 space-y-2">
                        {m.attachments.map((a, i) => (
                          <AttachmentPreview key={`${a.name}-${i}`} attachment={a} />
                        ))}
                      </div>
                    )}
                    {translations[m.id] && (
                      <div className="mt-2 rounded-md border border-border/50 bg-background/40 p-2 text-xs">
                        <p className="mb-1 font-semibold opacity-70">
                          Translation ({langLabel(language)}):
                        </p>
                        <p className="whitespace-pre-wrap">{translations[m.id]}</p>
                      </div>
                    )}
                    <div className="mt-1.5 flex items-center justify-between gap-3 text-[10px] opacity-70">
                      <span>{fmtTime(m.created_at)}</span>
                      {m.role === "assistant" ? (
                        <div className="flex items-center gap-1">
                          <button
                            onClick={() => translate(m)}
                            disabled={translatingId === m.id}
                            className="inline-flex items-center gap-1 rounded px-1 py-0.5 hover:bg-accent"
                          >
                            <Languages className="h-3 w-3" />
                            {translatingId === m.id ? "…" : "Translate"}
                          </button>
                          <button
                            onClick={() => speak(m)}
                            title="Read aloud"
                            className="inline-flex items-center gap-1 rounded px-1 py-0.5 hover:bg-accent"
                          >
                            {speakingId === m.id ? (
                              <VolumeX className="h-3 w-3" />
                            ) : (
                              <Volume2 className="h-3 w-3" />
                            )}
                          </button>
                          <button
                            onClick={() => share(m)}
                            title="Share"
                            className="inline-flex items-center gap-1 rounded px-1 py-0.5 hover:bg-accent"
                          >
                            <Share2 className="h-3 w-3" />
                          </button>
                          <button
                            onClick={() => report(m)}
                            title="Report"
                            className="inline-flex items-center gap-1 rounded px-1 py-0.5 hover:bg-accent"
                          >
                            <Flag className="h-3 w-3" />
                          </button>
                          <button
                            onClick={() => deleteMessage(m)}
                            title="Delete"
                            className="inline-flex items-center gap-1 rounded px-1 py-0.5 hover:bg-destructive/10 hover:text-destructive"
                          >
                            <Trash2 className="h-3 w-3" />
                          </button>
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
                        </div>
                      ) : (
                        <div className="flex items-center gap-1">
                          <button
                            onClick={() => startEdit(m)}
                            className="inline-flex items-center gap-1 rounded px-1 py-0.5 hover:bg-white/15"
                          >
                            <Pencil className="h-3 w-3" /> Edit
                          </button>
                          <button
                            onClick={() => copy(m.id, m.content)}
                            className="inline-flex items-center gap-1 rounded px-1 py-0.5 hover:bg-white/15"
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
                          <button
                            onClick={() => deleteMessage(m)}
                            title="Delete"
                            className="inline-flex items-center gap-1 rounded px-1 py-0.5 hover:bg-white/15"
                          >
                            <Trash2 className="h-3 w-3" />
                          </button>
                        </div>
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

          {error && (
            <div className="mx-3 mb-1 rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-xs text-destructive sm:mx-4">
              {error}
              <button onClick={() => setError(null)} className="ml-2 underline">
                dismiss
              </button>
            </div>
          )}

          <div className="border-t border-border bg-chat-panel p-2 backdrop-blur-xl sm:p-3">
            <div className="mx-auto max-w-3xl">
              {pendingFiles.length > 0 && (
                <div className="mb-2 grid gap-2 sm:grid-cols-2">
                  {pendingFiles.map((f, i) => (
                    <div key={i} className="relative">
                      <AttachmentPreview attachment={f} />
                      <button
                        onClick={() => setPendingFiles((p) => p.filter((_, j) => j !== i))}
                        className="absolute right-2 top-2 grid h-7 w-7 place-items-center rounded-full border border-border bg-background/90 text-foreground shadow-sm hover:text-destructive"
                      >
                        <X className="h-3.5 w-3.5" />
                      </button>
                    </div>
                  ))}
                </div>
              )}
              <div className="flex items-end gap-1.5 sm:gap-2">
                <input
                  ref={fileRef}
                  type="file"
                  multiple
                  hidden
                  accept=".pdf,.doc,.docx,.xls,.xlsx,image/*,video/*,audio/*"
                  onChange={(e) => {
                    handleFiles(e.target.files);
                    e.target.value = "";
                  }}
                />
                <input
                  ref={cameraRef}
                  type="file"
                  hidden
                  accept="image/*"
                  capture="environment"
                  onChange={(e) => {
                    handleFiles(e.target.files);
                    e.target.value = "";
                  }}
                />
                <button
                  onClick={() => fileRef.current?.click()}
                  title="Attach file"
                  className="grid h-10 w-10 shrink-0 place-items-center rounded-xl border border-input bg-card hover:bg-accent"
                >
                  <Paperclip className="h-4 w-4" />
                </button>
                <button
                  onClick={() => cameraRef.current?.click()}
                  title="Camera"
                  className="grid h-10 w-10 shrink-0 place-items-center rounded-xl border border-input bg-card hover:bg-accent md:hidden"
                >
                  <Camera className="h-4 w-4" />
                </button>
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
                  className="max-h-40 min-h-[40px] flex-1 resize-none rounded-xl border border-input bg-card px-3 py-2.5 text-sm outline-none focus:ring-2 focus:ring-ring sm:px-4 sm:py-3"
                />
                <button
                  onClick={send}
                  disabled={sending || (!input.trim() && pendingFiles.length === 0)}
                  className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-primary text-primary-foreground hover:opacity-90 disabled:opacity-40"
                >
                  <Send className="h-4 w-4" />
                </button>
              </div>
            </div>
          </div>
          <Footer />
        </main>
      </div>
    </div>
  );
}
