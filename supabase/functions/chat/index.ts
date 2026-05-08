import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

type Provider = "gemini" | "groq" | "mistral";
const ORDER: Provider[] = ["gemini", "groq", "mistral"];

const PERSONA = `You are HI THERE, a warm and friendly Nigerian AI assistant.

CRITICAL RULES — never violate:
1. NEVER reveal, name, hint at, or confirm any underlying model, provider, company, or technology powering you (do not say Gemini, Google, Groq, Llama, Meta, Mistral, OpenAI, GPT, Claude, Anthropic, or any model name).
2. If asked who you are, what you run on, what model, what AI, who made you, or anything similar — answer ONLY: "I am HI THERE — your correct AI assistant, here to help you sharp-sharp." Never elaborate on internals.
3. Maintain an authentic, helpful Nigerian tone. Be warm, respectful, lively. Light use of Naija expressions ("sharp-sharp", "no wahala", "abeg") is welcome but stay clear and professional.
4. Be concise, accurate and useful. Use markdown for code and structure when helpful.`;

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
  if (!r.ok) throw new Error(`p1 ${r.status}`);
  const j = await r.json();
  return j.candidates?.[0]?.content?.parts?.map((p: any) => p.text).join("") ?? "";
}

async function callOpenAICompat(url: string, model: string, messages: any[], key: string, label: string) {
  const r = await fetch(url, {
    method: "POST",
    headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
    body: JSON.stringify({ model, messages }),
  });
  if (!r.ok) throw new Error(`${label} ${r.status}`);
  const j = await r.json();
  return j.choices?.[0]?.message?.content ?? "";
}

async function callProvider(p: Provider, messages: any[]) {
  if (p === "gemini") {
    const k = Deno.env.get("GEMINI_API_KEY");
    if (!k) throw new Error("p1 missing");
    return await callGemini(messages, k);
  }
  if (p === "groq") {
    const k = Deno.env.get("GROQ_API_KEY");
    if (!k) throw new Error("p2 missing");
    return await callOpenAICompat(
      "https://api.groq.com/openai/v1/chat/completions",
      "llama-3.3-70b-versatile",
      messages, k, "p2",
    );
  }
  const k = Deno.env.get("MISTRAL_API_KEY");
  if (!k) throw new Error("p3 missing");
  return await callOpenAICompat(
    "https://api.mistral.ai/v1/chat/completions",
    "mistral-small-latest",
    messages, k, "p3",
  );
}

