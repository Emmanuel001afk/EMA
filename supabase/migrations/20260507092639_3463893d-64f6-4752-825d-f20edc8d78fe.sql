
-- Roles
create type public.app_role as enum ('admin','user');

create table public.profiles (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null unique,
  display_name text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
alter table public.profiles enable row level security;
create policy "profiles readable by owner" on public.profiles for select using (auth.uid() = user_id);
create policy "profiles insert own" on public.profiles for insert with check (auth.uid() = user_id);
create policy "profiles update own" on public.profiles for update using (auth.uid() = user_id);

create table public.user_roles (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null,
  role public.app_role not null,
  unique(user_id, role)
);
alter table public.user_roles enable row level security;

create or replace function public.has_role(_user_id uuid, _role public.app_role)
returns boolean language sql stable security definer set search_path = public as $$
  select exists(select 1 from public.user_roles where user_id=_user_id and role=_role)
$$;

create policy "roles self read" on public.user_roles for select using (auth.uid() = user_id or public.has_role(auth.uid(),'admin'));
create policy "admin manage roles" on public.user_roles for all using (public.has_role(auth.uid(),'admin')) with check (public.has_role(auth.uid(),'admin'));

-- Conversations & messages
create table public.conversations (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null,
  title text not null default 'New Chat',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
alter table public.conversations enable row level security;
create policy "conv owner all" on public.conversations for all using (auth.uid()=user_id) with check (auth.uid()=user_id);

create table public.messages (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references public.conversations(id) on delete cascade,
  user_id uuid not null,
  role text not null check (role in ('user','assistant')),
  content text not null,
  provider text,
  created_at timestamptz not null default now()
);
alter table public.messages enable row level security;
create policy "msg owner all" on public.messages for all using (auth.uid()=user_id) with check (auth.uid()=user_id);

-- Settings
create table public.app_settings (
  key text primary key,
  value jsonb not null,
  updated_at timestamptz not null default now()
);
alter table public.app_settings enable row level security;
create policy "settings readable by all auth" on public.app_settings for select using (auth.uid() is not null);
create policy "settings admin write" on public.app_settings for all using (public.has_role(auth.uid(),'admin')) with check (public.has_role(auth.uid(),'admin'));

insert into public.app_settings(key,value) values ('active_provider', '"gemini"'::jsonb);

-- Update timestamp helper
create or replace function public.touch_updated_at() returns trigger language plpgsql as $$
begin new.updated_at = now(); return new; end $$;

create trigger t_profiles_upd before update on public.profiles for each row execute function public.touch_updated_at();
create trigger t_conv_upd before update on public.conversations for each row execute function public.touch_updated_at();
create trigger t_settings_upd before update on public.app_settings for each row execute function public.touch_updated_at();

-- New user trigger
create or replace function public.handle_new_user() returns trigger language plpgsql security definer set search_path = public as $$
begin
  insert into public.profiles(user_id, display_name) values (new.id, coalesce(new.raw_user_meta_data->>'display_name', split_part(new.email,'@',1)));
  insert into public.user_roles(user_id, role) values (new.id, 'user');
  return new;
end $$;

create trigger on_auth_user_created after insert on auth.users for each row execute function public.handle_new_user();
