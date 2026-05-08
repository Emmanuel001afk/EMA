import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

type Provider = "gemini" | "groq" | "mistral";
const ORDER: Provider[] = ["gemini", "groq", "mistral"];

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

async function callOpenAICompat(url: string, model: string, messages: any[], key: string, label: string) {
  const r = await fetch(url, {
    method: "POST",
    headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
    body: JSON.stringify({ model, messages }),
  });
  if (!r.ok) throw new Error(`${label} ${r.status}: ${await r.text()}`);
  const j = await r.json();
  return j.choices?.[0]?.message?.content ?? "";
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
    const active = ((settings.active_provider as string) || "gemini") as Provider;
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
    const sysMsg = mode === "translate"
      ? { role: "system", content: `You are a translation engine. Translate the user's text into ${langNames[lang] || lang}. Output ONLY the translation, no explanations.` }
      : { role: "system", content: `You are HI THERE, a friendly helpful AI assistant. Always respond in ${langNames[lang] || "English"}. Be warm and concise.` };
    const fullMessages = [sysMsg, ...messages];

    // Build failover order: active first, then the rest in default order
    const tryOrder: Provider[] = [active, ...ORDER.filter((p) => p !== active)];

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
          return new Response(JSON.stringify({ content, provider: p }), {
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