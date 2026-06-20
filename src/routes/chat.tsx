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
  ChevronDown,
  ChevronRight,
  Bookmark,
  BookmarkCheck,
  Activity,
  LayoutDashboard,
  Wand2,
  Bug,
  ListChecks,
  FileDown,
  Type as TypeIcon,
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
  text?: string;
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
  attachments.map(({ data: _d, text: _t, ...rest }) => rest);

async function fileToBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || "").split(",")[1] || "");
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
}

const TEXT_EXTS = /\.(txt|md|markdown|json|jsonl|csv|tsv|xml|yml|yaml|html?|css|scss|less|js|jsx|ts|tsx|py|rb|go|rs|java|kt|swift|c|h|cpp|hpp|cs|php|sh|bash|zsh|sql|toml|ini|env|log|gitignore|dockerfile|lock)$/i;
const TEXT_MIME = /^(text\/|application\/(json|xml|x-yaml|javascript|typescript))/;
const isTextFile = (f: { type?: string; name: string }) =>
  TEXT_MIME.test(f.type || "") || TEXT_EXTS.test(f.name);

async function readTextSafe(file: Blob, maxBytes = 200_000): Promise<string> {
  const slice = file.size > maxBytes ? file.slice(0, maxBytes) : file;
  const t = await slice.text();
  return file.size > maxBytes ? t + `\n…[truncated, original ${file.size} bytes]` : t;
}

async function extractZipSummary(file: File): Promise<string> {
  const { default: JSZip } = await import("jszip");
  const zip = await JSZip.loadAsync(file);
  let entries = Object.values(zip.files).filter((e) => !e.dir);

  // Detect & strip a single wrapper folder (GitHub "<repo>-main/", Lovable export, etc.)
  const topSegs = new Set(entries.map((e) => e.name.split("/")[0]));
  let wrapper = "";
  if (topSegs.size === 1) {
    wrapper = [...topSegs][0] + "/";
  }
  const norm = (n: string) => (wrapper && n.startsWith(wrapper) ? n.slice(wrapper.length) : n);

  // Skip noise
  const SKIP = /(^|\/)(node_modules|\.git|\.next|dist|build|\.cache|\.turbo|coverage)\//;
  const SKIP_FILES = /(package-lock\.json|bun\.lock|yarn\.lock|pnpm-lock\.yaml)$/i;
  entries = entries.filter((e) => !SKIP.test("/" + norm(e.name)) && !SKIP_FILES.test(norm(e.name)));

  const list = entries.map((e) => `- ${norm(e.name)}`).join("\n");
  const textEntries = entries.filter((e) => isTextFile({ name: e.name }));

  // Prioritise key files
  const KEY = /(package\.json|tsconfig|vite\.config|tailwind|index\.html|README|src\/routes\/|src\/App|src\/main|src\/router|src\/integrations\/)/i;
  textEntries.sort((a, b) => (KEY.test(b.name) ? 1 : 0) - (KEY.test(a.name) ? 1 : 0));

  let body = "";
  let budget = 240_000;
  let included = 0;
  for (const e of textEntries) {
    if (budget <= 0) break;
    try {
      const content = await e.async("string");
      const snippet = content.slice(0, Math.min(20_000, budget));
      budget -= snippet.length;
      included++;
      body += `\n\n\`\`\`${guessLang(e.name)} ${norm(e.name)}\n${snippet}${content.length > snippet.length ? "\n…[truncated]" : ""}\n\`\`\``;
    } catch {
      /* skip binary */
    }
  }
  return `ZIP archive "${file.name}"${wrapper ? ` (wrapper "${wrapper}" stripped)` : ""} — ${entries.length} files, ${included} read inline below.\n\nFile list:\n${list}${body}`;
}

function guessLang(name: string): string {
  const ext = name.split(".").pop()?.toLowerCase() || "";
  const map: Record<string, string> = {
    ts: "ts", tsx: "tsx", js: "js", jsx: "jsx", json: "json",
    css: "css", scss: "scss", html: "html", md: "md",
    py: "py", rb: "rb", go: "go", rs: "rs", java: "java", sh: "bash",
    sql: "sql", yml: "yaml", yaml: "yaml", toml: "toml", env: "bash",
  };
  return map[ext] || "";
}

