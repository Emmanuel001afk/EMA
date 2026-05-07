
-- Extend profiles
alter table public.profiles
  add column if not exists email text,
  add column if not exists subscription_plan text not null default 'free',
  add column if not exists banned boolean not null default false,
  add column if not exists last_active_at timestamptz default now(),
  add column if not exists language text not null default 'en',
  add column if not exists theme text not null default 'dark',
  add column if not exists daily_message_count int not null default 0,
  add column if not exists daily_count_reset_at timestamptz not null default now(),
  add column if not exists subscription_expires_at timestamptz;

-- Add CHECK via validation trigger pattern would be heavy; use simple constraints
do $$ begin
  alter table public.profiles add constraint profiles_plan_check check (subscription_plan in ('free','basic','premium'));
exception when duplicate_object then null; end $$;

-- Allow admin to read/update all profiles
drop policy if exists "admin read all profiles" on public.profiles;
create policy "admin read all profiles" on public.profiles
  for select using (public.has_role(auth.uid(),'admin'));
drop policy if exists "admin update all profiles" on public.profiles;
create policy "admin update all profiles" on public.profiles
  for update using (public.has_role(auth.uid(),'admin'));
drop policy if exists "admin delete profiles" on public.profiles;
create policy "admin delete profiles" on public.profiles
  for delete using (public.has_role(auth.uid(),'admin'));

-- messages: attachments + translations
alter table public.messages
  add column if not exists attachments jsonb not null default '[]'::jsonb;

create table if not exists public.message_translations (
  id uuid primary key default gen_random_uuid(),
  message_id uuid not null references public.messages(id) on delete cascade,
  user_id uuid not null,
  language text not null,
  content text not null,
  created_at timestamptz not null default now(),
  unique(message_id, language)
);
alter table public.message_translations enable row level security;
create policy "translations owner all" on public.message_translations for all
  using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- announcements
create table if not exists public.announcements (
  id uuid primary key default gen_random_uuid(),
  title text not null,
  body text not null,
  created_by uuid,
  created_at timestamptz not null default now()
);
alter table public.announcements enable row level security;
create policy "announcements readable by all auth" on public.announcements for select
  using (auth.uid() is not null);
create policy "announcements admin write" on public.announcements for all
  using (public.has_role(auth.uid(),'admin')) with check (public.has_role(auth.uid(),'admin'));

-- notifications
create table if not exists public.notifications (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null,
  title text not null,
  body text not null,
  kind text not null default 'system',
  read boolean not null default false,
  created_at timestamptz not null default now()
);
alter table public.notifications enable row level security;
create policy "notifications owner read" on public.notifications for select using (auth.uid() = user_id);
create policy "notifications owner update" on public.notifications for update using (auth.uid() = user_id);
create policy "notifications owner delete" on public.notifications for delete using (auth.uid() = user_id);
create policy "notifications admin insert" on public.notifications for insert
  with check (public.has_role(auth.uid(),'admin') or auth.uid() = user_id);

-- Trigger: when an announcement is created, fan out notifications to all profiles
create or replace function public.fanout_announcement()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  insert into public.notifications(user_id, title, body, kind)
  select user_id, new.title, new.body, 'announcement' from public.profiles;
  return new;
end $$;
drop trigger if exists trg_fanout_announcement on public.announcements;
create trigger trg_fanout_announcement after insert on public.announcements
  for each row execute function public.fanout_announcement();

-- transactions
create table if not exists public.transactions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null,
  reference text unique,
  amount_kobo bigint not null,
  currency text not null default 'NGN',
  plan text not null,
  status text not null default 'pending',
  provider text not null default 'paystack',
  raw jsonb,
  created_at timestamptz not null default now()
);
alter table public.transactions enable row level security;
create policy "tx owner read" on public.transactions for select using (auth.uid() = user_id);
create policy "tx owner insert" on public.transactions for insert with check (auth.uid() = user_id);
create policy "tx admin all" on public.transactions for all
  using (public.has_role(auth.uid(),'admin')) with check (public.has_role(auth.uid(),'admin'));

-- Default app_settings rows
insert into public.app_settings(key, value) values
  ('active_provider', '"gemini"'::jsonb),
  ('contact_email', '"halwayinofficial@gmail.com"'::jsonb),
  ('contact_phone', '"07043470672"'::jsonb),
  ('paystack_public_key', '""'::jsonb),
  ('paystack_secret_key', '""'::jsonb),
  ('daily_free_limit', '20'::jsonb),
  ('basic_price_naira', '2000'::jsonb),
  ('premium_price_naira', '5000'::jsonb)
on conflict (key) do nothing;

-- Update handle_new_user to always grant admin to designated email
create or replace function public.handle_new_user()
returns trigger language plpgsql security definer set search_path = public as $$
declare is_first boolean;
begin
  insert into public.profiles(user_id, display_name, email)
  values (new.id, coalesce(new.raw_user_meta_data->>'display_name', split_part(new.email,'@',1)), new.email);
  select count(*) = 0 into is_first from public.user_roles where role = 'admin';
  if is_first or lower(new.email) = 'emmanuelakintunde004@gmail.com' then
    insert into public.user_roles(user_id, role) values (new.id, 'admin')
    on conflict do nothing;
  else
    insert into public.user_roles(user_id, role) values (new.id, 'user')
    on conflict do nothing;
  end if;
  return new;
end $$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- Backfill: if the designated admin already exists, grant role
insert into public.user_roles(user_id, role)
select u.id, 'admin'::app_role from auth.users u
where lower(u.email) = 'emmanuelakintunde004@gmail.com'
on conflict do nothing;

-- Backfill emails into profiles
update public.profiles p set email = u.email
from auth.users u where p.user_id = u.id and p.email is null;

-- Storage bucket for chat uploads (private)
insert into storage.buckets (id, name, public) values ('chat-uploads','chat-uploads', false)
on conflict (id) do nothing;

create policy "chat uploads owner read"
  on storage.objects for select to authenticated
  using (bucket_id = 'chat-uploads' and auth.uid()::text = (storage.foldername(name))[1]);
create policy "chat uploads owner insert"
  on storage.objects for insert to authenticated
  with check (bucket_id = 'chat-uploads' and auth.uid()::text = (storage.foldername(name))[1]);
create policy "chat uploads owner delete"
  on storage.objects for delete to authenticated
  using (bucket_id = 'chat-uploads' and auth.uid()::text = (storage.foldername(name))[1]);
