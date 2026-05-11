
-- Cooldown tracking
ALTER TABLE public.profiles
  ADD COLUMN IF NOT EXISTS cooldown_count integer NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS cooldown_until timestamptz,
  ADD COLUMN IF NOT EXISTS bonus_messages integer NOT NULL DEFAULT 0;

-- Reports table
CREATE TABLE IF NOT EXISTS public.reports (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL,
  message_id uuid,
  conversation_id uuid,
  content text NOT NULL,
  reason text,
  created_at timestamptz NOT NULL DEFAULT now(),
  resolved boolean NOT NULL DEFAULT false
);
ALTER TABLE public.reports ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "reports owner insert" ON public.reports;
CREATE POLICY "reports owner insert" ON public.reports
  FOR INSERT WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "reports owner read" ON public.reports;
CREATE POLICY "reports owner read" ON public.reports
  FOR SELECT USING (auth.uid() = user_id OR public.has_role(auth.uid(), 'admin'::app_role));

DROP POLICY IF EXISTS "reports admin all" ON public.reports;
CREATE POLICY "reports admin all" ON public.reports
  FOR ALL USING (public.has_role(auth.uid(), 'admin'::app_role))
  WITH CHECK (public.has_role(auth.uid(), 'admin'::app_role));