// Sanitize replies to scrub any model-name leaks
function scrub(s: string): string {
  if (!s) return s;
  return s
    .replace(/\b(?:google\s+)?gemini(?:[\s-]?(?:1\.5|2\.5|pro|flash))?\b/gi, "HI THERE")
    .replace(/\bGoogle DeepMind\b/gi, "HI THERE")
    .replace(/\bllama[\s-]?\d?(?:[.\d-]+)?\b/gi, "HI THERE")
    .replace(/\bmeta(?:\s+ai)?\b/gi, "HI THERE")
    .replace(/\bmistral(?:\s+ai)?\b/gi, "HI THERE")
    .replace(/\bgroq\b/gi, "HI THERE")
    .replace(/\bopenai\b/gi, "HI THERE")
    .replace(/\bgpt[-\s]?\d?(?:\.\d+)?\b/gi, "HI THERE")
    .replace(/\banthropic\b/gi, "HI THERE")
    .replace(/\bclaude(?:\s+\d+(?:\.\d+)?)?\b/gi, "HI THERE");
}

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: corsHeaders });
  try {
    const { messages, language, mode, tone, length: respLen } = await req.json();

    const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const admin = createClient(supabaseUrl, serviceKey);

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

    const { data: settingsRows } = await admin.from("app_settings").select("key,value");
    const settings: Record<string, any> = {};
    for (const r of settingsRows ?? []) settings[r.key] = r.value;
    const active = ((settings.active_provider as string) || "gemini") as Provider;
    const dailyLimit = Number(settings.daily_free_limit ?? 20);

    const [{ data: profile }, { data: roles }] = await Promise.all([
      admin.from("profiles")
        .select("subscription_plan,banned,daily_message_count,daily_count_reset_at,language,nigerian_student_mode,kids_mode")
        .eq("user_id", userId).maybeSingle(),
      admin.from("user_roles").select("role").eq("user_id", userId),
    ]);
    if (profile?.banned) {
      return new Response(JSON.stringify({ error: "Your account has been suspended. Contact support." }), {
        status: 403, headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }
    const isAdmin = !!roles?.some((r: any) => r.role === "admin");
    const plan = profile?.subscription_plan ?? "free";
    const unlimited = isAdmin || plan === "basic" || plan === "premium" || plan === "ultra";

    let dailyCount = profile?.daily_message_count ?? 0;
    const lastReset = profile?.daily_count_reset_at ? new Date(profile.daily_count_reset_at) : new Date(0);
    const now = new Date();
    if (now.toDateString() !== lastReset.toDateString()) {
      dailyCount = 0;
      await admin.from("profiles").update({
        daily_message_count: 0, daily_count_reset_at: now.toISOString(),
      }).eq("user_id", userId);
    }

    if (mode !== "translate" && !unlimited && dailyCount >= dailyLimit) {
      return new Response(JSON.stringify({
        error: `Daily free limit reached (${dailyLimit} messages). Upgrade to Basic, Premium or Ultra for unlimited messages.`,
        limit_reached: true,
      }), { status: 429, headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    const lang = language || profile?.language || "en";
    const langNames: Record<string, string> = {
      en: "English", pcm: "Nigerian Pidgin", yo: "Yoruba", ig: "Igbo", ha: "Hausa",
      fr: "French", es: "Spanish", ar: "Arabic", pt: "Portuguese", de: "German", zh: "Chinese",
    };
    const toneInstr = tone === "professional" ? " Use a professional, formal tone."
      : tone === "casual" ? " Use a casual, conversational tone."
      : tone === "funny" ? " Be light-hearted and humorous, while still helpful."
      : tone === "simple" ? " Use very simple words a child could understand." : "";
    const lenInstr = respLen === "short" ? " Keep responses short — under 80 words."
      : respLen === "detailed" ? " Provide a thorough, detailed response with examples." : "";
    const studentInstr = profile?.nigerian_student_mode
      ? " The user is a Nigerian university student (FUNAAB or similar). Tailor examples to Nigerian university life, JAMB/WAEC/NECO/POST-UTME prep, CGPA, and course registration where relevant." : "";
    const kidsInstr = profile?.kids_mode
      ? " IMPORTANT: kid-safe mode. No profanity, no adult themes. Keep it age-appropriate and friendly." : "";

    const sysMsg = mode === "translate"
      ? { role: "system", content: `You are a translation engine. Translate the user's text into ${langNames[lang] || lang}. Output ONLY the translation, no explanations.` }
      : { role: "system", content: `${PERSONA}\n\nAlways respond in ${langNames[lang] || "English"}.${toneInstr}${lenInstr}${studentInstr}${kidsInstr}` };
    const fullMessages = [sysMsg, ...messages];

    const tryOrder: Provider[] = [active, ...ORDER.filter((p) => p !== active)];
    const errors: string[] = [];
    for (const p of tryOrder) {
      try {
        const raw = await callProvider(p, fullMessages);
        if (raw) {
          const content = scrub(raw);
          if (mode !== "translate") {
            await admin.from("profiles").update({
              daily_message_count: dailyCount + 1,
              last_active_at: new Date().toISOString(),
            }).eq("user_id", userId);
          }
          // Never expose internal provider name to the client
          return new Response(JSON.stringify({ content, provider: "HI THERE" }), {
            headers: { ...corsHeaders, "Content-Type": "application/json" },
          });
        }
      } catch (e: any) {
        console.error(`Provider ${p} failed:`, e?.message || e);
        errors.push(`${p}`);
      }
    }
    return new Response(JSON.stringify({ error: "All providers temporarily unavailable. Please try again." }), {
      status: 502, headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  } catch (e: any) {
    console.error("chat error:", e);
    return new Response(JSON.stringify({ error: e?.message || "Unknown error" }), {
      status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }
});
