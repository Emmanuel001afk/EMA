import { createFileRoute, useNavigate, Link } from "@tanstack/react-router";
import { useEffect, useMemo, useRef, useState, useCallback } from "react";
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
  Mic,
  MicOff,
  FileDown,
  Columns,
  GitCompare,
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

// Puter.js loaded via <script> in __root.tsx — declare global type here
declare const puter: {
  ai: {
    chat: (
      messages: { role: string; content: string }[],
      options?: { model?: string }
    ) => Promise<any>;
  };
  auth: { signIn: () => Promise<void>; isSignedIn: () => Promise<boolean> };
};

// callPuterAI: free AI fallback via user's Puter account (no server, no API key needed)
async function callPuterAI(
  messages: { role: string; content: string }[]
): Promise<string> {
  // Wait up to 3 seconds for Puter.js to finish loading (async script tag)
  if (typeof puter === "undefined") {
    await new Promise<void>((resolve, reject) => {
      let waited = 0;
      const check = setInterval(() => {
        waited += 100;
        if (typeof puter !== "undefined") { clearInterval(check); resolve(); }
        if (waited >= 3000) { clearInterval(check); reject(new Error("Puter not loaded")); }
      }, 100);
    });
  }
  // Ensure user is signed into their free Puter account
  try {
    const signedIn = await puter.auth.isSignedIn();
    if (!signedIn) await puter.auth.signIn();
  } catch (_authErr) {
    // Some Puter versions don't need explicit sign-in for basic AI access
  }
  const response = await puter.ai.chat(messages, { model: "claude-sonnet-4-6" });
  if (typeof response === "string") return response;
  if (response?.message?.content?.[0]?.text) return response.message.content[0].text;
  if (response?.content?.[0]?.text) return response.content[0].text;
  if (response?.text) return response.text;
  return String(response || "");
}



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

  // Check for password protection
  let zip: any;
  try {
    zip = await JSZip.loadAsync(file);
  } catch (e: any) {
    if (String(e).includes("password")) {
      return `ZIP archive "${file.name}" is password-protected and cannot be read. Please provide an unprotected ZIP.`;
    }
    return `ZIP archive "${file.name}" could not be opened: ${e?.message || e}`;
  }

  let entries = Object.values(zip.files).filter((e: any) => !e.dir);

  // Handle nested ZIP (zip inside zip) — extract inner zip first
  const innerZip = (entries as any[]).find((e: any) => e.name.toLowerCase().endsWith(".zip"));
  if (innerZip) {
    try {
      const innerData = await (innerZip as any).async("arraybuffer");
      const innerFile = new File([innerData], innerZip.name);
      const innerResult = await extractZipSummary(innerFile);
      return `Outer ZIP: "${file.name}" contains inner ZIP "${innerZip.name}":\n\n${innerResult}`;
    } catch { /* fall through to process outer zip */ }
  }

  // Detect & strip single wrapper folder (GitHub "repo-main/", Lovable export, etc.)
  const topSegs = new Set((entries as any[]).map((e: any) => e.name.split("/")[0]));
  let wrapper = "";
  if (topSegs.size === 1) wrapper = [...topSegs][0] + "/";
  const norm = (n: string) => (wrapper && n.startsWith(wrapper) ? n.slice(wrapper.length) : n);

  // Skip build artifacts and lock files — keep everything else
  const SKIP = /(^\/|\/)?(node_modules|\.git|\.next|dist|build|\.cache|\.turbo|coverage)(\/|$)/;
  const SKIP_FILES = /(package-lock\.json|bun\.lock|yarn\.lock|pnpm-lock\.yaml)$/i;
  entries = (entries as any[]).filter((e: any) => {
    const n = "/" + norm(e.name);
    return !SKIP.test(n) && !SKIP_FILES.test(norm(e.name));
  });

  const totalFiles = entries.length;
  const binaryFiles: string[] = [];
  const failedFiles: string[] = [];
  const textEntries = (entries as any[]).filter((e: any) => isTextFile({ name: e.name }));
  const nonTextEntries = (entries as any[]).filter((e: any) => !isTextFile({ name: e.name }));
  for (const e of nonTextEntries) binaryFiles.push(norm(e.name));

  // Sort: key project files first, then alphabetical
  const KEY = /(package\.json$|tsconfig|vite\.config|tailwind|index\.html|README|\/routes\/|\/App\.|src\/main|router\.tsx|\/integrations\/|supabase.*\/index\.ts)/i;
  textEntries.sort((a: any, b: any) => {
    const ak = KEY.test(a.name) ? 0 : 1;
    const bk = KEY.test(b.name) ? 0 : 1;
    return ak - bk || norm(a.name).localeCompare(norm(b.name));
  });

  // Build file list
  const allFileList = [
    ...textEntries.map((e: any) => `  [TEXT] ${norm(e.name)}`),
    ...binaryFiles.map((n) => `  [BINARY - listed only, not read] ${n}`),
  ].join("\n");

  // Read every text file with byte verification
  let body = "";
  let budget = 300_000; // large budget — read as much as possible
  let included = 0;
  for (const e of textEntries) {
    if (budget <= 0) {
      body += `\n\n[Budget exhausted — ${textEntries.length - included} files not shown. Ask me to read a specific file.]`;
      break;
    }
    try {
      const rawContent: string = await (e as any).async("string");
      // Verify file actually has content
      if (!rawContent || rawContent.trim().length === 0) {
        body += `\n\n[FILE: ${norm(e.name)} — empty file (0 bytes of content)]`;
        included++;
        continue;
      }
      const perFileBudget = Math.min(30_000, budget);
      const snippet = rawContent.slice(0, perFileBudget);
      const truncated = rawContent.length > perFileBudget;
      budget -= snippet.length;
      included++;
      body += `\n\n\`\`\`${guessLang(e.name)} ${norm(e.name)}\n${snippet}${truncated ? "\n…[truncated — file is " + rawContent.length + " chars, showing first " + perFileBudget + "]" : ""}\n\`\`\``;
    } catch (err: any) {
      failedFiles.push(`${norm(e.name)}: ${err?.message || "read error"}`);
    }
  }

  const header = [
    `=== ZIP ARCHIVE: "${file.name}" ===`,
    `Total files found: ${totalFiles}`,
    `Text files read: ${included}/${textEntries.length}`,
    `Binary files (listed only): ${binaryFiles.length}`,
    wrapper ? `Wrapper folder stripped: "${wrapper}"` : "",
    failedFiles.length ? `Files that failed to read: ${failedFiles.join(", ")}` : "",
    "",
    "CRITICAL INSTRUCTION TO AI: Everything below is the ACTUAL file content extracted directly from the ZIP.",
    "You MUST use ONLY this content to answer questions. Do NOT use training knowledge about what these files might contain.",
    "If a file is not shown below, say so explicitly. Never invent or guess file contents.",
    "",
    "=== ALL FILES IN ZIP ===",
    allFileList,
    "",
    "=== FILE CONTENTS (read verbatim from ZIP) ===",
  ].filter(Boolean).join("\n");


  return header + body;
}

