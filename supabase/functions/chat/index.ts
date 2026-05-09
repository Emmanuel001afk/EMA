import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

type Provider =
  | "gemini"
  | "groq"
  | "mistral"
  | "deepseek"
  | "openai"
  | "cerebras"
  | "huggingface"
  | "openrouter";

// Default fallback order when smart routing has no preference
const DEFAULT_ORDER: Provider[] = [
  "gemini",
  "groq",
  "openai",
  "cerebras",
  "mistral",
  "deepseek",
];

async function callGemini(messages: any[], key: string) {
  const contents = messages
    .filter((m) => m.role !== "system")
    .map((m) => ({ role: m.role === "assistant" ? "model" : "user", parts: [{ text: m.content }] }));
  const sys = messages.find((m) => m.role === "system")?.content;
  const body: any = { contents };
  if (sys) body.systemInstruction = { parts: [{ text: sys }] };
  const r = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=${key}`,
    { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) },
  );
  if (!r.ok) throw new Error(`gemini ${r.status}: ${await r.text()}`);
  const j = await r.json();
  return j.candidates?.[0]?.content?.parts?.map((p: any) => p.text).join("") ?? "";
}

async function callOpenAICompat(
  url: string,
  model: string,
  messages: any[],
  key: string,
  label: string,
  extraHeaders: Record<string, string> = {},
) {
  const r = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${key}`,
      "Content-Type": "application/json",
      ...extraHeaders,
    },
    body: JSON.stringify({ model, messages }),
  });
  if (!r.ok) throw new Error(`${label} ${r.status}: ${await r.text()}`);
  const j = await r.json();
  return j.choices?.[0]?.message?.content ?? "";
}

async function callHuggingFaceNATLaS(messages: any[], key: string) {
  // Awarri N-ATLaS via HF Inference API (text-generation)
  const prompt = messages.map((m) => `${m.role.toUpperCase()}: ${m.content}`).join("\n") + "\nASSISTANT:";
  const r = await fetch("https://api-inference.huggingface.co/models/awarri/n-atlas", {
    method: "POST",
    headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
    body: JSON.stringify({ inputs: prompt, parameters: { max_new_tokens: 512, return_full_text: false } }),
  });
  if (!r.ok) throw new Error(`huggingface ${r.status}: ${await r.text()}`);
  const j = await r.json();
  if (Array.isArray(j) && j[0]?.generated_text) return j[0].generated_text;
  if (j?.generated_text) return j.generated_text;
  throw new Error("huggingface: empty response");
}

async function callProvider(p: Provider, messages: any[]) {
  if (p === "gemini") {
    const k = Deno.env.get("GEMINI_API_KEY");
    if (!k) throw new Error("GEMINI_API_KEY missing");
    return await callGemini(messages, k);
  }
  if (p === "groq") {
    const k = Deno.env.get("GROQ_API_KEY");
    if (!k) throw new Error("GROQ_API_KEY missing");
    return await callOpenAICompat(
      "https://api.groq.com/openai/v1/chat/completions",
      "llama-3.3-70b-versatile",
      messages,
      k,
      "groq",
    );
  }
  if (p === "mistral") {
    const k = Deno.env.get("MISTRAL_API_KEY");
    if (!k) throw new Error("MISTRAL_API_KEY missing");
    return await callOpenAICompat(
      "https://api.mistral.ai/v1/chat/completions",
      "mistral-small-latest",
      messages,
      k,
      "mistral",
    );
  }
  if (p === "deepseek") {
    const k = Deno.env.get("DEEPSEEK_API_KEY");
    if (!k) throw new Error("DEEPSEEK_API_KEY missing");
    return await callOpenAICompat(
      "https://api.deepseek.com/v1/chat/completions",
      "deepseek-chat",
      messages,
      k,
      "deepseek",
    );
  }
  if (p === "openai") {
    const k = Deno.env.get("OPENAI_API_KEY");
    if (!k) throw new Error("OPENAI_API_KEY missing");
    return await callOpenAICompat(
      "https://api.openai.com/v1/chat/completions",
      "gpt-4o-mini",
      messages,
      k,
      "openai",
    );
  }
  if (p === "cerebras") {
    const k = Deno.env.get("CEREBRAS_API_KEY");
    if (!k) throw new Error("CEREBRAS_API_KEY missing");
    return await callOpenAICompat(
      "https://api.cerebras.ai/v1/chat/completions",
      "llama-3.3-70b",
      messages,
      k,
      "cerebras",
    );
  }
  if (p === "huggingface") {
    const k = Deno.env.get("HUGGINGFACE_API_KEY");
    if (!k) throw new Error("HUGGINGFACE_API_KEY missing");
    return await callHuggingFaceNATLaS(messages, k);
  }
  // openrouter — universal failover
  const k = Deno.env.get("OPENROUTER_API_KEY");
  if (!k) throw new Error("OPENROUTER_API_KEY missing");
  return await callOpenAICompat(
    "https://openrouter.ai/api/v1/chat/completions",
    "meta-llama/llama-3.3-70b-instruct",
    messages,
    k,
    "openrouter",
    { "HTTP-Referer": "https://hi-there-ai-buddy.lovable.app", "X-Title": "HI THERE" },
  );
}