function base64ToBlob(base64: string, type: string) {
  const bin = atob(base64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new Blob([bytes], { type });
}

function CollapsibleCode({
  code,
  lang,
  filename,
}: {
  code: string;
  lang?: string;
  filename?: string;
}) {
  const lineCount = code.split("\n").length;
  // Auto-open short snippets; collapse anything bigger or anything with a filename.
  const [open, setOpen] = useState(!filename && lineCount <= 12);
  const [copied, setCopied] = useState(false);
  const label = filename || (lang ? `${lang} snippet` : "code");

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      /* ignore */
    }
  };

  const download = () => {
    const blob = new Blob([code], { type: "text/plain" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename?.split("/").pop() || `snippet.${lang || "txt"}`;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="my-3 overflow-hidden rounded-xl border border-border bg-card/60">
      <div className="flex items-center gap-2 border-b border-border bg-muted/40 px-2.5 py-1.5 text-[12px]">
        <button
          type="button"
          onClick={() => setOpen((v) => !v)}
          className="flex flex-1 items-center gap-1.5 text-left font-mono text-muted-foreground hover:text-foreground"
        >
          {open ? (
            <ChevronDown className="h-3.5 w-3.5 shrink-0" />
          ) : (
            <ChevronRight className="h-3.5 w-3.5 shrink-0" />
          )}
          <FileText className="h-3.5 w-3.5 shrink-0" />
          <span className="truncate">{label}</span>
          <span className="ml-1 shrink-0 text-[10px] opacity-60">
            {lineCount} line{lineCount === 1 ? "" : "s"}
          </span>
        </button>
        <button
          type="button"
          onClick={copy}
          className="inline-flex items-center gap-1 rounded px-1.5 py-0.5 hover:bg-accent"
          title="Copy"
        >
          {copied ? <Check className="h-3 w-3" /> : <Copy className="h-3 w-3" />}
        </button>
        <button
          type="button"
          onClick={download}
          className="inline-flex items-center gap-1 rounded px-1.5 py-0.5 hover:bg-accent"
          title="Download"
        >
          <Download className="h-3 w-3" />
        </button>
      </div>
      {open && (
        <pre className="m-0 max-h-[480px] overflow-auto bg-background/40 p-3 text-[12px] leading-relaxed">
          <code>{code}</code>
        </pre>
      )}
    </div>
  );
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
    if (raw.includes("non-2xx") || raw.includes("502") || raw.includes("503")) {
      return "The AI is taking a quick break. Please try again in a moment.";
    }
    // For real provider errors, surface the actual message so admin can debug.
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
  const [availableVoices, setAvailableVoices] = useState<SpeechSynthesisVoice[]>([]);
  const [typing, setTyping] = useState("");
  const [search, setSearch] = useState("");
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [pendingFiles, setPendingFiles] = useState<Attachment[]>([]);
  const [error, setError] = useState<string | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const cameraRef = useRef<HTMLInputElement>(null);

  // === New: prefs, pinned, slash menu, audit ===
  const [personality, setPersonality] = useState<"direct" | "friendly" | "detailed">(
    () => (typeof localStorage !== "undefined" && (localStorage.getItem("hi-there-personality") as any)) || "friendly",
  );
  const [respLength, setRespLength] = useState<"short" | "normal" | "detailed">(
    () => (typeof localStorage !== "undefined" && (localStorage.getItem("hi-there-length") as any)) || "normal",
  );
  const [fontSize, setFontSize] = useState<"sm" | "md" | "lg">(
    () => (typeof localStorage !== "undefined" && (localStorage.getItem("hi-there-fontsize") as any)) || "md",
  );
  const [pinned, setPinned] = useState<Record<string, boolean>>(() => {
    try {
      return JSON.parse(localStorage.getItem("hi-there-pinned") || "{}");
    } catch {
      return {};
    }
  });
  const [slashOpen, setSlashOpen] = useState(false);
  const [auditOpen, setAuditOpen] = useState(false);
  const [auditReport, setAuditReport] = useState<string | null>(null);
  const [auditing, setAuditing] = useState(false);
  const [isOffline, setIsOffline] = useState(() => typeof navigator !== "undefined" && !navigator.onLine);

  useEffect(() => {
    try { localStorage.setItem("hi-there-personality", personality); } catch { /* ignore */ }
  }, [personality]);
  useEffect(() => {
    try { localStorage.setItem("hi-there-length", respLength); } catch { /* ignore */ }
  }, [respLength]);
  useEffect(() => {
    try { localStorage.setItem("hi-there-fontsize", fontSize); } catch { /* ignore */ }
  }, [fontSize]);
  useEffect(() => {
    try { localStorage.setItem("hi-there-pinned", JSON.stringify(pinned)); } catch { /* ignore */ }
  }, [pinned]);
  useEffect(() => {
    const on = () => setIsOffline(false);
    const off = () => setIsOffline(true);
    window.addEventListener("online", on);
    window.addEventListener("offline", off);
    return () => {
      window.removeEventListener("online", on);
      window.removeEventListener("offline", off);
    };
  }, []);

  // Time-of-day welcome greeting
  const timeGreeting = useMemo(() => {
    const h = new Date().getHours();
    const morning = ["Good morning — ready when you are.", "Morning! What's first today?", "Morning. Let's make it count."];
    const afternoon = ["Afternoon. What are we working on?", "Good afternoon — how can I help?", "Afternoon! Drop it on me."];
    const evening = ["Evening. What do you need?", "Evening — let's wrap something up.", "Evening! Still building?"];
    const lateNight = ["Late night session? I'm here.", "Up late? Let's keep it sharp.", "Quiet hours — perfect for focus work."];
    const pool = h < 5 ? lateNight : h < 12 ? morning : h < 17 ? afternoon : h < 22 ? evening : lateNight;
    return pool[Math.floor(Math.random() * pool.length)];
  }, [activeConvId]); // refresh greeting on new chat

  // Slash command catalogue
  const slashCommands = [
    { cmd: "/explain", desc: "Explain the last code in plain English" },
    { cmd: "/fix", desc: "Find and fix bugs in the last code" },
    { cmd: "/audit", desc: "Full bug scan of the project" },
    { cmd: "/compare", desc: "Compare two solutions to a problem" },
    { cmd: "/summary", desc: "Summarise this conversation" },
    { cmd: "/export", desc: "Export this conversation to a file" },
    { cmd: "/image", desc: "Generate a new image" },
    { cmd: "/health", desc: "Run a self-audit of all AI providers" },
  ];
  const slashMatches = useMemo(() => {
    const t = input.trim();
    if (!t.startsWith("/")) return [];
    return slashCommands.filter((c) => c.cmd.startsWith(t.split(" ")[0]));
  }, [input]);

  // Smart follow-up suggestions for the last assistant message
  const lastAssistant = useMemo(() => [...messages].reverse().find((m) => m.role === "assistant"), [messages]);
  const suggestions = useMemo(() => {
    if (!lastAssistant) return [];
    const c = lastAssistant.content.toLowerCase();
    const hasImg = lastAssistant.attachments?.some((a) => a.type?.startsWith("image/"));
    if (hasImg) return ["Modify this image", "Generate a variation", "Animate this image"];
    if (/bug|issue|error|fix|critical|high|severity/.test(c))
      return ["Fix the highest severity bug", "Show me all affected files", "Explain what caused this"];
    if (/```/.test(lastAssistant.content)) return ["Explain this code", "Refactor for readability", "Add tests"];
    if (/zip|project|files|repository/.test(c)) return ["List the most important files", "Find security issues", "Suggest improvements"];
    return ["Tell me more", "Give an example", "Summarize the key points"];
  }, [lastAssistant]);

  // Self-audit
  const runSelfAudit = async () => {
    setAuditOpen(true);
    setAuditing(true);
    setAuditReport(null);
    try {
      const { data, error } = await supabase.functions.invoke("chat", { body: { mode: "audit", messages: [] } });
      if (error) throw error;
      const provs = data?.providers || {};
      const ok = Object.entries(provs).filter(([, v]: any) => v.ok).map(([k]) => k);
      const bad = Object.entries(provs).filter(([, v]: any) => !v.ok);
      const lines = [
        `**System Health Report**  •  ${new Date().toLocaleString()}`,
        ``,
        `**AI Providers (${ok.length}/${Object.keys(provs).length} online)**`,
        ...Object.entries(provs).map(([k, v]: any) =>
          `- ${v.ok ? "✅" : "❌"} **${k}** — ${v.ok ? `${v.ms}ms` : (v.error || "failed")}`,
        ),
        ``,
        `**Image generation:** ${data?.image_generation?.ok ? `✅ working (via ${data.image_generation.via})` : `❌ ${data?.image_generation?.error || "down"}`}`,
        `**ZIP file reader:** ${data?.zip_reader?.ok ? "✅ active" : "❌ not available"}`,
        ``,
        bad.length === 0
          ? `All systems nominal — full failover chain available.`
          : `Providers currently limited or down: ${bad.map(([k]) => k).join(", ")}. The smart router will skip them automatically.`,
      ];
      setAuditReport(lines.join("\n"));
    } catch (e: any) {
      setAuditReport(`❌ Audit failed: ${friendlyError(e)}`);
    } finally {
      setAuditing(false);
    }
  };

  // Copy the last assistant response
  const copyLast = async () => {
    if (!lastAssistant) return;
    await navigator.clipboard.writeText(lastAssistant.content);
    setCopiedId(lastAssistant.id);
    setTimeout(() => setCopiedId(null), 1500);
  };

  // Toggle pin
  const togglePin = (id: string) => setPinned((p) => ({ ...p, [id]: !p[id] }));
  const pinnedMessages = useMemo(() => messages.filter((m) => pinned[m.id]), [messages, pinned]);

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
          pre: ({ children }: any) => {
            // Extract the inner <code> child produced by remark for fenced blocks.
            const child: any = Array.isArray(children) ? children[0] : children;
            const props = child?.props || {};
            const raw = String(props.children ?? "").replace(/\n$/, "");
            const className: string = props.className || "";
            const langMatch = /language-([\w+-]+)/.exec(className);
            const lang = langMatch?.[1];
            // Filename support: ```tsx src/foo.tsx  → react-markdown drops the
            // info-string tail, so we also accept a first-line "// file: path" hint.
            let filename: string | undefined;
            const firstLine = raw.split("\n")[0] || "";
            const fileHint = /^\s*(?:\/\/|#|--)\s*(?:file|path):\s*(\S+)/i.exec(firstLine);
            const bareFile = /^(?:[\w.\-/]+\/)?[\w.\-]+\.\w{1,8}$/i.test(firstLine.trim());
            let body = raw;
            if (fileHint) {
              filename = fileHint[1];
              body = raw.split("\n").slice(1).join("\n");
            } else if (className.includes(" ") || (lang && bareFile)) {
              // some markdowns keep extra info after language in className
              const extra = className.split(" ").find((c) => c.includes("/") || c.includes("."));
              if (extra) filename = extra;
            }
            return <CollapsibleCode code={body} lang={lang} filename={filename} />;
          },
          code: ({ inline, className, children, ...props }: any) => {
            if (inline) {
              return (
                <code
                  className="rounded bg-muted px-1 py-0.5 font-mono text-[0.9em]"
                  {...props}
                >
                  {children}
                </code>
              );
            }
            // Block code is handled by the `pre` override; just pass through.
            return (
              <code className={className} {...props}>
                {children}
              </code>
            );
          },
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

  // Load browser TTS voices for the picker (Chrome populates them async).
  useEffect(() => {
    if (typeof window === "undefined" || !window.speechSynthesis) return;
    const load = () => setAvailableVoices(window.speechSynthesis.getVoices());
    load();
    window.speechSynthesis.onvoiceschanged = load;
    return () => {
      if (window.speechSynthesis) window.speechSynthesis.onvoiceschanged = null;
    };
  }, []);

  const saveVoice = async (name: string) => {
    setVoiceName(name);
    if (userId) {
      await supabase
        .from("profiles")
        .update({ voice_name: name } as never)
        .eq("user_id", userId);
    }
  };

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
    if (data && data.length && !activeConvId) {
      // If user came from dashboard, restore that conversation
      let target = data[0].id;
      try {
        const requested = localStorage.getItem("hi-there-open-conv");
        if (requested && data.some((c) => c.id === requested)) target = requested;
        localStorage.removeItem("hi-there-open-conv");
      } catch { /* ignore */ }
      setActiveConvId(target);
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
      let textContent: string | undefined;
      try {
        if (f.name.toLowerCase().endsWith(".zip") || f.type === "application/zip") {
          textContent = await extractZipSummary(f);
        } else if (isTextFile(f) && f.size <= 2 * 1024 * 1024) {
          textContent = await readTextSafe(f);
        }
      } catch (e) {
        console.warn("file extract failed", f.name, e);
      }
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
        text: textContent,
      });
    }
    setPendingFiles((p) => [...p, ...uploads]);
  };

  const send = async () => {
    if ((!input.trim() && pendingFiles.length === 0) || !userId || sending) return;
    setError(null);
    // Slash-command routing (intercept before sending to AI)
    const raw = input.trim();
    if (raw === "/health" || raw === "/audit") {
      setInput("");
      setSlashOpen(false);
      if (raw === "/health") {
        await runSelfAudit();
        return;
      }
      // /audit on a project context: rewrite as a clear audit instruction
      setInput("Run a full audit of the project I shared. Report every issue you find in one response, sorted Critical → High → Medium → Low.");
      return;
    }
    if (raw === "/export") {
      setInput("");
      setSlashOpen(false);
      downloadConversation(activeConvId);
      return;
    }
    if (raw === "/summary") {
      setInput("Summarise this entire conversation in clear bullet points.");
      return;
    }
    if (raw === "/explain") {
      setInput("Explain the last code I shared in plain English, section by section.");
      return;
    }
    if (raw === "/fix") {
      setInput("Find and fix every bug in the last code I shared. Show before/after for each change.");
      return;
    }
    if (raw === "/compare") {
      setInput("Compare two different solutions to my last question, side by side, with pros/cons.");
      return;
    }
    setSlashOpen(false);
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
      const textBlocks = attachments
        .filter((a) => a.text)
        .map((a) => `\n\n=== File: ${a.name} ===\n${a.text}`)
        .join("");
      const attachContext = attachments.length
        ? `\n\n[User attached ${attachments.length} file(s): ${attachments
            .map((a) => `${a.name} (${a.type})`)
            .join(", ")}. ${
            hasImages
              ? "Image data is attached — describe what you see."
              : "Use the file contents below."
          }]${textBlocks}`
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
          prefs: { personality, length: respLength },
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
    <div className={`flex h-screen flex-col bg-chat-surface bg-[radial-gradient(circle_at_top_left,var(--chat-glow),transparent_34%),linear-gradient(180deg,var(--chat-surface),var(--background))] ${fontSize === "sm" ? "text-[13px]" : fontSize === "lg" ? "text-[17px]" : "text-[15px]"}`}>
      {isOffline && (
        <div className="bg-destructive/15 px-3 py-1.5 text-center text-xs text-destructive">
          You're offline — messages will fail until your connection returns.
        </div>
      )}
      {auditOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4" onClick={() => setAuditOpen(false)}>
          <div className="max-h-[80vh] w-full max-w-lg overflow-auto rounded-2xl border border-border bg-card p-5 shadow-2xl" onClick={(e) => e.stopPropagation()}>
            <div className="mb-3 flex items-center justify-between">
              <h3 className="flex items-center gap-2 text-lg font-bold"><Activity className="h-5 w-5 text-primary" /> System health</h3>
              <button onClick={() => setAuditOpen(false)} className="rounded p-1 hover:bg-accent"><X className="h-4 w-4" /></button>
            </div>
            {auditing && <p className="text-sm text-muted-foreground">Pinging every provider…</p>}
            {auditReport && <pre className="whitespace-pre-wrap text-xs leading-relaxed">{auditReport}</pre>}
          </div>
        </div>
      )}
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
                <Link
                  to="/dashboard"
                  onClick={() => setProfileOpen(false)}
                  className="flex items-center gap-2 rounded-md px-2.5 py-2 text-sm hover:bg-accent"
                >
                  <LayoutDashboard className="h-4 w-4" /> Project dashboard
                </Link>
                <button
                  onClick={() => { setProfileOpen(false); runSelfAudit(); }}
                  className="flex w-full items-center gap-2 rounded-md px-2.5 py-2 text-left text-sm hover:bg-accent"
                >
                  <Activity className="h-4 w-4" /> Audit AI health
                </button>
                <button
                  onClick={() => { setProfileOpen(false); copyLast(); }}
                  disabled={!lastAssistant}
                  className="flex w-full items-center gap-2 rounded-md px-2.5 py-2 text-left text-sm hover:bg-accent disabled:opacity-40"
                >
                  <Copy className="h-4 w-4" /> Copy last response
                </button>
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
                <div className="border-t border-border/60 px-2.5 py-2">
                  <label className="mb-1 flex items-center gap-1.5 text-[11px] font-semibold text-muted-foreground">
                    <Volume2 className="h-3 w-3" /> Voice tone
                  </label>
                  <select
                    value={voiceName}
                    onChange={(e) => saveVoice(e.target.value)}
                    className="w-full rounded-md border border-input bg-background px-2 py-1 text-xs"
                  >
                    <option value="default">Default</option>
                    <option value="female">Female (auto)</option>
                    <option value="male">Male (auto)</option>
                    {availableVoices.map((v) => (
                      <option key={v.voiceURI} value={v.name}>
                        {v.name} {v.lang ? `· ${v.lang}` : ""}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="border-t border-border/60 px-2.5 py-2 space-y-2">
                  <div>
                    <label className="mb-1 flex items-center gap-1.5 text-[11px] font-semibold text-muted-foreground">
                      <Wand2 className="h-3 w-3" /> Personality
                    </label>
                    <select
                      value={personality}
                      onChange={(e) => setPersonality(e.target.value as any)}
                      className="w-full rounded-md border border-input bg-background px-2 py-1 text-xs"
                    >
                      <option value="direct">Direct (short, no fluff)</option>
                      <option value="friendly">Friendly (default)</option>
                      <option value="detailed">Detailed</option>
                    </select>
                  </div>
                  <div>
                    <label className="mb-1 flex items-center gap-1.5 text-[11px] font-semibold text-muted-foreground">
                      <ListChecks className="h-3 w-3" /> Response length
                    </label>
                    <select
                      value={respLength}
                      onChange={(e) => setRespLength(e.target.value as any)}
                      className="w-full rounded-md border border-input bg-background px-2 py-1 text-xs"
                    >
                      <option value="short">Short</option>
                      <option value="normal">Normal</option>
                      <option value="detailed">Detailed</option>
                    </select>
                  </div>
                  <div>
                    <label className="mb-1 flex items-center gap-1.5 text-[11px] font-semibold text-muted-foreground">
                      <TypeIcon className="h-3 w-3" /> Font size
                    </label>
                    <div className="flex gap-1">
                      {(["sm", "md", "lg"] as const).map((s) => (
                        <button
                          key={s}
                          onClick={() => setFontSize(s)}
                          className={`flex-1 rounded border px-2 py-1 text-xs ${fontSize === s ? "border-primary bg-primary/10" : "border-input"}`}
                        >
                          {s === "sm" ? "Small" : s === "md" ? "Normal" : "Large"}
                        </button>
                      ))}
                    </div>
                  </div>
                </div>
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
              {pinnedMessages.length > 0 && (
                <div className="rounded-xl border border-primary/30 bg-primary/5 p-3">
                  <p className="mb-2 flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wide text-primary">
                    <BookmarkCheck className="h-3 w-3" /> Pinned ({pinnedMessages.length})
                  </p>
                  <div className="space-y-1.5">
                    {pinnedMessages.map((p) => (
                      <div key={`pin-${p.id}`} className="flex items-start gap-2 text-xs">
                        <span className="line-clamp-2 flex-1 text-foreground/80">{p.content.slice(0, 200)}</span>
                        <button onClick={() => togglePin(p.id)} className="shrink-0 rounded p-0.5 hover:bg-accent" title="Unpin">
                          <X className="h-3 w-3" />
                        </button>
                      </div>
                    ))}
                  </div>
                </div>
              )}
              {messages.length === 0 && !typing && (
                <div className="space-y-6 py-6 sm:py-10">
                  <div className="text-center">
                    <div className="mx-auto mb-4 grid h-14 w-14 place-items-center rounded-2xl bg-gradient-to-br from-primary to-accent shadow-lg">
                      <Sparkles className="h-7 w-7 text-primary-foreground" />
                    </div>
                    <h2 className="text-3xl font-bold tracking-tight sm:text-4xl">
                      {timeGreeting}
                    </h2>
                    <p className="mt-2 text-sm text-muted-foreground">
                      Ask, upload images, PDFs, ZIPs or code — I'll handle it.
                    </p>
                  </div>
                  <div className="grid gap-2 sm:grid-cols-2">
                    {[
                      { icon: ImageIcon, label: "Generate an image", prompt: "Generate an image of " },
                      { icon: FileText, label: "Summarize a document", prompt: "Summarize this for me: " },
                      { icon: Search, label: "Search the latest news", prompt: "What's the latest news on " },
                      { icon: Film, label: "Explain a concept", prompt: "Explain in simple terms: " },
                    ].map((q) => (
                      <button
                        key={q.label}
                        onClick={() => setInput(q.prompt)}
                        className="group flex items-center gap-3 rounded-xl border border-border bg-card/60 p-3 text-left text-sm backdrop-blur transition hover:border-primary/60 hover:bg-card"
                      >
                        <span className="grid h-9 w-9 place-items-center rounded-lg bg-primary/10 text-primary">
                          <q.icon className="h-4 w-4" />
                        </span>
                        <span className="font-medium">{q.label}</span>
                      </button>
                    ))}
                  </div>
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
                            onClick={() => togglePin(m.id)}
                            title={pinned[m.id] ? "Unpin" : "Pin"}
                            className="inline-flex items-center gap-1 rounded px-1 py-0.5 hover:bg-accent"
                          >
                            {pinned[m.id] ? <BookmarkCheck className="h-3 w-3 text-primary" /> : <Bookmark className="h-3 w-3" />}
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
              {!sending && !typing && lastAssistant && suggestions.length > 0 && (
                <div className="flex flex-wrap gap-2 pt-1">
                  {suggestions.map((s) => (
                    <button
                      key={s}
                      onClick={() => { setInput(s); }}
                      className="rounded-full border border-border bg-card px-3 py-1 text-xs text-muted-foreground hover:border-primary hover:text-foreground"
                    >
                      {s}
                    </button>
                  ))}
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
              {slashMatches.length > 0 && (
                <div className="mb-2 rounded-xl border border-border bg-popover/95 p-1 shadow-lg backdrop-blur">
                  {slashMatches.map((c) => (
                    <button
                      key={c.cmd}
                      onClick={() => { setInput(c.cmd); setSlashOpen(false); }}
                      className="flex w-full items-start gap-2 rounded-md px-2.5 py-1.5 text-left text-xs hover:bg-accent"
                    >
                      <span className="font-mono font-semibold text-primary">{c.cmd}</span>
                      <span className="text-muted-foreground">{c.desc}</span>
                    </button>
                  ))}
                </div>
              )}
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
                  accept=".pdf,.doc,.docx,.xls,.xlsx,.zip,.txt,.md,.json,.csv,.js,.ts,.tsx,.jsx,.py,.html,.css,image/*,video/*,audio/*"
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