// === PDF TEXT EXTRACTION ===
// Uses pdf.js from CDN to extract real text from PDFs
async function extractPdfText(file: File): Promise<string> {
  try {
    // Dynamically load pdf.js
    if (!(window as any).pdfjsLib) {
      await new Promise<void>((resolve, reject) => {
        const s = document.createElement("script");
        s.src = "https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.min.js";
        s.onload = () => resolve();
        s.onerror = () => reject(new Error("pdf.js failed to load"));
        document.head.appendChild(s);
      });
      (window as any).pdfjsLib.GlobalWorkerOptions.workerSrc =
        "https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.worker.min.js";
    }
    const pdfjsLib = (window as any).pdfjsLib;
    const arrayBuffer = await file.arrayBuffer();
    const pdf = await pdfjsLib.getDocument({ data: arrayBuffer }).promise;
    const totalPages = pdf.numPages;
    let fullText = `=== PDF: "${file.name}" — ${totalPages} pages ===\n\n`;
    for (let i = 1; i <= Math.min(totalPages, 50); i++) {
      const page = await pdf.getPage(i);
      const textContent = await page.getTextContent();
      const pageText = textContent.items.map((item: any) => item.str).join(" ");
      fullText += `--- Page ${i} ---\n${pageText}\n\n`;
    }
    if (totalPages > 50) fullText += `[${totalPages - 50} more pages not shown — upload smaller PDF]`;
    return fullText;
  } catch (e: any) {
    return `PDF "${file.name}" could not be read: ${e?.message || e}. Try copying the text manually.`;
  }
}

