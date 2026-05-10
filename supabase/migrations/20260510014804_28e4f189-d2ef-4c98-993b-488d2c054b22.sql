ALTER TABLE public.profiles
  ADD COLUMN IF NOT EXISTS voice_name text NOT NULL DEFAULT 'default',
  ADD COLUMN IF NOT EXISTS memories jsonb NOT NULL DEFAULT '[]'::jsonb;