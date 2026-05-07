import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: corsHeaders });
  try {
    const { reference, plan } = await req.json();
    if (!reference || !plan) throw new Error("Missing reference/plan");

    const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const admin = createClient(supabaseUrl, serviceKey);

    const authHeader = req.headers.get("Authorization") || "";
    const jwt = authHeader.replace("Bearer ", "");
    const { data: u } = await admin.auth.getUser(jwt);
    const userId = u?.user?.id;
    if (!userId) throw new Error("Not authenticated");

    const { data: setting } = await admin.from("app_settings").select("value")
      .eq("key", "paystack_secret_key").maybeSingle();
    const secret = (setting?.value as string) || "";
    if (!secret) throw new Error("Paystack not configured. Ask admin to add Paystack secret key.");

    const r = await fetch(`https://api.paystack.co/transaction/verify/${reference}`, {
      headers: { Authorization: `Bearer ${secret}` },
    });
    const j = await r.json();
    if (!j.status || j.data?.status !== "success") {
      return new Response(JSON.stringify({ error: "Verification failed", raw: j }), {
        status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const expires = new Date(); expires.setMonth(expires.getMonth() + 1);
    await admin.from("transactions").insert({
      user_id: userId, reference, amount_kobo: j.data.amount, currency: j.data.currency,
      plan, status: "success", raw: j.data,
    });
    await admin.from("profiles").update({
      subscription_plan: plan,
      subscription_expires_at: expires.toISOString(),
    }).eq("user_id", userId);
    await admin.from("notifications").insert({
      user_id: userId, title: "Subscription activated",
      body: `Your ${plan} plan is now active until ${expires.toDateString()}.`,
      kind: "subscription",
    });

    return new Response(JSON.stringify({ ok: true, plan }), {
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  } catch (e: any) {
    return new Response(JSON.stringify({ error: e?.message || "Unknown error" }), {
      status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }
});