// === LIVE CODE ARTIFACT RENDERER ===
// When AI returns a full HTML or React snippet, render it live in a sandboxed iframe
function ArtifactRenderer({ code, lang }: { code: string; lang: string }) {
  const [visible, setVisible] = useState(false);
  const isHtml = lang === "html" || lang === "svg";
  const isJsx = lang === "jsx" || lang === "tsx" || lang === "react";
  if (!isHtml && !isJsx) return null;
  let iframeContent = code;
  if (isJsx) {
    // Wrap React/JSX in a simple HTML shell with React CDN
    iframeContent = `<!DOCTYPE html><html><head>
<script src="https://unpkg.com/react@18/umd/react.development.js"></script>
<script src="https://unpkg.com/react-dom@18/umd/react-dom.development.js"></script>
<script src="https://unpkg.com/@babel/standalone/babel.min.js"></script>
<style>body{margin:0;font-family:sans-serif;}</style>
</head><body><div id="root"></div>
<script type="text/babel">
${code}
const root = ReactDOM.createRoot(document.getElementById('root'));
root.render(React.createElement(typeof App !== 'undefined' ? App : () => React.createElement('div', null, 'Component loaded'), null));
</script></body></html>`;
  }
  return (
    <div className="mt-2 overflow-hidden rounded-xl border border-primary/30 bg-background">
      <div className="flex items-center justify-between bg-primary/5 px-3 py-1.5">
        <span className="text-xs font-medium text-primary">⚡ Live Preview</span>
        <button
          onClick={() => setVisible(!visible)}
          className="text-xs text-muted-foreground hover:text-foreground"
        >
          {visible ? "Hide" : "Show"} preview
        </button>
      </div>
      {visible && (
        <iframe
          srcDoc={iframeContent}
          sandbox="allow-scripts allow-same-origin"
          className="h-64 w-full border-0 bg-white"
          title="Live preview"
        />
      )}
    </div>
  );
}