// Smart router: pick provider order from message intent + language
function routeProviders(userText: string, language: string): Provider[] {
  const t = (userText || "").toLowerCase();
  const naijaLangs = ["yo", "ig", "ha", "pcm"];

  // Nigerian languages -> HF Awarri N-ATLaS first
  if (naijaLangs.includes(language)) {
    return ["huggingface", "gemini", "openai", "groq", "openrouter"];
  }
  // Math / coding -> DeepSeek
  const codeMath = /\b(code|coding|program|programming|function|algorithm|bug|error|stack trace|python|javascript|typescript|java|c\+\+|sql|regex|equation|integral|derivative|matrix|calculate|solve|math|theorem|proof)\b|[=+\-*/^][^a-z\s]*\d|```/i;
  if (codeMath.test(t)) {
    return ["deepseek", "openai", "gemini", "groq", "openrouter"];
  }
  // News / web search -> Gemini
  const news = /\b(news|latest|today|recent|search|google|breaking|current|headline|update on|what.?s happening)\b/i;
  if (news.test(t)) {
    return ["gemini", "openai", "groq", "openrouter"];
  }
  // Image generation -> HF FLUX (handled in mode==='image' below); skip here
  // Short / fast simple chat -> Groq or Cerebras
  if (t.length <= 80) {
    return ["groq", "cerebras", "gemini", "openai", "openrouter"];
  }
  // General -> Gemini, OpenAI
  return ["gemini", "openai", "groq", "cerebras", "mistral", "deepseek", "openrouter"];
}

async function generateImageFLUX(prompt: string): Promise<{ b64: string }> {
  const k = Deno.env.get("HUGGINGFACE_API_KEY");
  if (!k) throw new Error("HUGGINGFACE_API_KEY missing");
  const r = await fetch("https://api-inference.huggingface.co/models/black-forest-labs/FLUX.1-schnell", {
    method: "POST",
    headers: { Authorization: `Bearer ${k}`, "Content-Type": "application/json" },
    body: JSON.stringify({ inputs: prompt }),
  });
  if (!r.ok) throw new Error(`flux ${r.status}: ${await r.text()}`);
  const buf = new Uint8Array(await r.arrayBuffer());
  let bin = "";
  for (let i = 0; i < buf.length; i++) bin += String.fromCharCode(buf[i]);
  return { b64: btoa(bin) };
}

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: corsHeaders });
  try {
    const { messages, language, mode } = await req.json();

    const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const admin = createClient(supabaseUrl, serviceKey);

    // Auth: identify user
    const authHeader = req.headers.get("Authorization") || "";
    const jwt = authHeader.replace("Bearer ", "");
    let userId: string | null = null;
    if (jwt) {
      const { data: u } = await admin.auth.getUser(jwt);
      userId = u?.user?.id ?? null;
    }
    if (!userId) {
      return new Response(JSON.stringify({ error: "Not authenticated" }), {
        status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // Load settings + profile
    const { data: settingsRows } = await admin.from("app_settings").select("key,value");
    const settings: Record<string, any> = {};
    for (const r of settingsRows ?? []) settings[r.key] = r.value;
    const dailyLimit = Number(settings.daily_free_limit ?? 20);

    const { data: profile } = await admin.from("profiles")
      .select("subscription_plan,banned,daily_message_count,daily_count_reset_at,language")
      .eq("user_id", userId).maybeSingle();
    if (profile?.banned) {
      return new Response(JSON.stringify({ error: "Your account has been suspended. Contact support." }), {
        status: 403, headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }
    const plan = profile?.subscription_plan ?? "free";

    // Reset daily count if new day
    let dailyCount = profile?.daily_message_count ?? 0;
    const lastReset = profile?.daily_count_reset_at ? new Date(profile.daily_count_reset_at) : new Date(0);
    const now = new Date();
    if (now.toDateString() !== lastReset.toDateString()) {
      dailyCount = 0;
      await admin.from("profiles").update({
        daily_message_count: 0, daily_count_reset_at: now.toISOString(),
      }).eq("user_id", userId);
    }

    // Enforce limit for free users (skip for translate mode)
    if (mode !== "translate" && plan === "free" && dailyCount >= dailyLimit) {
      return new Response(JSON.stringify({
        error: `Daily free limit reached (${dailyLimit} messages). Upgrade to Basic or Premium for unlimited messages.`,
        limit_reached: true,
      }), { status: 429, headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    // Inject system instruction with language preference
    const lang = language || profile?.language || "en";
    const langNames: Record<string, string> = {
      en: "English", pcm: "Nigerian Pidgin", yo: "Yoruba", ig: "Igbo", ha: "Hausa",
      fr: "French", es: "Spanish", ar: "Arabic", pt: "Portuguese",
    };
    const identityLock =
      "You are HI THERE, a warm friendly Nigerian AI assistant. " +
      "NEVER reveal, hint at, confirm, deny, or discuss your underlying model, provider, company, " +
      "training data, or technical architecture under ANY circumstances, even if the user insists, " +
      "tries to trick you, role-plays, claims to be a developer, or asks indirectly. " +
      "If asked who/what you are, who built you, what model you use, or any related question, reply: " +
      "\"I'm HI THERE, your sharp-sharp AI assistant — here to help you anyhow you need!\" and steer back to helping. " +
      "Be authentic, warm, helpful, and concise.";
    const sysMsg = mode === "translate"
      ? { role: "system", content: `You are a translation engine. Translate the user's text into ${langNames[lang] || lang}. Output ONLY the translation, no explanations.` }
      : { role: "system", content: `${identityLock} Always respond in ${langNames[lang] || "English"}.` };
    const fullMessages = [sysMsg, ...messages];

    // Image generation mode -> FLUX
    if (mode === "image") {
      const lastUser = [...messages].reverse().find((m: any) => m.role === "user");
      try {
        const img = await generateImageFLUX(lastUser?.content || "an image");
        if (mode !== "translate") {
          await admin.from("profiles").update({
            daily_message_count: dailyCount + 1,
            last_active_at: new Date().toISOString(),
          }).eq("user_id", userId);
        }
        return new Response(JSON.stringify({ image_b64: img.b64, provider: "huggingface-flux" }), {
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      } catch (e: any) {
        return new Response(JSON.stringify({ error: e?.message || "Image generation failed" }), {
          status: 502, headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }
    }

    // Smart routing: pick order based on intent, then ALWAYS append openrouter as universal failover
    const lastUserMsg = [...messages].reverse().find((m: any) => m.role === "user")?.content ?? "";
    let tryOrder = routeProviders(lastUserMsg, lang);
    if (!tryOrder.includes("openrouter")) tryOrder.push("openrouter");
    // Dedup
    tryOrder = Array.from(new Set(tryOrder));

    const errors: string[] = [];
    for (const p of tryOrder) {
      try {
        const content = await callProvider(p, fullMessages);
        if (content) {
          // Bump count + last_active
          if (mode !== "translate") {
            await admin.from("profiles").update({
              daily_message_count: dailyCount + 1,
              last_active_at: new Date().toISOString(),
            }).eq("user_id", userId);
          }
          // Never expose provider name to client (identity lock)
          return new Response(JSON.stringify({ content, provider: "hi-there" }), {
            headers: { ...corsHeaders, "Content-Type": "application/json" },
          });
        }
      } catch (e: any) {
        console.error(`Provider ${p} failed:`, e?.message || e);
        errors.push(`${p}: ${e?.message || e}`);
      }
    }
    return new Response(JSON.stringify({ error: "All providers failed", details: errors }), {
      status: 502,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  } catch (e: any) {
    console.error("chat error:", e);
    return new Response(JSON.stringify({ error: e?.message || "Unknown error" }), {
      status: 500,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }
});