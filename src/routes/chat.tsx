import { createFileRoute, useNavigate, Link } from "@tanstack/react-router";
import { useEffect, useMemo, useRef, useState, useCallback } from "react";
import { supabase } from "@/integrations/supabase/client";
import {
  Sparkles, Send, Plus, Copy, LogOut, Shield, Check, Menu, X,
  Settings as SettingsIcon, Search, Paperclip, Camera, Languages,
  User, Pencil, Volume2, VolumeX, Share2, Flag, Trash2, Download,
  FileText, Film, ChevronDown, ChevronLeft, ChevronRight, Bookmark,
  BookmarkCheck, Activity, FileDown, Bug,
  Type as TypeIcon, Mic, MicOff, Globe, Brain,
  Star, ExternalLink, Play, Image as ImageIcon,
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

// - Puter.js global -
declare const puter: {
  ai: { chat: (msgs: {role:string;content:string}[], opts?: {model?:string}) => Promise<any> };
  auth: { isSignedIn: () => Promise<boolean>; signIn: () => Promise<void> };
};

async function callPuterAI(msgs: {role:string;content:string}[]): Promise<string> {
  if (typeof puter === "undefined") {
    await new Promise<void>((resolve, reject) => {
      let w = 0;
      const iv = setInterval(() => {
        w += 100;
        if (typeof puter !== "undefined") { clearInterval(iv); resolve(); }
        if (w >= 4000) { clearInterval(iv); reject(new Error("Puter unavailable")); }
      }, 100);
    });
  }
  try { const si = await puter.auth.isSignedIn(); if (!si) await puter.auth.signIn(); } catch {}
  const res = await puter.ai.chat(msgs, { model: "claude-sonnet-4-6" });
  if (typeof res === "string") return res;
  if (res?.message?.content?.[0]?.text) return res.message.content[0].text;
  if (res?.content?.[0]?.text) return res.content[0].text;
  if (res?.text) return res.text;
  return String(res || "");
}

// - Pollinations.ai image generation -- NO API KEY NEEDED -
type ImgOpts = { aspect: "1:1"|"9:16"|"16:9"; quantity: number; quality: "standard"|"high" };
function aspectDims(a: ImgOpts["aspect"], q: ImgOpts["quality"]) {
  const hi = q === "high";
  if (a === "9:16") return hi ? { w: 896, h: 1600 } : { w: 640, h: 1152 };
  if (a === "16:9") return hi ? { w: 1600, h: 896 } : { w: 1152, h: 640 };
  return hi ? { w: 1280, h: 1280 } : { w: 1024, h: 1024 };
}
function slugifyPrompt(text: string, max = 48): string {
  const cleaned = text.toLowerCase().split("").map(ch => {
    const c = ch.charCodeAt(0);
    if ((c >= 48 && c <= 57) || (c >= 97 && c <= 122)) return ch;
    return "-";
  }).join("");
  const parts = cleaned.split("-").filter(Boolean).slice(0, 8);
  const slug = parts.join("-").slice(0, max);
  return slug || "image";
}
async function generateImagePollinations(prompt: string, opts?: Partial<ImgOpts>): Promise<string[]> {
  const o: ImgOpts = { aspect: opts?.aspect || "1:1", quantity: Math.max(1, Math.min(4, opts?.quantity || 1)), quality: opts?.quality || "standard" };
  const { w, h } = aspectDims(o.aspect, o.quality);
  const encoded = encodeURIComponent(prompt);
  const models = ["flux", "turbo", "seedream"];
  const out: string[] = [];
  for (let i = 0; i < o.quantity; i++) {
    const seed = Math.floor(Math.random() * 1_000_000);
    let ok = false;
    for (const model of models) {
      try {
        const url = "https://image.pollinations.ai/prompt/" + encoded + "?model=" + model + "&width=" + w + "&height=" + h + "&seed=" + seed + "&nologo=true" + (o.quality === "high" ? "&enhance=true" : "");
        const r = await fetch(url);
        if (!r.ok) continue;
        const buf = new Uint8Array(await r.arrayBuffer());
        let bin = "";
        for (let j = 0; j < buf.length; j++) bin += String.fromCharCode(buf[j]);
        out.push(btoa(bin));
        ok = true;
        break;
      } catch { continue; }
    }
    if (!ok && out.length === 0) throw new Error("Image generation unavailable");
  }
  if (!out.length) throw new Error("Image generation unavailable");
  return out;
}

// - Types -
type Attachment = { name: string; url: string; type: string; size: number; path?: string; data?: string; text?: string };
type Msg = {
  id: string; role: "user"|"assistant"; content: string; created_at: string;
  provider?: string|null; attachments?: Attachment[];
  thinking?: string; citations?: {title:string;url:string}[];
};
type Conv = { id: string; title: string; updated_at: string };

// - File helpers -- NO regex anywhere -
const TEXT_EXTS = ["txt","md","markdown","json","jsonl","csv","tsv","xml","yml","yaml","html","htm","css","scss","less","js","jsx","ts","tsx","py","rb","go","rs","java","kt","swift","c","h","cpp","hpp","cs","php","sh","bash","zsh","sql","toml","ini","env","log","gitignore","dockerfile","lock"];
const TEXT_MIMES = ["text/","application/json","application/xml","application/x-yaml","application/javascript","application/typescript"];
const isText = (f:{type?:string;name:string}) => TEXT_EXTS.includes(f.name.split(".").pop()?.toLowerCase() || "") || TEXT_MIMES.some(m => (f.type||"").startsWith(m));
const isImg = (a:Attachment) => a.type?.startsWith("image/");
const isVid = (a:Attachment) => a.type?.startsWith("video/");
const isAud = (a:Attachment) => a.type?.startsWith("audio/");
const isPdf = (a:Attachment) => a.type === "application/pdf" || a.name.toLowerCase().endsWith(".pdf");
const stripData = (arr:Attachment[]) => arr.map(({data:_,text:__,...r})=>r);
const SKIP_DIRS = ["node_modules",".git",".next","dist","build",".cache",".turbo","coverage"];
const SKIP_FILES = ["package-lock.json","bun.lock","yarn.lock","pnpm-lock.yaml"];

function b64ToBlob(b64:string, type:string) {
  const bin = atob(b64); const bytes = new Uint8Array(bin.length);
  for (let i=0;i<bin.length;i++) bytes[i]=bin.charCodeAt(i);
  return new Blob([bytes],{type});
}
async function fileToB64(file:File): Promise<string> {
  return new Promise((res,rej)=>{const r=new FileReader();r.onload=()=>res(String(r.result||"").split(",")[1]||"");r.onerror=rej;r.readAsDataURL(file);});
}
async function readText(file:Blob, max=200000): Promise<string> {
  const sl = file.size > max ? file.slice(0,max) : file;
  const t = await sl.text();
  return file.size > max ? t + "\n[truncated -- " + file.size + " bytes total]" : t;
}
function guesslang(name:string) {
  const ext = name.split(".").pop()?.toLowerCase() || "";
  const m: Record<string,string> = {ts:"ts",tsx:"tsx",js:"js",jsx:"jsx",json:"json",css:"css",scss:"scss",html:"html",md:"md",py:"py",go:"go",rs:"rs",java:"java",sh:"bash",sql:"sql",yml:"yaml",yaml:"yaml",toml:"toml",env:"bash"};
  return m[ext]||"";
}

// - PDF extraction -
async function extractPdf(file:File): Promise<string> {
  try {
    if (!(window as any).pdfjsLib) {
      await new Promise<void>((res,rej)=>{const s=document.createElement("script");s.src="https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.min.js";s.onload=()=>res();s.onerror=()=>rej(new Error("pdf.js failed"));document.head.appendChild(s);});
      (window as any).pdfjsLib.GlobalWorkerOptions.workerSrc="https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.worker.min.js";
    }
    const lib = (window as any).pdfjsLib;
    const buf = await file.arrayBuffer();
    const pdf = await lib.getDocument({data:buf}).promise;
    let out = "=== PDF: " + file.name + " (" + pdf.numPages + " pages) ===\n\n";
    for (let i=1;i<=Math.min(pdf.numPages,50);i++) {
      const p = await pdf.getPage(i);
      const tc = await p.getTextContent();
      out += "--- Page " + i + " ---\n" + tc.items.map((x:any)=>x.str).join(" ") + "\n\n";
    }
    return out;
  } catch(e:any) { return "PDF could not be read: " + (e?.message||e); }
}

// - ZIP extraction -- zero regex -
async function extractZip(file:File): Promise<string> {
  const {default:JSZip} = await import("jszip");
  let zip:any;
  try { zip = await JSZip.loadAsync(file); }
  catch(e:any) {
    const msg = String(e?.message||e).toLowerCase();
    if (msg.includes("password")) return "ZIP is password-protected.";
    return "ZIP could not be opened: " + msg;
  }
  let entries = Object.values(zip.files).filter((e:any)=>!e.dir) as any[];
  const tops = new Set(entries.map((e:any)=>e.name.split("/")[0]));
  let wrap = "";
  if (tops.size===1) wrap = [...tops][0]+"/";
  const norm = (n:string) => wrap && n.startsWith(wrap) ? n.slice(wrap.length) : n;
  entries = entries.filter((e:any)=>{
    const n = norm(e.name); const parts = n.split("/");
    if (SKIP_DIRS.some(d=>parts.includes(d))) return false;
    if (SKIP_FILES.some(f=>parts[parts.length-1]===f)) return false;
    return true;
  });
  const txts = entries.filter((e:any)=>isText({name:e.name}));
  const bins = entries.filter((e:any)=>!isText({name:e.name}));
  const KEY = ["package.json","tsconfig","vite.config","tailwind","index.html","README","/routes/","/App.","src/main","router.tsx","/integrations/","supabase"];
  txts.sort((a:any,b:any)=>(KEY.some(k=>a.name.toLowerCase().includes(k.toLowerCase()))?0:1)-(KEY.some(k=>b.name.toLowerCase().includes(k.toLowerCase()))?0:1));
  const fileList = [...txts.map((e:any)=>"  [TEXT] "+norm(e.name)),...bins.map((e:any)=>"  [BIN]  "+norm(e.name))].join("\n");
  let body=""; let budget=300000; let included=0;
  for (const e of txts) {
    if (budget<=0){body+="\n\n[Budget reached -- ask for specific files]";break;}
    try {
      const raw:string = await (e as any).async("string");
      if (!raw||raw.trim().length===0){body+="\n\n[EMPTY: "+norm(e.name)+"]";included++;continue;}
      const cut = Math.min(30000,budget);
      const snip = raw.slice(0,cut);
      budget-=snip.length; included++;
      body+="\n\n```"+guesslang(e.name)+" "+norm(e.name)+"\n"+snip+(raw.length>cut?"\n[truncated]":"")+"\n```";
    } catch(err:any){body+="\n[Failed: "+norm(e.name)+": "+(err?.message||"error")+"]";}
  }
  return ["=== ZIP: \""+file.name+"\" ===","Files: "+entries.length+" total | "+included+"/"+txts.length+" text read | "+bins.length+" binary","Wrapper stripped: "+(wrap||"none"),"","CRITICAL: Use ONLY the content below. Never guess or invent file contents.","If a file is not shown, say so clearly.","","=== FILE LIST ===",fileList,"","=== CONTENTS ==="].join("\n")+body;
}

// - Collapsible code block -
function Code({code,lang,filename}:{code:string;lang?:string;filename?:string}) {
  const lines = code.split("\n").length;
  const [open,setOpen] = useState(!filename&&lines<=15);
  const [copied,setCopied] = useState(false);
  const lbl = filename||(lang?lang+" snippet":"code");
  const copy = async()=>{try{await navigator.clipboard.writeText(code);setCopied(true);setTimeout(()=>setCopied(false),1500);}catch{}};
  const dl = ()=>{const b=new Blob([code],{type:"text/plain"});const u=URL.createObjectURL(b);const a=document.createElement("a");a.href=u;a.download=filename?.split("/").pop()||("snippet."+(lang||"txt"));a.click();URL.revokeObjectURL(u);};
  const isRunnable = lang==="html"||lang==="jsx"||lang==="tsx"||lang==="react"||lang==="svg";
  const [preview,setPreview] = useState(false);
  let iframeDoc = code;
  if ((lang==="jsx"||lang==="tsx"||lang==="react")&&preview) {
    iframeDoc = ["<!DOCTYPE html><html><head>","<script src=\"https://unpkg.com/react@18/umd/react.development.js\"></script>","<script src=\"https://unpkg.com/react-dom@18/umd/react-dom.development.js\"></script>","<script src=\"https://unpkg.com/@babel/standalone/babel.min.js\"></script>","<style>body{margin:0;font-family:sans-serif;}</style>","</head><body><div id=\"root\"></div>","<script type=\"text/babel\">"+code+"\nconst r=ReactDOM.createRoot(document.getElementById('root'));r.render(React.createElement(typeof App!=='undefined'?App:()=>React.createElement('div',null,'Component loaded'),null));</script>","</body></html>"].join("\n");
  }
  return (
    <div className="my-3 overflow-hidden rounded-2xl border border-white/10 bg-black/40 shadow-lg backdrop-blur-sm">
      <div className="flex items-center gap-2 border-b border-white/10 bg-white/5 px-3 py-2 text-[11px]">
        <button onClick={()=>setOpen(v=>!v)} className="flex flex-1 items-center gap-2 text-left text-zinc-400 hover:text-white">
          {open?<ChevronDown className="h-3.5 w-3.5 shrink-0"/>:<ChevronRight className="h-3.5 w-3.5 shrink-0"/>}
          <FileText className="h-3.5 w-3.5 shrink-0 text-primary"/>
          <span className="truncate font-mono">{lbl}</span>
          <span className="shrink-0 text-zinc-500">{lines} lines</span>
        </button>
        {isRunnable&&<button onClick={()=>{setPreview(v=>!v);if(!open)setOpen(true);}} className="rounded-md bg-primary/20 px-2 py-0.5 text-primary hover:bg-primary/30"><Play className="inline h-2.5 w-2.5"/> Preview</button>}
        <button onClick={copy} className="rounded-md px-2 py-0.5 text-zinc-400 hover:bg-white/10 hover:text-white">{copied?<Check className="h-3 w-3"/>:<Copy className="h-3 w-3"/>}</button>
        <button onClick={dl} className="rounded-md px-2 py-0.5 text-zinc-400 hover:bg-white/10 hover:text-white"><Download className="h-3 w-3"/></button>
      </div>
      {open&&<pre className="m-0 max-h-[500px] overflow-auto p-4 text-[12px] leading-relaxed text-zinc-200"><code>{code}</code></pre>}
      {open&&isRunnable&&preview&&<iframe srcDoc={iframeDoc} sandbox="allow-scripts allow-same-origin" className="h-72 w-full border-0 bg-white" title="preview"/>}
    </div>
  );
}

// - Image Carousel -
function Carousel({images}:{images:{src:string;name:string}[]}) {
  const [i,setI] = useState(0);
  if (!images.length) return null;
  const cur = images[i];
  const isGen = cur.name.startsWith("artwork-")||cur.name.startsWith("animation-");
  return (
    <div className="overflow-hidden rounded-2xl border border-white/10 bg-black/40 shadow-xl">
      <div className="relative">
        <img src={cur.src} alt={isGen?"AI Generated":cur.name} className="max-h-96 w-full object-contain bg-black/20"/>
        {images.length>1&&<>
          <button onClick={()=>setI(n=>(n-1+images.length)%images.length)} className="absolute left-2 top-1/2 -translate-y-1/2 rounded-full bg-black/60 p-2 text-white hover:bg-black/80"><ChevronLeft className="h-4 w-4"/></button>
          <button onClick={()=>setI(n=>(n+1)%images.length)} className="absolute right-2 top-1/2 -translate-y-1/2 rounded-full bg-black/60 p-2 text-white hover:bg-black/80"><ChevronRight className="h-4 w-4"/></button>
          <div className="absolute bottom-3 left-1/2 flex -translate-x-1/2 gap-1.5">
            {images.map((_,n)=><button key={n} onClick={()=>setI(n)} className={"h-1.5 w-1.5 rounded-full transition-all "+(n===i?"bg-white w-4":"bg-white/40")}/>)}
          </div>
        </>}
        {isGen&&(
          <div className="absolute top-2 right-2 flex gap-1">
            {["1:1","16:9","9:16","4:3"].map(r=><button key={r} className="rounded-lg bg-black/60 px-2 py-0.5 text-[10px] text-white backdrop-blur hover:bg-black/80">{r}</button>)}
          </div>
        )}
      </div>
      <div className="flex items-center justify-between px-3 py-2 text-[11px] text-zinc-400">
        <span>{isGen?"* AI Generated":(cur.name)}{images.length>1?" ("+(i+1)+"/"+images.length+")":""}</span>
        <div className="flex gap-2">
          {isGen&&<button onClick={()=>{const p=window.prompt("What to change?");if(p){const ev=new CustomEvent("aem-edit-image",{detail:{prompt:p}});window.dispatchEvent(ev);}}} className="text-primary hover:text-primary/80">Edit Edit</button>}
          <a href={cur.src} download={isGen?"aem-image.png":cur.name} className="flex items-center gap-1 rounded-lg bg-white/10 px-2 py-1 hover:bg-white/20"><Download className="h-3 w-3"/> Save</a>
        </div>
      </div>
    </div>
  );
}

// - Citation card -
function CitationCard({c}:{c:{title:string;url:string}}) {
  return (
    <a href={c.url} target="_blank" rel="noreferrer" className="flex items-center gap-2 rounded-xl border border-white/10 bg-white/5 px-3 py-2 text-xs hover:bg-white/10 transition-colors">
      <Globe className="h-3 w-3 shrink-0 text-primary"/>
      <span className="flex-1 truncate text-zinc-300">{c.title}</span>
      <ExternalLink className="h-3 w-3 shrink-0 text-zinc-500"/>
    </a>
  );
}

// - Attachment preview -
function AttachView({a}:{a:Attachment}) {
  const src = a.data&&isImg(a)?"data:"+a.type+";base64,"+a.data:a.url;
  if (isImg(a)) return <Carousel images={[{src,name:a.name}]}/>;
  if (isVid(a)) return <div className="overflow-hidden rounded-2xl border border-white/10 bg-black/40"><video src={src} controls playsInline className="max-h-80 w-full"/><div className="flex items-center justify-between px-3 py-2 text-[11px] text-zinc-400"><span><Film className="mr-1 inline h-3 w-3"/>{a.name}</span><a href={src} download={a.name} className="flex items-center gap-1 rounded-lg bg-white/10 px-2 py-1 hover:bg-white/20"><Download className="h-3 w-3"/> Save</a></div></div>;
  if (isAud(a)) return <div className="rounded-2xl border border-white/10 bg-black/40 p-3"><div className="mb-2 flex items-center justify-between text-[11px] text-zinc-400"><span>{a.name}</span><a href={src} download={a.name} className="flex items-center gap-1 rounded-lg bg-white/10 px-2 py-1 hover:bg-white/20"><Download className="h-3 w-3"/> Save</a></div><audio src={src} controls className="w-full"/></div>;
  if (isPdf(a)) return <div className="overflow-hidden rounded-2xl border border-white/10 bg-black/40"><iframe title={a.name} src={src} className="h-72 w-full"/><div className="flex items-center justify-between px-3 py-2 text-[11px] text-zinc-400"><span><FileText className="mr-1 inline h-3 w-3"/>{a.name}</span><a href={src} download={a.name} className="flex items-center gap-1 rounded-lg bg-white/10 px-2 py-1 hover:bg-white/20"><Download className="h-3 w-3"/> Save</a></div></div>;
  return <a href={src} target="_blank" rel="noreferrer" download={a.name} className="flex items-center justify-between gap-2 rounded-2xl border border-white/10 bg-white/5 px-3 py-2 text-xs hover:bg-white/10"><span className="truncate"><FileText className="mr-1 inline h-3 w-3"/>{a.name}</span><Download className="h-3.5 w-3.5 shrink-0"/></a>;
}

// - Main component -
function ChatPage() {
  const navigate = useNavigate();

  const friendlyErr = (e:unknown):string => {
    const err = e as {message?:string;error?:string};
    if (!navigator.onLine) return "You're offline. Check your connection.";
    const raw = (err?.message||err?.error||"").toString().toLowerCase();
    if (raw.includes("failed to fetch")||raw.includes("networkerror")||raw.includes("network request")) return "Network issue -- please try again in a moment.";
    if (raw.includes("429")||raw.includes("cooldown")||raw.includes("rate limit")||raw.includes("too many")) return "You've hit the daily limit. Try again later or upgrade your plan.";
    if (raw.includes("limit")&&raw.includes("day")) return "Daily message limit reached. Try again later or upgrade.";
    if (raw.includes("401")||raw.includes("unauthor")||raw.includes("jwt")) return "Session expired. Please sign in again.";
    if (raw.includes("edge function")||raw.includes("non-2xx")||raw.includes("502")||raw.includes("503")||raw.includes("504")||raw.includes("gateway")) return "AI is temporarily unavailable. Please try again shortly.";
    if (raw.includes("timeout")||raw.includes("timed out")) return "That took too long. Please try again.";
    if (raw.includes("image generation unavailable")) return "Image service is busy. Please try again in a moment.";
    if (raw.includes("all providers failed")||raw.includes("providers")) return "AI is offline right now. Please try again soon.";
    if (raw.includes("500")||raw.includes("internal")) return "Something went wrong on our side. Please try again.";
    return "AI unavailable right now. Please try again in a moment.";
  };

  // - State -
  const [userId,setUserId] = useState<string|null>(null);
  const [displayName,setDisplayName] = useState("");
  const [isAdmin,setIsAdmin] = useState(false);
  const [language,setLanguage] = useState("en");
  const [conversations,setConversations] = useState<Conv[]>([]);
  const [activeConvId,setActiveConvId] = useState<string|null>(null);
  const [messages,setMessages] = useState<Msg[]>([]);
  const [input,setInput] = useState("");
  const [sending,setSending] = useState(false);
  const [copiedId,setCopiedId] = useState<string|null>(null);
  const [translations,setTranslations] = useState<Record<string,string>>({});
  const [translatingId,setTranslatingId] = useState<string|null>(null);
  const [editingId,setEditingId] = useState<string|null>(null);
  const [editingText,setEditingText] = useState("");
  const [speakingId,setSpeakingId] = useState<string|null>(null);
  const [voiceName,setVoiceName] = useState("default");
  const [voices,setVoices] = useState<SpeechSynthesisVoice[]>([]);
  const [typing,setTyping] = useState("");
  const [search,setSearch] = useState("");
  const [drawerOpen,setDrawerOpen] = useState(false);
  const [profileOpen,setProfileOpen] = useState(false);
  const [pendingFiles,setPendingFiles] = useState<Attachment[]>([]);
  const [error,setError] = useState<string|null>(null);
  const [statusMsg,setStatusMsg] = useState("Thinking...");
  const [personality,setPersonality] = useState<"direct"|"friendly"|"detailed">(()=>(typeof localStorage!=="undefined"&&localStorage.getItem("aem-personality") as any)||"friendly");
  const [respLen,setRespLen] = useState<"short"|"normal"|"detailed">(()=>(typeof localStorage!=="undefined"&&localStorage.getItem("aem-length") as any)||"normal");
  const [fontSize,setFontSize] = useState<"sm"|"md"|"lg">(()=>(typeof localStorage!=="undefined"&&localStorage.getItem("aem-fontsize") as any)||"md");
  const [pinned,setPinned] = useState<Record<string,boolean>>(()=>{try{return JSON.parse(localStorage.getItem("aem-pinned")||"{}");}catch{return {};}});
  const [auditOpen,setAuditOpen] = useState(false);
  const [auditReport,setAuditReport] = useState<string|null>(null);
  const [auditing,setAuditing] = useState(false);
  const [isOffline,setIsOffline] = useState(()=>typeof navigator!=="undefined"&&!navigator.onLine);
  const [voiceListening,setVoiceListening] = useState(false);
  const [voiceError,setVoiceError] = useState<string|null>(null);
  const [memory,setMemory] = useState("");
  const [kidsMode,setKidsMode] = useState(()=>{try{return JSON.parse(localStorage.getItem("aem-caps")||"{}").kids_mode===true;}catch{return false;}});
  const [compareRes,setCompareRes] = useState<{a:string;b:string}|null>(null);
  const [comparing,setComparing] = useState(false);
  const [exportingPdf,setExportingPdf] = useState(false);
  const [aemMode,setAemMode] = useState(false);
  const [thinkingOpen,setThinkingOpen] = useState<string|null>(null);
  const [showBriefing,setShowBriefing] = useState(false);
  const [webSearchEnabled,setWebSearchEnabled] = useState(true);
  const [streamingMsg,setStreamingMsg] = useState("");
  const [imgAspect,setImgAspect] = useState<ImgOpts["aspect"]>(()=>((typeof localStorage!=="undefined"&&localStorage.getItem("aem-img-aspect")) as any)||"1:1");
  const [imgQty,setImgQty] = useState<number>(()=>{try{return Math.max(1,Math.min(4,parseInt(localStorage.getItem("aem-img-qty")||"1")||1));}catch{return 1;}});
  const [imgQuality,setImgQuality] = useState<ImgOpts["quality"]>(()=>((typeof localStorage!=="undefined"&&localStorage.getItem("aem-img-quality")) as any)||"standard");
  const [showImgOpts,setShowImgOpts] = useState(false);
  const [aiGreeting,setAiGreeting] = useState<string>("");
  const [faqItems,setFaqItems] = useState<string[]>([]);

  const scrollRef = useRef<HTMLDivElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const cameraRef = useRef<HTMLInputElement>(null);
  const recogRef = useRef<any>(null);

  // Persist prefs
  useEffect(()=>{try{localStorage.setItem("aem-personality",personality);}catch{}},[personality]);
  useEffect(()=>{try{localStorage.setItem("aem-length",respLen);}catch{}},[respLen]);
  useEffect(()=>{try{localStorage.setItem("aem-fontsize",fontSize);}catch{}},[fontSize]);
  useEffect(()=>{try{localStorage.setItem("aem-pinned",JSON.stringify(pinned));}catch{}},[pinned]);
  useEffect(()=>{try{localStorage.setItem("aem-img-aspect",imgAspect);}catch{}},[imgAspect]);
  useEffect(()=>{try{localStorage.setItem("aem-img-qty",String(imgQty));}catch{}},[imgQty]);
  useEffect(()=>{try{localStorage.setItem("aem-img-quality",imgQuality);}catch{}},[imgQuality]);
  useEffect(()=>{const on=()=>setIsOffline(false);const off=()=>setIsOffline(true);window.addEventListener("online",on);window.addEventListener("offline",off);return()=>{window.removeEventListener("online",on);window.removeEventListener("offline",off);};},[]);

  // TTS
  useEffect(()=>{if(!window.speechSynthesis)return;const load=()=>setVoices(window.speechSynthesis.getVoices());load();window.speechSynthesis.onvoiceschanged=load;return()=>{if(window.speechSynthesis)window.speechSynthesis.onvoiceschanged=null;};},[]);

  // Voice input
  const startVoice = useCallback(()=>{
    const SR=(window as any).SpeechRecognition||(window as any).webkitSpeechRecognition;
    if(!SR){setVoiceError("Voice not supported in this browser. Try Chrome.");return;}
    const r=new SR();
    r.lang=language==="yo"?"yo-NG":language==="ha"?"ha-NG":language==="ig"?"ig-NG":"en-NG";
    r.continuous=false;r.interimResults=false;
    r.onresult=(e:any)=>{const t=Array.from(e.results).map((x:any)=>x[0].transcript).join(" ");setInput(p=>p?p+" "+t:t);};
    r.onerror=(e:any)=>{setVoiceError(e.error==="not-allowed"?"Microphone access denied.":"Voice error: "+e.error);setVoiceListening(false);};
    r.onend=()=>setVoiceListening(false);
    recogRef.current=r;r.start();setVoiceListening(true);setVoiceError(null);
  },[language]);
  const stopVoice = useCallback(()=>{recogRef.current?.stop();setVoiceListening(false);},[]);

  // Image edit event
  useEffect(()=>{
    const h=(e:any)=>{const{prompt}=e.detail;if(prompt){setInput("Edit the last generated image: "+prompt);setTimeout(()=>document.getElementById("aem-send")?.click(),100);}};
    window.addEventListener("aem-edit-image",h);return()=>window.removeEventListener("aem-edit-image",h);
  },[]);

  // Load memory
  useEffect(()=>{if(!userId)return;supabase.from("profiles").select("memory_summary,display_name").eq("user_id",userId).maybeSingle().then(({data})=>{setMemory((data as any)?.memory_summary||"");setDisplayName((data as any)?.display_name||"");});},[userId]);

  // Scroll
  useEffect(()=>{scrollRef.current?.scrollTo({top:scrollRef.current.scrollHeight,behavior:"smooth"});},[messages,typing,streamingMsg]);

  // PDF export
  const exportPdf = useCallback(async()=>{
    setExportingPdf(true);
    try {
      const pw=window.open("","_blank");if(!pw){setExportingPdf(false);return;}
      const nl=String.fromCharCode(10);const br="<br>";
      const rows=messages.map(m=>"<div class=\"msg "+m.role+"\"><div class=\"role\">"+m.role+"</div>"+m.content.split(nl).join(br)+"</div>").join("");
      pw.document.write(["<!DOCTYPE html><html><head><title>HI THERE Chat Export</title>","<style>body{font-family:sans-serif;max-width:800px;margin:40px auto;padding:0 20px;line-height:1.6;}",".msg{margin:16px 0;padding:12px 16px;border-radius:12px;}",".user{background:#1e1e2e;color:#cdd6f4;text-align:right;}",".assistant{background:#181825;color:#cdd6f4;}",".role{font-size:11px;color:#6c7086;margin-bottom:4px;text-transform:uppercase;}","h1{color:#cba6f7;border-bottom:1px solid #313244;padding-bottom:8px;}","</style></head><body>","<h1>HI THERE -- Chat Export</h1>","<p style='color:#6c7086;font-size:12px;'>"+new Date().toLocaleString()+"</p>",rows,"</body></html>"].join(""));
      pw.document.close();pw.focus();setTimeout(()=>{pw.print();pw.close();},500);
    } finally{setExportingPdf(false);}
  },[messages]);

  // Compare
  const runCompare = useCallback(async(prompt:string)=>{
    setComparing(true);setCompareRes(null);
    try {
      const [r1,r2]=await Promise.allSettled([supabase.functions.invoke("chat",{body:{messages:[{role:"user",content:prompt}],prefs:{personality,length:respLen}}}),supabase.functions.invoke("chat",{body:{messages:[{role:"user",content:prompt}],prefs:{personality:"direct",length:"short"}}})]);
      setCompareRes({a:r1.status==="fulfilled"?(r1.value.data?.content||"No response"):"Failed",b:r2.status==="fulfilled"?(r2.value.data?.content||"No response"):"Failed"});
    } finally{setComparing(false);}
  },[personality,respLen]);

  // Greeting
  const greeting = useMemo(()=>{
    const h=new Date().getHours();
    const pools={morning:["Good morning -- let's build something today.","Morning! What are we working on?","Morning. Ready when you are."],afternoon:["Good afternoon -- what's next?","Afternoon! What do you need?","Afternoon. Let's keep it moving."],evening:["Evening -- still building?","Evening. What can I help with?","Evening -- let's wrap something up."],late:["Late night session. I'm here.","Up late? Let's make it count.","Quiet hours -- best time to focus."]};
    const pool=h<5?pools.late:h<12?pools.morning:h<17?pools.afternoon:h<22?pools.evening:pools.late;
    return pool[Math.floor(Math.random()*pool.length)];
  },[activeConvId]);

  // Suggestions -- zero regex
  const lastAI = useMemo(()=>[...messages].reverse().find(m=>m.role==="assistant"),[messages]);
  const suggestions = useMemo(()=>{
    if(!lastAI) return [];
    const c=lastAI.content.toLowerCase();
    const hasImg=lastAI.attachments?.some(a=>a.type?.startsWith("image/"));
    if(hasImg) return ["Modify this image","Generate a variation","Animate this image"];
    if(["bug","issue","error","fix","critical"].some(k=>c.includes(k))) return ["Fix the critical bug","Show affected files","Explain root cause"];
    if(lastAI.content.includes("```")) return ["Explain this code","Refactor it","Add TypeScript types","Write tests"];
    if(["zip","project","files","repository"].some(k=>c.includes(k))) return ["List important files","Find security issues","Suggest improvements"];
    return ["Tell me more","Give an example","Summarize key points"];
  },[lastAI]);

  // Slash commands
  const slashCmds = [
    {cmd:"/explain",desc:"Explain last code in plain English"},
    {cmd:"/fix",desc:"Find and fix all bugs"},
    {cmd:"/audit",desc:"Full project bug scan"},
    {cmd:"/compare",desc:"Compare two AI responses"},
    {cmd:"/summary",desc:"Summarise this conversation"},
    {cmd:"/export",desc:"Download conversation as Markdown"},
    {cmd:"/pdf",desc:"Export chat as PDF"},
    {cmd:"/image",desc:"Generate an image"},
    {cmd:"/health",desc:"Check all AI engine status"},
    {cmd:"/aem",desc:"Toggle AEM signature mode"},
    {cmd:"/think",desc:"Use deep reasoning mode"},
  ];
  const slashMatches = useMemo(()=>{const t=input.trim();if(!t.startsWith("/"))return[];return slashCmds.filter(c=>c.cmd.startsWith(t.split(" ")[0]));},[input]);

  // Markdown renderer
  const MD = ({text,dark}:{text:string;dark?:boolean})=>(
    <div className={"prose prose-sm max-w-none break-words "+(dark?"prose-invert":"dark:prose-invert")+" prose-p:my-1.5 prose-pre:my-0 prose-headings:my-2 prose-img:my-2 prose-img:rounded-2xl prose-a:text-primary prose-a:no-underline hover:prose-a:underline"}>
      <ReactMarkdown remarkPlugins={[remarkGfm,[remarkMath,{singleDollarTextMath:false}]]} rehypePlugins={[rehypeKatex]} components={{
        a:({href,children,...p})=><a href={href} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-primary hover:underline" {...p}>{children}<ExternalLink className="h-3 w-3"/></a>,
        img:({src,alt})=>src?<img src={src} alt={alt||"image"} className="max-h-96 w-full rounded-2xl object-contain"/>:null,
        pre:({children}:any)=>{
          const child:any=Array.isArray(children)?children[0]:children;
          const props=child?.props||{};
          const rawFull=String(props.children??"");
          const raw=rawFull.endsWith("\n")?rawFull.slice(0,-1):rawFull;
          const cls:string=props.className||"";
          const lp="language-";
          const lm=cls&&cls.startsWith(lp)?[null,cls.slice(lp.length)]:null;
          const lg=lm?.[1];
          const first=raw.split("\n")[0]||"";
          const hasFileHint=first.trim().includes("file:")||first.trim().includes("path:");
          const fh=hasFileHint?[null,first.trim().split(/file:|path:/i)[1]?.trim().split(" ")[0]||""]:null;
          const dotCount=first.trim().split(".").length-1;
          const isBare=first.trim().length>0&&first.trim().length<60&&dotCount>=1&&!first.trim().includes(" ");
          let body=raw; let fname:string|undefined;
          if(fh){fname=fh[1]||undefined;body=raw.split("\n").slice(1).join("\n");}
          else if(lg&&isBare){fname=first.trim();body=raw.split("\n").slice(1).join("\n");}
          return <Code code={body} lang={lg||undefined} filename={fname}/>;
        },
        code:({inline,className,children,...p}:any)=>inline?<code className="rounded-md bg-white/10 px-1.5 py-0.5 font-mono text-[0.85em] text-primary" {...p}>{children}</code>:<code className={className} {...p}>{children}</code>,
      }}>{text}</ReactMarkdown>
    </div>
  );

  // Refresh URLs
  const refreshUrls = async(rows:Msg[]):Promise<Msg[]>=>{
    return Promise.all(rows.map(async m=>{
      if(!m.attachments?.length) return m;
      const atts=await Promise.all(m.attachments.map(async a=>{
        if(!a.path||(a.url&&a.url.startsWith("data:"))) return a;
        const{data}=await supabase.storage.from("chat-uploads").createSignedUrl(a.path,60*60*24*7);
        return{...a,url:data?.signedUrl||a.url};
      }));
      return{...m,attachments:atts};
    }));
  };

  // Auth
  useEffect(()=>{
    supabase.auth.getSession().then(({data})=>{if(!data.session){navigate({to:"/auth"});return;}setUserId(data.session.user.id);});
    const{data:sub}=supabase.auth.onAuthStateChange((_e,session)=>{if(!session)navigate({to:"/auth"});});
    return()=>sub.subscription.unsubscribe();
  },[navigate]);

  useEffect(()=>{
    if(!userId) return;
    (async()=>{
      const[{data:roles},{data:profile}]=await Promise.all([supabase.from("user_roles").select("role").eq("user_id",userId),supabase.from("profiles").select("subscription_plan,language,voice_name,display_name").eq("user_id",userId).maybeSingle()]);
      setIsAdmin(!!roles?.some((r:any)=>r.role==="admin"));
      if(profile){setLanguage((profile as any).language||"en");setVoiceName((profile as any).voice_name||"default");setDisplayName((profile as any).display_name||"");}
      await loadConvs();
    })();
  },[userId]);

  const loadConvs = async()=>{
    const{data}=await supabase.from("conversations").select("id,title,updated_at").order("updated_at",{ascending:false});
    setConversations(data??[]);
    if(data&&data.length&&!activeConvId) setActiveConvId(data[0].id);
  };

  useEffect(()=>{
    if(!activeConvId){setMessages([]);return;}
    (async()=>{const{data}=await supabase.from("messages").select("*").eq("conversation_id",activeConvId).order("created_at",{ascending:true});setMessages(await refreshUrls((data??[]) as unknown as Msg[]));})();
  },[activeConvId]);

  const filteredConvs = useMemo(()=>{if(!search.trim()) return conversations;const q=search.toLowerCase();return conversations.filter(c=>c.title.toLowerCase().includes(q));},[conversations,search]);

  const newChat = async()=>{
    if(!userId) return;
    const{data,error:err}=await supabase.from("conversations").insert({user_id:userId,title:"New Chat"}).select().single();
    if(!err&&data){setConversations(c=>[data as Conv,...c]);setActiveConvId(data.id);setMessages([]);setDrawerOpen(false);}
  };

  // File handling
  const handleFiles = async(files:FileList|null)=>{
    if(!files||!userId) return;
    const uploads:Attachment[]=[];
    for(const f of Array.from(files)){
      if(f.size>20*1024*1024){setError(f.name+" is too large (max 20MB)");continue;}
      const safe=f.name.split("").map(c=>{const code=c.charCodeAt(0);return(code>=48&&code<=57)||(code>=65&&code<=90)||(code>=97&&code<=122)||c==="."||c==="_"||c==="-"?c:"-";}).join("");
      const path=userId+"/"+Date.now()+"-"+safe;
      const previewData=f.type.startsWith("image/")&&f.size<=6*1024*1024?await fileToB64(f):undefined;
      let textContent:string|undefined;
      try{
        if(f.name.toLowerCase().endsWith(".zip")||f.type==="application/zip") textContent=await extractZip(f);
        else if(f.type==="application/pdf"||f.name.toLowerCase().endsWith(".pdf")) textContent=await extractPdf(f);
        else if(isText(f)&&f.size<=2*1024*1024) textContent=await readText(f);
      }catch(e){console.warn("file read",f.name,e);}
      const{error:ue}=await supabase.storage.from("chat-uploads").upload(path,f,{contentType:f.type,upsert:true});
      if(ue){setError("Could not upload "+f.name);continue;}
      const{data:signed}=await supabase.storage.from("chat-uploads").createSignedUrl(path,60*60*24*7);
      uploads.push({name:f.name,url:signed?.signedUrl||"",type:f.type||"application/octet-stream",size:f.size,path,data:previewData,text:textContent});
    }
    setPendingFiles(p=>[...p,...uploads]);
  };

  // Self audit
  const runAudit = async()=>{
    setAuditOpen(true);setAuditing(true);setAuditReport(null);
    try{
      const{data,error:e}=await supabase.functions.invoke("chat",{body:{mode:"audit",messages:[]}});
      if(e) throw e;
      const provs=data?.providers||{};
      const lines=["**AEM System Health Report** -- "+new Date().toLocaleString(),"","**AI Engines:**",...Object.entries(provs).map(([k,v]:any)=>"- "+(v.ok?"on":"x")+" **"+k+"** "+(v.ok?v.ms+"ms":(v.error||"offline"))),"","**Image Studio:** "+(data?.image_generation?.ok?"on Pollinations.ai active":"x check config"),"**ZIP Reader:** on byte-verified extraction","**PDF Reader:** on pdf.js active","**Voice Input:** on Web Speech API","**Puter.js:** on Claude Sonnet browser layer","","*AEM Adaptive Router is managing all routing automatically.*"];
      setAuditReport(lines.join("\n"));
    }catch(e:any){setAuditReport("Audit failed: "+friendlyErr(e));}finally{setAuditing(false);}
  };

  const fmtTime=(s:string)=>new Date(s).toLocaleTimeString([],{hour:"2-digit",minute:"2-digit"});

  const copy=async(id:string,text:string)=>{await navigator.clipboard.writeText(text);setCopiedId(id);setTimeout(()=>setCopiedId(null),1500);};
  const togglePin=(id:string)=>setPinned(p=>({...p,[id]:!p[id]}));
  const saveVoice=async(n:string)=>{setVoiceName(n);if(userId)await supabase.from("profiles").update({voice_name:n} as never).eq("user_id",userId);};
  const delMsg=async(m:Msg)=>{if(!window.confirm("Delete this message?"))return;await supabase.from("messages").delete().eq("id",m.id);setMessages(ms=>ms.filter(x=>x.id!==m.id));};
  const delConv=async(id:string)=>{if(!window.confirm("Delete this conversation?"))return;await supabase.from("conversations").delete().eq("id",id);setConversations(cs=>cs.filter(c=>c.id!==id));if(activeConvId===id){const next=conversations.find(c=>c.id!==id)?.id??null;setActiveConvId(next);if(!next)setMessages([]);}};
  const dlConv=(id=activeConvId)=>{if(!id)return;const conv=conversations.find(c=>c.id===id);const rows=id===activeConvId?messages:[];const body=rows.length?rows.map(m=>"## "+(m.role==="assistant"?"HI THERE":"You")+" -- "+new Date(m.created_at).toLocaleString()+"\n\n"+m.content).join("\n\n---\n\n"):"Open this chat first.";const blob=new Blob(["# "+(conv?.title||"Chat")+"\n\n"+body],{type:"text/markdown"});const u=URL.createObjectURL(blob);const a=document.createElement("a");a.href=u;const st=(conv?.title||"chat").split("").map(c=>{const code=c.charCodeAt(0);return(code>=48&&code<=57)||(code>=65&&code<=90)||(code>=97&&code<=122)?c:"-";}).join("").slice(0,50);a.download=st+".md";a.click();URL.revokeObjectURL(u);};
  const startEdit=(m:Msg)=>{setEditingId(m.id);setEditingText(m.content);};
  const saveEdit=async(m:Msg)=>{const t=editingText.trim();if(!t||t===m.content){setEditingId(null);return;}await supabase.from("messages").update({content:t}).eq("id",m.id);setMessages(ms=>ms.map(x=>x.id===m.id?{...x,content:t}:x));setEditingId(null);};
  const speak=(m:Msg)=>{if(!window.speechSynthesis){setError("Speech not supported");return;}if(speakingId===m.id){window.speechSynthesis.cancel();setSpeakingId(null);return;}window.speechSynthesis.cancel();const u=new SpeechSynthesisUtterance(m.content);const fn=["female","samantha","victoria","zira","karen","tessa"];const mn=["male","daniel","alex","david","fred"];const wf=voiceName.toLowerCase().includes("female");const wm=voiceName.toLowerCase().includes("male");const pick=voices.find(v=>v.name.toLowerCase().includes(voiceName.toLowerCase()))??voices.find(v=>wf&&fn.some(n=>v.name.toLowerCase().includes(n)))??voices.find(v=>wm&&mn.some(n=>v.name.toLowerCase().includes(n)))??voices[0];if(pick)u.voice=pick;u.onend=()=>setSpeakingId(null);setSpeakingId(m.id);window.speechSynthesis.speak(u);};
  const shareMsg=async(m:Msg)=>{
  const text="From HI THERE:\n\n"+m.content;
  const nav=navigator as any;
  // Try native share (includes WhatsApp, Telegram etc on mobile)
  if(nav.share){try{await nav.share({title:"HI THERE",text,url:window.location.href});return;}catch{}}
  // Fallback: copy to clipboard
  await navigator.clipboard.writeText(text);setCopiedId(m.id);setTimeout(()=>setCopiedId(null),1500);
};
const shareToWhatsApp=(m:Msg)=>{const text=encodeURIComponent("From HI THERE:\n\n"+m.content.slice(0,500));window.open("https://wa.me/?text="+text,"_blank");};
const downloadAsFile=(m:Msg)=>{
  const blob=new Blob([m.content],{type:"text/plain"});
  const url=URL.createObjectURL(blob);const a=document.createElement("a");
  a.href=url;a.download="hi-there-response.txt";a.click();URL.revokeObjectURL(url);
};
  const report=async(m:Msg)=>{const reason=window.prompt("Why are you reporting this? (optional)");if(reason===null)return;await supabase.from("reports").insert({user_id:userId!,message_id:m.id,conversation_id:activeConvId,content:m.content,reason:reason||null});alert("Report sent. Thank you.");};
  const translate=async(m:Msg)=>{setTranslatingId(m.id);try{const{data,error:e}=await supabase.functions.invoke("chat",{body:{messages:[{role:"user",content:m.content}],language,mode:"translate"}});if(e||data?.error)throw new Error(data?.error||e?.message);setTranslations(t=>({...t,[m.id]:data.content}));}catch(e:unknown){setError(friendlyErr(e));}finally{setTranslatingId(null);};};
  const signOut=async()=>{await supabase.auth.signOut();navigate({to:"/"});};

  // - SEND -
  const send = async()=>{
    if((!input.trim()&&pendingFiles.length===0)||!userId||sending) return;
    setError(null);
    const raw=input.trim();

    // Slash commands
    if(raw==="/health"||raw==="/audit"){setInput("");await runAudit();return;}
    if(raw==="/pdf"){setInput("");exportPdf();return;}
    if(raw==="/export"){setInput("");dlConv(activeConvId);return;}
    if(raw==="/summary"){setInput("Summarise this entire conversation in clear bullet points.");return;}
    if(raw==="/explain"){setInput("Explain the last code block in plain English, section by section.");return;}
    if(raw==="/fix"){setInput("Find and fix every bug in the last code I shared. Show the exact changes for each file.");return;}
    if(raw==="/aem"){setInput("");setAemMode(v=>!v);setError(aemMode?"AEM Mode deactivated":"* AEM Mode activated -- your personal AI context is now active");return;}
    if(raw==="/compare"){setInput("");if(lastAI){await runCompare(messages[messages.length-2]?.content||"Compare two approaches");}return;}
    if(raw==="/think"){setInput("Use deep reasoning. Think through this step by step before answering: "+input.replace("/think","").trim());return;}

    let convId=activeConvId;
    if(!convId){const{data}=await supabase.from("conversations").insert({user_id:userId,title:raw.slice(0,40)||"New Chat"}).select().single();if(!data)return;convId=data.id;setActiveConvId(convId);setConversations(c=>[data as Conv,...c]);}

    const text=input.trim();
    const atts=pendingFiles;
    const storedAtts=stripData(atts);
    setInput("");setPendingFiles([]);setSending(true);

    const userMsg:Msg={id:crypto.randomUUID(),role:"user",content:text,created_at:new Date().toISOString(),attachments:atts};
    setMessages(m=>[...m,userMsg]);
    await supabase.from("messages").insert({id:userMsg.id,conversation_id:convId,user_id:userId,role:"user",content:text,attachments:storedAtts as unknown as Json});

    if(messages.length===0){const title=(text||atts[0]?.name||"New Chat").slice(0,40);await supabase.from("conversations").update({title}).eq("id",convId!);setConversations(cs=>cs.map(c=>c.id===convId?{...c,title}:c));}

    try{
      // File context
      const textBlocks=atts.filter(a=>a.text).map(a=>"\n\n=== File: "+a.name+" ===\n"+a.text).join("");
      const hasImgs=atts.some(isImg);
      const attCtx=atts.length?"\n\n[Attached "+atts.length+" file(s): "+atts.map(a=>a.name+"("+a.type+")").join(", ")+". "+(hasImgs?"Images attached -- describe what you see.":"File contents below.")+"]"+textBlocks:"";

      const hist=[...messages,{...userMsg,content:text+attCtx}].map(m=>({role:m.role,content:m.content}));

      // Intent detection -- zero regex
      const low=text.toLowerCase().trim();
      const imgVerbs=["generate","create","draw","make","design","render","produce","paint","sketch","show me"];
      const imgNouns=["image","picture","photo","pic","logo","illustration","wallpaper","art","drawing","painting","portrait","poster","flyer","banner","avatar"];
      const drawVerbs=["draw","paint","sketch","illustrate","render"];
      const wantsImg=low.startsWith("/image ")||(imgVerbs.some(v=>low.includes(v))&&imgNouns.some(n=>low.includes(n)))||drawVerbs.some(v=>low.trimStart().startsWith(v));
      const vidTerms=["animate","animation","make it move","image to video","turn into video","moving image","video from image"];
      const wantsVid=vidTerms.some(v=>low.includes(v));

      const histWithMemory=memory?[{role:"system",content:"User memory:\n"+memory+(aemMode?"\n\nAEM MODE ACTIVE: This is the AEM platform creator. Provide elevated technical detail, remember all previous projects, and operate as the user's personal AI architect.":"")},...hist]:hist;

      let data:any=null;let invokeError:any=null;

      // PRIMARY: Puter.js (real Claude, free) for text-only
      if(!wantsImg&&!wantsVid&&atts.length===0){
        setStatusMsg("Connecting to AI...");
        try{const pr=await callPuterAI(histWithMemory);if(pr)data={content:pr,provider:"hi-there"};}
        catch{}
      }

      // CLIENT-SIDE image generation via Pollinations -- no server needed
      if(wantsImg&&!data){
        setStatusMsg("Creating image...");
        try{
          const imgPrompt=low.startsWith("/image ")?text.slice(7):text;
          const b64=await generateImagePollinations(imgPrompt);
          data={image_b64:b64,provider:"hi-there"};
        }catch{/* fall through to server */}
      }

      // SECONDARY: Supabase Edge Function
      if(!data){
        setStatusMsg(wantsImg?"Generating image...":wantsVid?"Creating animation...":atts.some(a=>a.name.endsWith(".zip"))?"Reading ZIP...":atts.some(a=>isPdf(a))?"Reading PDF...":atts.length>0?"Processing files...":"Routing to best AI engine...");
        const ir=await supabase.functions.invoke("chat",{body:{messages:histWithMemory,language,mode:wantsImg?"image":undefined,prefs:{personality,length:respLen,kidsMode,webSearch:webSearchEnabled,aemMode},attachments:atts.map(a=>({url:a.url,type:a.type,name:a.name,data:a.data}))}});
        data=ir.data;invokeError=ir.error;
      }

      if(invokeError) throw invokeError;
      if(data?.error) throw new Error(data.error);

      let aAtts:Attachment[]=[]; let sAtts:Attachment[]=[];

      if(data.image_b64){
        const fn="artwork-"+Date.now()+".png";const ip=userId+"/generated/"+fn;
        try{const blob=b64ToBlob(data.image_b64,"image/png");const{error:ie}=await supabase.storage.from("chat-uploads").upload(ip,blob,{contentType:"image/png",upsert:true});if(ie)throw ie;const{data:sig}=await supabase.storage.from("chat-uploads").createSignedUrl(ip,60*60*24*7);aAtts=[{name:fn,type:"image/png",size:blob.size,path:ip,url:sig?.signedUrl||"",data:data.image_b64}];sAtts=stripData(aAtts);}
        catch{aAtts=[{name:"generated-"+Date.now()+".png",type:"image/png",size:data.image_b64.length,url:"data:image/png;base64,"+data.image_b64,data:data.image_b64}];sAtts=aAtts;}
      }

      if(data.video_b64){
        const fn="animation-"+Date.now()+".mp4";const vp=userId+"/generated/"+fn;
        try{const blob=b64ToBlob(data.video_b64,"video/mp4");const{error:ve}=await supabase.storage.from("chat-uploads").upload(vp,blob,{contentType:"video/mp4",upsert:true});if(ve)throw ve;const{data:sig}=await supabase.storage.from("chat-uploads").createSignedUrl(vp,60*60*24*7);aAtts=[{name:fn,type:"video/mp4",size:blob.size,path:vp,url:sig?.signedUrl||"",data:data.video_b64}];sAtts=stripData(aAtts);}
        catch{aAtts=[{name:"animated-"+Date.now()+".mp4",type:"video/mp4",size:data.video_b64.length,url:"data:video/mp4;base64,"+data.video_b64,data:data.video_b64}];sAtts=aAtts;}
      }

      const reply:string=data.image_b64?"Here's what I created:":data.video_b64?"Here's your animation:":data.content||"(no response)";
      const thinking:string|undefined=data.thinking;
      const citations:{title:string;url:string}[]|undefined=data.citations;
      const aid=crypto.randomUUID();

      // Typewriter -- word by word like ChatGPT
      if(!data.image_b64&&!data.video_b64){
        const words=reply.split(" ");let built="";setTyping("");
        await new Promise<void>(res=>{
          let wi=0;
          const iv=setInterval(()=>{
            wi++;built=words.slice(0,wi).join(" ");setTyping(built);
            if(wi>=words.length){clearInterval(iv);res();}
          },Math.max(15,Math.min(50,8000/words.length)));
        });
        setTyping("");
      }

      const aMsg:Msg={id:aid,role:"assistant",content:reply,created_at:new Date().toISOString(),provider:data.provider,attachments:aAtts,thinking,citations};
      setMessages(m=>[...m,aMsg]);
      await supabase.from("messages").insert({id:aid,conversation_id:convId,user_id:userId,role:"assistant",content:reply,provider:data.provider,attachments:sAtts as unknown as Json});
      await supabase.from("conversations").update({updated_at:new Date().toISOString()}).eq("id",convId!);

      // Auto-save memory every 10 messages
      const all=[...messages,userMsg,aMsg];
      if(all.length>0&&all.length%10===0){
        const recent=all.slice(-10).map(m=>m.role+": "+m.content).join("\n").slice(0,3000);
        supabase.functions.invoke("chat",{body:{messages:[{role:"system",content:"Extract personal facts about the user from this conversation as bullet points starting with '-'. Max 10. Only real verified facts."},{role:"user",content:recent}],prefs:{personality:"direct",length:"short"}}}).then(({data:md})=>{if(md?.content&&userId){supabase.from("profiles").update({memory_summary:md.content} as any).eq("user_id",userId);setMemory(md.content);}});
      }
    }catch(e:unknown){const msg=friendlyErr(e);setError(msg);setMessages(m=>[...m,{id:crypto.randomUUID(),role:"assistant",content:"Warning: "+msg,created_at:new Date().toISOString()}]);}
    finally{setSending(false);setStatusMsg("Thinking...");}
  };

  if(!userId) return null;

  const pinnedMsgs=messages.filter(m=>pinned[m.id]);

  // - Sidebar -
  const SB=(
    <aside className="flex h-full w-64 shrink-0 flex-col bg-gradient-to-b from-zinc-950 to-zinc-900 border-r border-white/5 p-3">
      <button onClick={newChat} className="mb-3 flex items-center justify-center gap-2 rounded-2xl bg-gradient-to-r from-primary to-violet-600 px-3 py-2.5 text-sm font-semibold text-white shadow-lg shadow-primary/20 hover:opacity-90 transition-opacity">
        <Plus className="h-4 w-4"/> New Chat
      </button>
      <div className="relative mb-2">
        <Search className="absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-zinc-500"/>
        <input value={search} onChange={e=>setSearch(e.target.value)} placeholder="Search conversations..." className="w-full rounded-xl border border-white/5 bg-white/5 py-1.5 pl-8 pr-2 text-xs text-zinc-300 outline-none placeholder:text-zinc-600 focus:border-primary/50 focus:bg-white/10"/>
      </div>
      <div className="flex-1 space-y-0.5 overflow-y-auto">
        {filteredConvs.map(c=>(
          <div key={c.id} className={"group flex items-center gap-1 rounded-xl transition-all "+(c.id===activeConvId?"bg-white/10 text-white":"text-zinc-400 hover:bg-white/5 hover:text-zinc-200")}>
            <button onClick={()=>{setActiveConvId(c.id);setDrawerOpen(false);}} className="min-w-0 flex-1 truncate px-3 py-2 text-left text-sm">{c.title}</button>
            <button onClick={()=>delConv(c.id)} className="mr-1 grid h-7 w-7 shrink-0 place-items-center rounded-lg opacity-0 hover:bg-red-500/20 hover:text-red-400 group-hover:opacity-100"><Trash2 className="h-3 w-3"/></button>
          </div>
        ))}
        {filteredConvs.length===0&&<p className="px-3 py-6 text-center text-xs text-zinc-600">{search?"No matches.":"No conversations yet."}</p>}
      </div>
      <div className="mt-2 border-t border-white/5 pt-2 space-y-0.5">
        <Link to="/settings" className="flex items-center gap-2 rounded-xl px-3 py-2 text-xs text-zinc-500 hover:bg-white/5 hover:text-zinc-300"><SettingsIcon className="h-3.5 w-3.5"/> Settings</Link>
        <button onClick={signOut} className="flex w-full items-center gap-2 rounded-xl px-3 py-2 text-left text-xs text-zinc-500 hover:bg-white/5 hover:text-zinc-300"><LogOut className="h-3.5 w-3.5"/> Sign out</button>
      </div>
    </aside>
  );

  return (
    <div className={"flex h-screen flex-col bg-zinc-950 text-white "+(fontSize==="sm"?"text-[13px]":fontSize==="lg"?"text-[17px]":"text-[15px]")}>
      {isOffline&&<div className="flex items-center justify-center gap-2 border-b border-red-500/20 bg-gradient-to-r from-red-950/50 to-red-900/30 px-4 py-2 text-center text-xs text-red-400"><span className="h-1.5 w-1.5 rounded-full bg-red-400"/> No internet connection -- reconnecting automatically...</div>}
      {aemMode&&<div className="bg-gradient-to-r from-primary/20 to-violet-600/20 border-b border-primary/30 px-4 py-1.5 text-center text-xs text-primary">* AEM Mode Active -- Personal AI Context Enabled</div>}

      {/* Health panel */}
      {auditOpen&&(
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-sm p-4" onClick={()=>setAuditOpen(false)}>
          <div className="max-h-[90vh] w-full max-w-xl overflow-auto rounded-3xl border border-white/10 bg-zinc-900 p-6 shadow-2xl" onClick={e=>e.stopPropagation()}>
            <div className="mb-4 flex items-center justify-between">
              <h3 className="flex items-center gap-2 text-lg font-bold"><Brain className="h-5 w-5 text-primary"/> AEM Neural Hub</h3>
              <button onClick={()=>setAuditOpen(false)} className="rounded-xl p-1.5 hover:bg-white/10"><X className="h-4 w-4"/></button>
            </div>
            <div className="mb-4 rounded-2xl border border-white/10 bg-white/5 p-4">
              <p className="mb-2 text-xs font-semibold text-zinc-500 uppercase tracking-wider">System Status</p>
              <div className="flex items-center gap-2">
                <span className={"h-2 w-2 rounded-full "+(sending?"bg-yellow-400 animate-pulse":"bg-green-500")}/>
                <span className="text-sm">{sending?statusMsg:"All systems operational"}</span>
              </div>
              <div className="mt-2 flex flex-wrap gap-2 text-[11px] text-zinc-500">
                <span className={"rounded-lg px-2 py-0.5 "+(memory?"bg-green-500/10 text-green-400":"bg-white/5")}>Memory: {memory?"on":"off"}</span>
                <span className={"rounded-lg px-2 py-0.5 "+(voiceListening?"bg-blue-500/10 text-blue-400":"bg-white/5")}>Voice: {voiceListening?"on":"off"}</span>
                <span className={"rounded-lg px-2 py-0.5 "+(aemMode?"bg-primary/10 text-primary":"bg-white/5")}>AEM: {aemMode?"on":"off"}</span>
                <span className={"rounded-lg px-2 py-0.5 "+(kidsMode?"bg-yellow-500/10 text-yellow-400":"bg-white/5")}>Kids: {kidsMode?"on":"off"}</span>
              </div>
            </div>
            {auditing&&<div className="mb-4 rounded-2xl border border-white/10 bg-white/5 p-4 text-sm text-zinc-400 animate-pulse">Pinging all AI engines...</div>}
            {auditReport&&<div className="mb-4 rounded-2xl border border-white/10 bg-white/5 p-4"><MD text={auditReport}/></div>}
            <div className="grid grid-cols-2 gap-2 mb-4">
              {[{icon:Activity,label:"Run health check",action:runAudit,disabled:auditing},{icon:FileDown,label:"Export PDF",action:()=>{setAuditOpen(false);exportPdf();},disabled:exportingPdf},{icon:Mic,label:voiceListening?"Stop voice":"Voice mode",action:()=>{voiceListening?stopVoice():startVoice();setAuditOpen(false);}},{icon:Sparkles,label:"Compare AI",action:()=>{setAuditOpen(false);if(lastAI)runCompare(messages[messages.length-2]?.content||"");}}].map((b,i)=>(
                <button key={i} onClick={b.action} disabled={(b as any).disabled} className="flex items-center gap-2 rounded-2xl border border-white/10 bg-white/5 px-3 py-2.5 text-xs hover:bg-white/10 disabled:opacity-40 transition-colors">
                  <b.icon className="h-3.5 w-3.5 text-primary"/>{b.label}
                </button>
              ))}
            </div>
            <div className="rounded-2xl border border-amber-500/20 bg-amber-500/5 p-3 text-xs text-zinc-400">
              <p className="mb-1 font-semibold text-amber-400">Warning: Code Safety</p>
              Before pushing any generated code to GitHub: verify the complete file was returned, all imports are intact, and no closing bracket is missing.
            </div>
            {isAdmin&&<Link to="/admin" onClick={()=>setAuditOpen(false)} className="mt-3 flex items-center gap-2 rounded-2xl border border-primary/30 bg-primary/5 px-3 py-2 text-xs text-primary hover:bg-primary/10"><Shield className="h-3.5 w-3.5"/> Open Admin Panel</Link>}
          </div>
        </div>
      )}

      {/* Header */}
      <header className="flex items-center justify-between border-b border-white/5 bg-zinc-950/80 px-4 py-3 backdrop-blur-xl">
        <div className="flex items-center gap-3">
          <button onClick={()=>setDrawerOpen(true)} className="md:hidden rounded-xl p-1.5 hover:bg-white/10"><Menu className="h-5 w-5"/></button>
          <Link to="/" className="flex items-center gap-2.5">
            <div className="grid h-9 w-9 place-items-center rounded-2xl bg-gradient-to-br from-primary via-violet-500 to-indigo-600 shadow-lg shadow-primary/30">
              <Sparkles className="h-4.5 w-4.5 text-white"/>
            </div>
            <div>
              <span className="text-base font-bold tracking-tight bg-gradient-to-r from-white to-zinc-400 bg-clip-text text-transparent">HI THERE</span>
              <span className="ml-2 rounded-full bg-primary/20 px-2 py-0.5 text-[9px] font-bold uppercase tracking-wider text-primary">AEM</span>
            </div>
          </Link>
        </div>
        <div className="flex items-center gap-2">
          <button onClick={()=>setWebSearchEnabled(v=>!v)} title={webSearchEnabled?"Web search on":"Web search off"} className={"rounded-xl p-1.5 transition-colors "+(webSearchEnabled?"text-primary hover:bg-primary/10":"text-zinc-600 hover:bg-white/5")}><Globe className="h-4 w-4"/></button>
          <button onClick={()=>setAemMode(v=>!v)} title="AEM Mode" className={"rounded-xl p-1.5 transition-colors "+(aemMode?"text-primary hover:bg-primary/10":"text-zinc-600 hover:bg-white/5")}><Star className="h-4 w-4"/></button>
          <NotificationBell userId={userId}/>
          {isAdmin&&<Link to="/admin" className="flex items-center gap-1 rounded-xl border border-white/10 bg-white/5 px-2 py-1 text-xs hover:bg-white/10"><Shield className="h-3 w-3"/> Admin</Link>}
          <div className="relative">
            <button onClick={()=>setProfileOpen(v=>!v)} className="flex h-9 w-9 items-center justify-center rounded-2xl border border-white/10 bg-gradient-to-br from-primary/20 to-violet-600/20 text-xs font-bold hover:border-primary/30 transition-colors">
              {displayName?.[0]?.toUpperCase()||<User className="h-4 w-4"/>}
            </button>
            {profileOpen&&(
              <div className="absolute right-0 top-11 z-50 w-64 rounded-3xl border border-white/10 bg-zinc-900/95 p-2 shadow-2xl backdrop-blur-xl">
                {[{icon:SettingsIcon,label:"Settings",to:"/settings"},{icon:Activity,label:"AEM Neural Hub",action:()=>{setProfileOpen(false);runAudit();}},{icon:FileDown,label:"Export PDF",action:()=>{setProfileOpen(false);exportPdf();}},{icon:Download,label:"Download chat",action:()=>{setProfileOpen(false);dlConv(activeConvId);}},{icon:LogOut,label:"Sign out",action:signOut}].map((item,i)=>(
                  item.to?<Link key={i} to={item.to} onClick={()=>setProfileOpen(false)} className="flex items-center gap-2.5 rounded-2xl px-3 py-2 text-sm text-zinc-300 hover:bg-white/5 hover:text-white">
                    <item.icon className="h-4 w-4 text-zinc-500"/>{item.label}
                  </Link>:<button key={i} onClick={(item as any).action} className="flex w-full items-center gap-2.5 rounded-2xl px-3 py-2 text-left text-sm text-zinc-300 hover:bg-white/5 hover:text-white">
                    <item.icon className="h-4 w-4 text-zinc-500"/>{item.label}
                  </button>
                ))}
                <div className="mt-1 border-t border-white/5 pt-2 px-1 space-y-2">
                  {[{label:"Personality",val:personality,set:setPersonality,opts:[{v:"direct",l:"Direct"},{v:"friendly",l:"Friendly"},{v:"detailed",l:"Detailed"}]},{label:"Length",val:respLen,set:setRespLen,opts:[{v:"short",l:"Short"},{v:"normal",l:"Normal"},{v:"detailed",l:"Long"}]}].map(s=>(
                    <div key={s.label}>
                      <p className="mb-1 px-2 text-[10px] font-semibold uppercase tracking-wider text-zinc-600">{s.label}</p>
                      <div className="flex gap-1">
                        {s.opts.map(o=><button key={o.v} onClick={()=>(s.set as any)(o.v)} className={"flex-1 rounded-xl px-2 py-1 text-[11px] transition-colors "+(s.val===o.v?"bg-primary/20 text-primary":"text-zinc-500 hover:bg-white/5")}>{o.l}</button>)}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>
      </header>

      <div className="flex flex-1 overflow-hidden">
        <div className="hidden md:flex">{SB}</div>
        {drawerOpen&&<div className="fixed inset-0 z-40 md:hidden" onClick={()=>setDrawerOpen(false)}><div className="absolute inset-0 bg-black/70 backdrop-blur-sm"/><div className="absolute left-0 top-0 h-full" onClick={e=>e.stopPropagation()}><div className="relative h-full">{SB}<button onClick={()=>setDrawerOpen(false)} className="absolute right-2 top-2 rounded-xl p-1.5 hover:bg-white/10"><X className="h-4 w-4"/></button></div></div></div>}

        <main className="flex flex-1 flex-col overflow-hidden bg-zinc-950">
          <div ref={scrollRef} className="flex-1 overflow-y-auto px-4 py-6 sm:px-6">
            <div className="mx-auto max-w-3xl space-y-5">

              {/* Pinned */}
              {pinnedMsgs.length>0&&<div className="rounded-3xl border border-primary/20 bg-primary/5 p-4"><p className="mb-2 flex items-center gap-2 text-[11px] font-bold uppercase tracking-wider text-primary"><BookmarkCheck className="h-3.5 w-3.5"/> Pinned ({pinnedMsgs.length})</p><div className="space-y-1">{pinnedMsgs.map(p=><div key={"pin-"+p.id} className="flex items-start gap-2 text-xs text-zinc-400"><span className="flex-1 line-clamp-2">{p.content.slice(0,200)}</span><button onClick={()=>togglePin(p.id)} className="shrink-0 rounded-lg p-0.5 hover:bg-white/10"><X className="h-3 w-3"/></button></div>)}</div></div>}

              {/* Welcome */}
              {messages.length===0&&!typing&&(
                <div className="space-y-8 py-8">
                  <div className="text-center">
                    <div className="mx-auto mb-6 relative">
                      <div className="grid h-20 w-20 mx-auto place-items-center rounded-3xl bg-gradient-to-br from-primary via-violet-500 to-indigo-600 shadow-2xl shadow-primary/30 ring-4 ring-primary/20">
                        <Sparkles className="h-10 w-10 text-white"/>
                      </div>
                      {aemMode&&<div className="absolute -top-1 -right-1 rounded-full bg-primary px-2 py-0.5 text-[9px] font-bold text-white shadow-lg">AEM</div>}
                    </div>
                    <h1 className="text-3xl font-bold tracking-tight sm:text-4xl bg-gradient-to-r from-white via-zinc-200 to-zinc-500 bg-clip-text text-transparent">{greeting}{displayName?", "+displayName:""}</h1>
                    <p className="mt-3 text-sm text-zinc-500 max-w-md mx-auto">Your intelligent AI platform -- reads files, builds code, generates images, searches the web, and more.</p>
                    <div className="mt-4 flex flex-wrap justify-center gap-2 text-[11px] text-zinc-500">
                      {[" ZIP & PDF"," Images"," Voice"," Full code"," 6 languages","! Live preview"," Web search"].map(c=><span key={c} className="rounded-full border border-white/10 bg-white/5 px-3 py-1">{c}</span>)}
                    </div>
                  </div>
                  <div className="grid gap-2 sm:grid-cols-2">
                    {[{icon:ImageIcon,label:"Generate an image",prompt:"Generate an image of "},{icon:Bug,label:"Audit my project",prompt:"Here is my project ZIP. Find all bugs sorted by severity:\n"},{icon:Globe,label:"Search the web",prompt:"Search and summarize: "},{icon:Brain,label:"Deep reasoning",prompt:"/think "}].map(q=>(
                      <button key={q.label} onClick={()=>setInput(q.prompt)} className="group flex items-center gap-3 rounded-2xl border border-white/5 bg-white/3 p-4 text-left text-sm backdrop-blur transition-all hover:border-primary/30 hover:bg-white/5 hover:shadow-lg hover:shadow-primary/5">
                        <span className="grid h-10 w-10 place-items-center rounded-2xl bg-primary/10 text-primary transition-colors group-hover:bg-primary/20"><q.icon className="h-4.5 w-4.5"/></span>
                        <span className="font-medium text-zinc-300">{q.label}</span>
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {/* Compare results */}
              {compareRes&&<div className="rounded-3xl border border-white/10 bg-white/3 p-5"><div className="mb-3 flex items-center justify-between"><p className="text-sm font-semibold text-zinc-300">Multi-Engine Comparison</p><button onClick={()=>setCompareRes(null)} className="rounded-xl p-1 hover:bg-white/10"><X className="h-3.5 w-3.5"/></button></div><div className="grid grid-cols-2 gap-4"><div><p className="mb-2 text-[11px] font-bold uppercase tracking-wider text-primary">Response A</p><p className="text-sm text-zinc-300 whitespace-pre-wrap">{compareRes.a}</p></div><div><p className="mb-2 text-[11px] font-bold uppercase tracking-wider text-violet-400">Response B</p><p className="text-sm text-zinc-300 whitespace-pre-wrap">{compareRes.b}</p></div></div></div>}
              {comparing&&<div className="rounded-3xl border border-white/10 bg-white/3 p-5 text-center text-sm text-zinc-500 animate-pulse">Comparing two AI engines simultaneously...</div>}

              {/* Messages */}
              {messages.map(m=>{
                const imgAtts=(m.attachments||[]).filter(a=>isImg(a));
                const otherAtts=(m.attachments||[]).filter(a=>!isImg(a));
                const isU=m.role==="user";
                return(
                  <div key={m.id} className={"flex gap-3 "+(isU?"justify-end":"justify-start")}>
                    {!isU&&<div className="mt-1 shrink-0"><div className="grid h-9 w-9 place-items-center rounded-2xl bg-gradient-to-br from-primary to-violet-600 shadow-lg shadow-primary/20"><Sparkles className="h-4 w-4 text-white"/></div></div>}
                    <div className={"group flex flex-col max-w-[82%] sm:max-w-[78%] "+(isU?"items-end":"items-start")}>
                      {imgAtts.length>0&&<div className="mb-2 w-full max-w-md"><Carousel images={imgAtts.map(a=>({src:a.data?"data:"+a.type+";base64,"+a.data:a.url,name:a.name}))}/></div>}
                      {/* Thinking trace */}
                      {m.thinking&&<div className="mb-2 w-full"><button onClick={()=>setThinkingOpen(thinkingOpen===m.id?null:m.id)} className="flex items-center gap-2 text-[11px] text-zinc-500 hover:text-zinc-300"><Brain className="h-3 w-3 text-primary"/>{thinkingOpen===m.id?"Hide":"Show"} thinking process<ChevronDown className={"h-3 w-3 transition-transform "+(thinkingOpen===m.id?"rotate-180":"")}/></button>{thinkingOpen===m.id&&<div className="mt-1 rounded-2xl border border-white/5 bg-white/3 p-3 text-xs text-zinc-500 whitespace-pre-wrap leading-relaxed">{m.thinking}</div>}</div>}
                      <div className={"rounded-3xl text-sm "+(isU?"bg-gradient-to-br from-primary/80 to-violet-600/80 text-white px-5 py-3.5 shadow-lg shadow-primary/10":"bg-zinc-900 border border-white/5 px-5 py-3.5 shadow-sm")}>
                        <MD text={m.content} dark={isU}/>
                        {isU&&editingId===m.id&&<div className="mt-2 space-y-1"><textarea value={editingText} onChange={e=>setEditingText(e.target.value)} rows={3} className="w-full rounded-2xl border border-white/10 bg-black/40 px-3 py-2 text-sm"/><div className="flex justify-end gap-1"><button onClick={()=>setEditingId(null)} className="rounded-xl px-3 py-1 text-xs hover:bg-white/10">Cancel</button><button onClick={()=>saveEdit(m)} className="rounded-xl bg-primary px-3 py-1 text-xs text-white">Save</button></div></div>}
                        {otherAtts.length>0&&<div className="mt-3 space-y-2">{otherAtts.map((a,i)=><AttachView key={a.name+i} a={a}/>)}</div>}
                        {translations[m.id]&&<div className="mt-3 rounded-2xl border border-white/5 bg-white/5 p-3 text-xs"><p className="mb-1 font-semibold text-zinc-500">Translation ({langLabel(language)}):</p><p className="text-zinc-300 whitespace-pre-wrap">{translations[m.id]}</p></div>}
                        {/* Citations */}
                        {m.citations&&m.citations.length>0&&<div className="mt-3 space-y-1"><p className="text-[10px] font-bold uppercase tracking-wider text-zinc-600">Sources</p>{m.citations.map((c,i)=><CitationCard key={i} c={c}/>)}</div>}
                        <div className={"mt-2 flex items-center justify-between gap-2 text-[10px] opacity-0 group-hover:opacity-100 transition-opacity "+(isU?"":"")}>
                          <span className="text-zinc-600">{fmtTime(m.created_at)}</span>
                          {!isU?(
                            <div className="flex items-center gap-0.5 flex-wrap">
                              {[{icon:Languages,action:()=>translate(m),label:translatingId===m.id?"...":"Translate"},{icon:pinned[m.id]?BookmarkCheck:Bookmark,action:()=>togglePin(m.id),label:pinned[m.id]?"Unpin":"Pin",color:pinned[m.id]?"text-primary":""},{icon:speakingId===m.id?VolumeX:Volume2,action:()=>speak(m),label:speakingId===m.id?"Stop":"Read"},{icon:Share2,action:()=>shareMsg(m),label:"Share"},{icon:Download,action:()=>downloadAsFile(m),label:"Save"},{icon:Flag,action:()=>report(m),label:"Report"},{icon:Trash2,action:()=>delMsg(m),label:"Delete",color:"hover:text-red-400"},{icon:copiedId===m.id?Check:Copy,action:()=>copy(m.id,m.content),label:copiedId===m.id?"Copied":"Copy"}].map((b,i)=>(
                                <button key={i} onClick={b.action} className={"flex items-center gap-0.5 rounded-xl px-1.5 py-1 hover:bg-white/10 "+(b.color||"text-zinc-500")}><b.icon className="h-3 w-3"/><span className="hidden sm:inline">{b.label}</span></button>
                              ))}
                            </div>
                          ):(
                            <div className="flex items-center gap-0.5">
                              {[{icon:Pencil,action:()=>startEdit(m)},{icon:copiedId===m.id?Check:Copy,action:()=>copy(m.id,m.content)},{icon:Trash2,action:()=>delMsg(m),color:"hover:text-red-400"}].map((b,i)=><button key={i} onClick={b.action} className={"rounded-xl p-1 hover:bg-white/20 text-white/50 "+(b.color||"")}><b.icon className="h-3 w-3"/></button>)}
                            </div>
                          )}
                        </div>
                      </div>
                    </div>
                    {isU&&<div className="mt-1 shrink-0"><div className="grid h-9 w-9 place-items-center rounded-2xl bg-zinc-800 border border-white/5 text-xs font-bold text-zinc-400">{displayName?.[0]?.toUpperCase()||"U"}</div></div>}
                  </div>
                );
              })}

              {/* Typing indicator */}
              {typing&&<div className="flex gap-3 justify-start"><div className="mt-1 shrink-0"><div className="grid h-9 w-9 place-items-center rounded-2xl bg-gradient-to-br from-primary to-violet-600 shadow-lg animate-pulse"><Sparkles className="h-4 w-4 text-white"/></div></div><div className="max-w-[78%] rounded-3xl border border-white/5 bg-zinc-900 px-5 py-3.5 shadow-sm text-sm"><MD text={typing}/><span className="ml-1 inline-block h-4 w-0.5 animate-pulse bg-primary align-middle rounded-full"/></div></div>}

              {/* Sending indicator */}
              {sending&&!typing&&<div className="flex gap-3 justify-start"><div className="mt-1 shrink-0"><div className="grid h-9 w-9 place-items-center rounded-2xl bg-gradient-to-br from-primary to-violet-600 shadow-lg animate-pulse"><Sparkles className="h-4 w-4 text-white"/></div></div><div className="rounded-3xl border border-white/5 bg-zinc-900 px-5 py-3.5 shadow-sm text-sm text-zinc-500"><span className="flex items-center gap-3"><span className="flex gap-1"><span className="h-1.5 w-1.5 animate-bounce rounded-full bg-primary [animation-delay:-0.3s]"/><span className="h-1.5 w-1.5 animate-bounce rounded-full bg-primary [animation-delay:-0.15s]"/><span className="h-1.5 w-1.5 animate-bounce rounded-full bg-primary"/></span><span className="text-xs text-primary/60 animate-pulse">{statusMsg}</span></span></div></div>}

              {/* Suggestions */}
              {!sending&&!typing&&lastAI&&suggestions.length>0&&<div className="flex flex-wrap gap-2 pt-1">{suggestions.map(s=><button key={s} onClick={()=>setInput(s)} className="rounded-2xl border border-white/5 bg-white/3 px-4 py-1.5 text-xs text-zinc-500 hover:border-primary/30 hover:bg-white/5 hover:text-zinc-300 transition-all">{s}</button>)}</div>}
            </div>
          </div>

          {/* Error */}
          {error&&<div className="mx-4 mb-2 rounded-2xl border border-red-500/20 bg-red-500/10 px-4 py-2 text-xs text-red-400">{error}<button onClick={()=>setError(null)} className="ml-2 underline hover:text-red-300">dismiss</button></div>}
          {voiceError&&<div className="mx-4 mb-1 text-xs text-red-400">{voiceError}</div>}

          {/* Input area */}
          <div className="border-t border-white/5 bg-zinc-950/90 p-3 backdrop-blur-xl sm:p-4">
            <div className="mx-auto max-w-3xl">
              {/* Slash menu */}
              {slashMatches.length>0&&<div className="mb-2 rounded-2xl border border-white/10 bg-zinc-900/95 p-1.5 shadow-xl backdrop-blur">{slashMatches.map(c=><button key={c.cmd} onClick={()=>{setInput(c.cmd+" ");}} className="flex w-full items-center gap-3 rounded-xl px-3 py-2 text-left hover:bg-white/5"><span className="font-mono text-xs font-bold text-primary">{c.cmd}</span><span className="text-xs text-zinc-500">{c.desc}</span></button>)}</div>}

              {/* Pending files */}
              {pendingFiles.length>0&&<div className="mb-3 grid gap-2 sm:grid-cols-2">{pendingFiles.map((f,i)=><div key={i} className="relative"><AttachView a={f}/><button onClick={()=>setPendingFiles(p=>p.filter((_,j)=>j!==i))} className="absolute right-2 top-2 grid h-7 w-7 place-items-center rounded-xl border border-white/10 bg-zinc-900 hover:text-red-400"><X className="h-3.5 w-3.5"/></button></div>)}</div>}

              <div className="flex items-end gap-2">
                <input ref={fileRef} type="file" multiple hidden accept=".pdf,.doc,.docx,.zip,.txt,.md,.json,.csv,.js,.ts,.tsx,.jsx,.py,.html,.css,image/*,video/*,audio/*" onChange={e=>{handleFiles(e.target.files);e.target.value="";}}/>
                <input ref={cameraRef} type="file" hidden accept="image/*" capture="environment" onChange={e=>{handleFiles(e.target.files);e.target.value="";}}/>
                {[{icon:Paperclip,action:()=>fileRef.current?.click(),title:"Attach file"},{icon:Camera,action:()=>cameraRef.current?.click(),title:"Camera",mobile:true},{icon:voiceListening?MicOff:Mic,action:()=>voiceListening?stopVoice():startVoice(),title:voiceListening?"Stop":"Voice",active:voiceListening},{icon:Activity,action:()=>setAuditOpen(true),title:"AEM Hub"}].map((b,i)=>(
                  (!b.mobile||true)&&<button key={i} onClick={b.action} title={b.title} className={"grid h-11 w-11 shrink-0 place-items-center rounded-2xl border transition-colors "+(b.active?"border-primary bg-primary/10 text-primary animate-pulse":"border-white/10 bg-white/5 text-zinc-400 hover:bg-white/10 hover:text-white")+(b.mobile?" md:hidden":"")}><b.icon className="h-4.5 w-4.5"/></button>
                ))}
                <textarea value={input} onChange={e=>{setInput(e.target.value);}} onKeyDown={e=>{if(e.key==="Enter"&&!e.shiftKey){e.preventDefault();send();}}} rows={1} placeholder="Message HI THERE..." className="max-h-40 min-h-[44px] flex-1 resize-none rounded-2xl border border-white/10 bg-white/5 px-4 py-3 text-sm text-white outline-none placeholder:text-zinc-600 focus:border-primary/50 focus:bg-white/8 transition-colors"/>
                <button id="aem-send" onClick={send} disabled={sending||(!input.trim()&&pendingFiles.length===0)} className="grid h-11 w-11 shrink-0 place-items-center rounded-2xl bg-gradient-to-br from-primary to-violet-600 text-white shadow-lg shadow-primary/20 hover:opacity-90 disabled:opacity-30 transition-opacity"><Send className="h-4.5 w-4.5"/></button>
              </div>
              <p className="mt-2 text-center text-[10px] text-zinc-700">HI THERE . AEM Platform . AI may make mistakes -- verify important information</p>
            </div>
          </div>
          <Footer/>
        </main>
      </div>
    </div>
  );
}