// === IMAGE CAROUSEL ===
// When multiple images are in one message, show them in a swipeable carousel
function ImageCarousel({ images }: { images: { src: string; name: string }[] }) {
  const [idx, setIdx] = useState(0);
  if (images.length === 0) return null;
  const cur = images[idx];
  return (
    <div className="overflow-hidden rounded-xl border border-border bg-background/70">
      <div className="relative">
        <img src={cur.src} alt={cur.name} className="max-h-80 w-full object-contain" loading="lazy" />
        {images.length > 1 && (
          <>
            <button
              onClick={() => setIdx((i) => (i - 1 + images.length) % images.length)}
              className="absolute left-2 top-1/2 -translate-y-1/2 rounded-full bg-black/50 p-1.5 text-white hover:bg-black/70"
            >‹</button>
            <button
              onClick={() => setIdx((i) => (i + 1) % images.length)}
              className="absolute right-2 top-1/2 -translate-y-1/2 rounded-full bg-black/50 p-1.5 text-white hover:bg-black/70"
            >›</button>
            <div className="absolute bottom-2 left-1/2 flex -translate-x-1/2 gap-1">
              {images.map((_, i) => (
                <button key={i} onClick={() => setIdx(i)}
                  className={`h-1.5 w-1.5 rounded-full transition-colors ${i === idx ? "bg-white" : "bg-white/40"}`}
                />
              ))}
            </div>
          </>
        )}
      </div>
      <div className="flex items-center justify-between px-2.5 py-1.5 text-[11px] text-muted-foreground">
        <span>{idx + 1} / {images.length}</span>
        <a href={cur.src} download="hi-there-image.png" className="inline-flex items-center gap-1 rounded px-1.5 py-0.5 hover:bg-accent">
          <Download className="h-3 w-3" /> Save
        </a>
      </div>
    </div>
  );
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
    // Aspect ratio classes for generated images
    const aspectClasses: Record<string, string> = {
      "1:1": "aspect-square",
      "16:9": "aspect-video",
      "9:16": "aspect-[9/16]",
      "4:3": "aspect-[4/3]",
    };
    const [aspectRatio, setAspectRatio] = useState("1:1");
    const isGenerated = attachment.name?.startsWith("generated-");
    return (
      <figure className="overflow-hidden rounded-xl border border-border bg-background/70">
        <div className={isGenerated ? aspectClasses[aspectRatio] : undefined}>
          <img
            src={src}
            alt={attachment.name}
            className={`w-full object-contain${isGenerated ? " h-full" : " max-h-80"}`}
            loading="lazy"
          />
        </div>
        {isGenerated && (
          <div className="flex flex-wrap gap-1 px-2.5 pt-2 pb-1">
            {Object.keys(aspectClasses).map((r) => (
              <button
                key={r}
                onClick={() => setAspectRatio(r)}
                className={`rounded px-2 py-0.5 text-[11px] font-medium transition-colors ${
                  aspectRatio === r
                    ? "bg-primary text-primary-foreground"
                    : "bg-accent text-muted-foreground hover:bg-accent/80"
                }`}
              >
                {r}
              </button>
            ))}
          </div>
        )}
        <figcaption className="flex items-center justify-between gap-2 px-2.5 py-1.5 text-[11px] text-muted-foreground">
          <span className="truncate">
            <ImageIcon className="mr-1 inline h-3 w-3" />
            {isGenerated ? "AI Generated Image" : attachment.name}
          </span>
          <div className="flex items-center gap-1">
            {isGenerated && (
              <button
                onClick={() => {
                  const prompt = window.prompt("Describe what to change in this image:");
                  if (prompt) {
                    const event = new CustomEvent("hi-there-edit-image", { detail: { src, prompt, name: attachment.name } });
                    window.dispatchEvent(event);
                  }
                }}
                className="inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-primary hover:bg-accent"
                title="Edit this image with AI"
              >
                ✏️ Edit
              </button>
            )}
            <a
              href={src}
              download={isGenerated ? "hi-there-image.png" : attachment.name}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-1 rounded px-1.5 py-0.5 hover:bg-accent"
            >
              <Download className="h-3 w-3" /> Save
            </a>
          </div>
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
  const recognitionRef = useRef<any>(null);

  // Live AI status message (shown while sending)
  const [aiStatusMsg, setAiStatusMsg] = useState("Thinking...");

  // Voice input state
  const [voiceListening, setVoiceListening] = useState(false);
  const [continuousVoice, setContinuousVoice] = useState(false);
  const [voiceError, setVoiceError] = useState<string | null>(null);

  // Multi-model compare state
  const [compareMode, setCompareMode] = useState(false);
  const [compareResults, setCompareResults] = useState<{a: string; b: string} | null>(null);
  const [comparing, setComparing] = useState(false);

  // Diff viewer state
  const [diffBefore, setDiffBefore] = useState("");
  const [diffAfter, setDiffAfter] = useState("");
  const [showDiff, setShowDiff] = useState(false);

  // PDF export state
  const [exportingPdf, setExportingPdf] = useState(false);

  // Persistent memory
  const [userMemory, setUserMemory] = useState<string>("");

  // Kids mode & caps from settings
  const [kidsMode, setKidsMode] = useState(() => {
    try { const c = JSON.parse(localStorage.getItem("hi-there-caps") || "{}"); return c.kids_mode === true; } catch { return false; }
  });
  const [capImageGen, setCapImageGen] = useState(() => {
    try { const c = JSON.parse(localStorage.getItem("hi-there-caps") || "{}"); return c.cap_image_gen !== false; } catch { return true; }
  });

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

  // Listen for image edit requests from the Edit button on generated images
  useEffect(() => {
    const handleImageEdit = async (e: any) => {
      const { prompt, name } = e.detail as { src: string; prompt: string; name: string };
      if (!prompt) return;
      // Send edit request as a new user message
      setInput(`Edit the last generated image: ${prompt}`);
      setTimeout(() => {
        document.getElementById("send-btn")?.click();
      }, 100);
    };
    window.addEventListener("hi-there-edit-image", handleImageEdit);
    return () => window.removeEventListener("hi-there-edit-image", handleImageEdit);
  }, []);

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
        } else if (f.type === "application/pdf" || f.name.toLowerCase().endsWith(".pdf")) {
          textContent = await extractPdfText(f);
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
      // Primary: Supabase Edge Function (all server-side providers)
      // Compare mode: run two parallel requests
      if (compareMode && !wantsImage) {
        setAiStatusMsg("Comparing two AI responses...");
        await runCompare(text);
        return;
      }

      // === PRIMARY: Puter.js (real Claude, free, no API key needed) ===
      // For text messages, try Puter.js FIRST — it gives real Claude Sonnet for free.
      // For images/video, go straight to server (Puter doesn't do image generation).
      let data: any = null;
      let error: any = null;

      const hasZip = pendingFiles.some(f => f.name?.endsWith(".zip"));
      const hasFiles = attachments.length > 0;

      if (!wantsImage && !hasFiles) {
        // Pure text — try Puter (real Claude) first
        setAiStatusMsg("Connecting to AI...");
        try {
          const puterReply = await callPuterAI(
            userMemory
              ? [{ role: "system", content: `User memory:\n${userMemory}` }, ...history]
              : history
          );
          if (puterReply) {
            data = { content: puterReply, provider: "hi-there" };
          }
        } catch (_puterErr) {
          // Puter unavailable — fall through to server providers below
        }
      }

      // === SECONDARY: Supabase Edge Function (all server-side providers) ===
      // Used when: image gen, file/ZIP upload, or Puter failed
      if (!data) {
        setAiStatusMsg(
          wantsImage
            ? "Generating image..."
            : hasZip
            ? "Reading ZIP and analyzing files..."
            : hasFiles
            ? "Processing your files..."
            : "Routing to best AI service..."
        );
        const invokeResult = await supabase.functions.invoke("chat", {
          body: {
            messages: userMemory
              ? [{ role: "system", content: `User memory:\n${userMemory}` }, ...history]
              : history,
            language,
            mode: wantsImage ? "image" : undefined,
            prefs: { personality, length: respLength, kidsMode },
            attachments: attachments.map((a) => ({
              url: a.url,
              type: a.type,
              name: a.name,
              data: a.data,
            })),
          },
        });
        data = invokeResult.data;
        error = invokeResult.error;
      }

      if (error) throw error;
      if (data?.error) throw new Error(data.error);
      let assistantAttachments: Attachment[] = [];
      let storedAssistantAttachments: Attachment[] = [];
      const reply: string = data.image_b64
        ? "Here's the image I created:"
        : data.video_b64
        ? "Here's your animated video:"
        : data.content || "(no response)";
      const provider: string = data.provider;

      // Handle generated image
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

      // Handle generated video (image-to-video animation)
      if (data.video_b64) {
        const filename = `animated-${Date.now()}.mp4`;
        const path = `${userId}/generated/${filename}`;
        try {
          const blob = base64ToBlob(data.video_b64, "video/mp4");
          const { error: vidErr } = await supabase.storage
            .from("chat-uploads")
            .upload(path, blob, { contentType: "video/mp4", upsert: true });
          if (vidErr) throw vidErr;
          const { data: signed } = await supabase.storage
            .from("chat-uploads")
            .createSignedUrl(path, 60 * 60 * 24 * 7);
          assistantAttachments = [
            {
              name: filename,
              type: "video/mp4",
              size: blob.size,
              path,
              url: signed?.signedUrl || "",
              data: data.video_b64,
            },
          ];
          storedAssistantAttachments = stripLocalData(assistantAttachments);
        } catch {
          assistantAttachments = [
            {
              name: filename,
              type: "video/mp4",
              size: data.video_b64.length,
              url: `data:video/mp4;base64,${data.video_b64}`,
              data: data.video_b64,
            },
          ];
          storedAssistantAttachments = assistantAttachments;
        }
      }

      const aid = crypto.randomUUID();
      // Skip typewriter for image/video replies
      if (!data.image_b64 && !data.video_b64) {
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
      setAiStatusMsg("Thinking...");
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

  // === VOICE INPUT ===
  const startVoice = useCallback(() => {
    const SR = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (!SR) { setVoiceError("Voice input not supported in this browser. Try Chrome."); return; }
    const r = new SR();
    r.lang = language || "en-US";
    r.continuous = continuousVoice;
    r.interimResults = false;
    r.onresult = (e: any) => {
      const transcript = Array.from(e.results).map((r: any) => r[0].transcript).join(" ");
      setInput((prev) => (prev ? prev + " " + transcript : transcript));
      if (continuousVoice) {
        // Auto-send in continuous mode
        setTimeout(() => {
          const btn = document.getElementById("send-btn");
          btn?.click();
        }, 500);
      }
    };
    r.onerror = (e: any) => {
      setVoiceError(e.error === "not-allowed" ? "Microphone access denied." : `Voice error: ${e.error}`);
      setVoiceListening(false);
    };
    r.onend = () => setVoiceListening(false);
    recognitionRef.current = r;
    r.start();
    setVoiceListening(true);
    setVoiceError(null);
  }, [language, continuousVoice]);

  const stopVoice = useCallback(() => {
    recognitionRef.current?.stop();
    setVoiceListening(false);
    setContinuousVoice(false);
  }, []);

  // === PDF EXPORT ===
  const exportPdf = useCallback(async () => {
    setExportingPdf(true);
    try {
      const printWindow = window.open("", "_blank");
      if (!printWindow) { setExportingPdf(false); return; }
      const htmlContent = `<!DOCTYPE html><html><head><title>HI THERE — Chat Export</title>
      <style>body{font-family:sans-serif;max-width:800px;margin:40px auto;padding:0 20px;line-height:1.6;}
      .msg{margin:16px 0;padding:12px 16px;border-radius:8px;}
      .user{background:#f0f0f0;text-align:right;}
      .assistant{background:#e8f4fd;}
      .role{font-size:11px;color:#666;margin-bottom:4px;text-transform:uppercase;}
      pre{background:#f5f5f5;padding:8px;border-radius:4px;overflow-x:auto;font-size:12px;}
      h1{color:#333;border-bottom:2px solid #eee;padding-bottom:8px;}
      </style></head><body>
      <h1>HI THERE — Chat Export</h1>
      <p style="color:#666;font-size:12px;">Exported ${new Date().toLocaleString()}</p>
      ${messages.map(m => `<div class="msg ${m.role}"><div class="role">${m.role}</div>${m.content.replace(/
/g,"<br>").replace(/```[\w]*
([\s\S]*?)```/g,"<pre>$1</pre>")}</div>`).join("")}
      </body></html>`;
      printWindow.document.write(htmlContent);
      printWindow.document.close();
      printWindow.focus();
      setTimeout(() => { printWindow.print(); printWindow.close(); }, 500);
    } finally {
      setExportingPdf(false);
    }
  }, [messages]);

  // === MULTI-MODEL COMPARE ===
  const runCompare = useCallback(async (prompt: string) => {
    if (!userId) return;
    setComparing(true);
    setCompareResults(null);
    try {
      // Run same prompt through two different routes in parallel
      const [res1, res2] = await Promise.allSettled([
        supabase.functions.invoke("chat", { body: { messages: [{ role: "user", content: prompt }], prefs: { personality, length: respLength } } }),
        supabase.functions.invoke("chat", { body: { messages: [{ role: "user", content: prompt }], prefs: { personality: "direct", length: "short" } } }),
      ]);
      const a = res1.status === "fulfilled" ? (res1.value.data?.content || res1.value.data?.error || "No response") : "Failed";
      const b = res2.status === "fulfilled" ? (res2.value.data?.content || res2.value.data?.error || "No response") : "Failed";
      setCompareResults({ a, b });
    } finally {
      setComparing(false);
      setCompareMode(false);
    }
  }, [userId, personality, respLength]);

  // === LOAD USER MEMORY ===
  useEffect(() => {
    if (!userId) return;
    supabase.from("profiles").select("memory_summary").eq("user_id", userId).maybeSingle().then(({ data }) => {
      setUserMemory((data as any)?.memory_summary || "");
    });
  }, [userId]);

  // === AUTO-SAVE MEMORY from conversations ===
  const updateMemory = useCallback(async (newMessages: typeof messages) => {
    if (!userId || newMessages.length < 6) return;
    // Extract facts from recent messages and save as memory summary
    const recentText = newMessages.slice(-10).map(m => `${m.role}: ${m.content}`).join("
").slice(0, 3000);
    const { data } = await supabase.functions.invoke("chat", {
      body: {
        messages: [
          { role: "system", content: "You extract factual personal information from conversations to remember about the user. Output ONLY a bullet list of facts, max 10 items, starting with '- '. Only include real personal facts (name, job, location, preferences). Nothing else." },
          { role: "user", content: recentText },
        ],
        prefs: { personality: "direct", length: "short" },
      },
    });
    if (data?.content) {
      const newFacts = data.content;
      await supabase.from("profiles").update({ memory_summary: newFacts } as any).eq("user_id", userId);
      setUserMemory(newFacts);
    }
  }, [userId]);


  return (
    <div className={`flex h-screen flex-col bg-chat-surface bg-[radial-gradient(circle_at_top_left,var(--chat-glow),transparent_34%),linear-gradient(180deg,var(--chat-surface),var(--background))] ${fontSize === "sm" ? "text-[13px]" : fontSize === "lg" ? "text-[17px]" : "text-[15px]"}`}>
      {isOffline && (
        <div className="bg-destructive/15 px-3 py-1.5 text-center text-xs text-destructive">
          You're offline — messages will fail until your connection returns.
        </div>
      )}
      {auditOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4" onClick={() => setAuditOpen(false)}>
          <div className="max-h-[90vh] w-full max-w-xl overflow-auto rounded-2xl border border-border bg-card p-5 shadow-2xl" onClick={(e) => e.stopPropagation()}>
            <div className="mb-4 flex items-center justify-between">
              <h3 className="flex items-center gap-2 text-lg font-bold">
                <Activity className="h-5 w-5 text-primary" /> AI Central Nervous System
              </h3>
              <button onClick={() => setAuditOpen(false)} className="rounded p-1 hover:bg-accent"><X className="h-4 w-4" /></button>
            </div>

            {/* Live status */}
            <div className="mb-4 rounded-xl border border-border bg-background p-3">
              <p className="text-xs font-semibold text-muted-foreground mb-2">SYSTEM STATUS</p>
              <div className="flex items-center gap-2">
                <span className={`h-2 w-2 rounded-full ${sending ? "bg-yellow-400 animate-pulse" : "bg-green-500"}`} />
                <span className="text-sm">{sending ? aiStatusMsg : "Ready — all systems online"}</span>
              </div>
              <p className="mt-1 text-xs text-muted-foreground">
                Memory: {userMemory ? "✅ Active" : "⬜ None"} &nbsp;|&nbsp;
                Voice: {voiceListening ? "🎤 Listening" : "⬜ Off"} &nbsp;|&nbsp;
                Kids mode: {kidsMode ? "🔒 On" : "⬜ Off"}
              </p>
            </div>

            {/* Provider health */}
            {auditing && (
              <div className="mb-4 rounded-xl border border-border bg-background p-3 text-sm text-muted-foreground animate-pulse">
                Pinging all AI services...
              </div>
            )}
            {auditReport && (
              <div className="mb-4 rounded-xl border border-border bg-background p-3">
                <p className="text-xs font-semibold text-muted-foreground mb-2">PROVIDER STATUS</p>
                <pre className="whitespace-pre-wrap text-xs leading-relaxed">{auditReport}</pre>
              </div>
            )}

            {/* Overseer controls */}
            <div className="mb-4 rounded-xl border border-border bg-background p-3">
              <p className="text-xs font-semibold text-muted-foreground mb-2">OVERSEER CONTROLS</p>
              <div className="grid grid-cols-2 gap-2">
                <button
                  onClick={runSelfAudit}
                  disabled={auditing}
                  className="flex items-center gap-1.5 rounded-lg border border-border bg-card px-3 py-2 text-xs hover:bg-accent"
                >
                  <Activity className="h-3 w-3" /> Run health check
                </button>
                <button
                  onClick={exportPdf}
                  disabled={exportingPdf}
                  className="flex items-center gap-1.5 rounded-lg border border-border bg-card px-3 py-2 text-xs hover:bg-accent"
                >
                  <Download className="h-3 w-3" /> Export chat PDF
                </button>
                <button
                  onClick={() => { setCompareMode(true); setAuditOpen(false); }}
                  className="flex items-center gap-1.5 rounded-lg border border-border bg-card px-3 py-2 text-xs hover:bg-accent"
                >
                  <Sparkles className="h-3 w-3" /> Multi-model compare
                </button>
                <button
                  onClick={() => { setContinuousVoice(true); startVoice(); setAuditOpen(false); }}
                  className="flex items-center gap-1.5 rounded-lg border border-border bg-card px-3 py-2 text-xs hover:bg-accent"
                >
                  <Mic className="h-3 w-3" /> Start voice mode
                </button>
              </div>
            </div>

            {/* Code verification notice */}
            <div className="rounded-xl border border-amber-500/30 bg-amber-500/5 p-3">
              <p className="text-xs font-semibold text-amber-600 mb-1">⚠️ CODE EDITING SAFETY</p>
              <p className="text-xs text-muted-foreground">
                Before applying any code changes from this AI to your GitHub/Vercel project,
                always verify: (1) the full file was returned, not a partial snippet,
                (2) all imports at the top are intact, (3) no export is missing at the bottom.
                The AI will always tell you which file to replace and where it goes.
              </p>
            </div>

            {isAdmin && (
              <div className="mt-3">
                <Link to="/admin" onClick={() => setAuditOpen(false)}
                  className="flex items-center gap-1.5 rounded-lg border border-primary/30 bg-primary/5 px-3 py-2 text-xs text-primary hover:bg-primary/10">
                  <Shield className="h-3 w-3" /> Open admin panel
                </Link>
              </div>
            )}
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
                    <div className="mx-auto mb-5 grid h-16 w-16 place-items-center rounded-2xl bg-gradient-to-br from-primary via-violet-600 to-indigo-600 shadow-xl ring-4 ring-primary/20">
                      <Sparkles className="h-8 w-8 text-white" />
                    </div>
                    <h2 className="text-2xl font-bold tracking-tight sm:text-3xl">
                      {timeGreeting}{displayName ? `, ${displayName}` : ""}
                    </h2>
                    <p className="mt-2 text-sm text-muted-foreground max-w-sm mx-auto">
                      I can read files, generate images, write and fix code, answer questions, and much more.
                    </p>
                    <div className="mt-4 flex flex-wrap justify-center gap-2 text-xs text-muted-foreground">
                      {["📎 Upload ZIP or PDF","🖼️ Generate images","🎤 Voice input","💻 Write & fix code","🔍 Search the web","🌍 Yoruba • Igbo • Hausa • Pidgin"].map(c => (
                        <span key={c} className="rounded-full border border-border bg-card px-3 py-1">{c}</span>
                      ))}
                    </div>
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

              {messages.map((m) => {
                // Group all image attachments for carousel
                const imgAttachments = (m.attachments || []).filter((a: Attachment) => a.type?.startsWith("image/"));
                const otherAttachments = (m.attachments || []).filter((a: Attachment) => !a.type?.startsWith("image/"));
                const isUser = m.role === "user";
                return (
                <div
                  key={m.id}
                  className={`flex gap-2 sm:gap-3 ${isUser ? "justify-end" : "justify-start"}`}
                >
                  {/* Avatar — assistant only */}
                  {!isUser && (
                    <div className="mt-1 shrink-0">
                      <div className="grid h-8 w-8 place-items-center rounded-xl bg-gradient-to-br from-primary to-violet-600 shadow-sm">
                        <Sparkles className="h-4 w-4 text-white" />
                      </div>
                    </div>
                  )}
                  <div className={`group flex flex-col ${isUser ? "items-end" : "items-start"} max-w-[85%] sm:max-w-[80%]`}>
                    {/* Image carousel — shown above text for rich media responses */}
                    {imgAttachments.length > 0 && (
                      <div className="mb-2 w-full max-w-sm">
                        <ImageCarousel images={imgAttachments.map((a: Attachment) => ({
                          src: a.data ? `data:${a.type};base64,${a.data}` : a.url,
                          name: a.name,
                        }))} />
                      </div>
                    )}
                    <div
                      className={`rounded-2xl text-sm ${
                        isUser
                          ? "bg-primary text-primary-foreground px-4 py-3"
                          : "bg-card border border-border/60 px-4 py-3 shadow-sm"
                      }`}
                    >
                    <MD text={m.content} dark={isUser} />
                    {/* Artifact renderer — shows live preview button for HTML/React */}
                    {!isUser && (() => {
                      const codeMatch = /```(html|jsx|tsx|react|svg)([\s\S]*?)```/i.exec(m.content);
                      if (!codeMatch) return null;
                      return <ArtifactRenderer code={codeMatch[2].trim()} lang={codeMatch[1].toLowerCase()} />;
                    })()}
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
                    {/* Artifact renderer — live preview for HTML/React code blocks */}
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
                    {/* Other attachments (PDF, ZIP, video) */}
                    {otherAttachments.length > 0 && (
                      <div className="mt-2 w-full space-y-2">
                        {otherAttachments.map((a: Attachment, i: number) => (
                          <AttachmentPreview key={`${a.name}-${i}`} attachment={a} />
                        ))}
                      </div>
                    )}
                  </div>
                  {/* User avatar */}
                  {isUser && (
                    <div className="mt-1 shrink-0">
                      <div className="grid h-8 w-8 place-items-center rounded-xl bg-muted border border-border text-xs font-bold text-muted-foreground">
                        {displayName?.[0]?.toUpperCase() || "U"}
                      </div>
                    </div>
                  )}
                </div>
                );
              })}

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
                <div className="flex gap-2 sm:gap-3 justify-start">
                  <div className="mt-1 shrink-0">
                    <div className="grid h-8 w-8 place-items-center rounded-xl bg-gradient-to-br from-primary to-violet-600 shadow-sm animate-pulse">
                      <Sparkles className="h-4 w-4 text-white" />
                    </div>
                  </div>
                  <div className="rounded-2xl border border-border/60 bg-card px-4 py-3 text-sm text-muted-foreground shadow-sm">
                    <span className="flex items-center gap-2">
                      <span className="inline-flex gap-1">
                        <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-primary [animation-delay:-0.3s]" />
                        <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-primary [animation-delay:-0.15s]" />
                        <span className="h-1.5 w-1.5 animate-bounce rounded-
