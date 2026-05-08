
alter table public.profiles
  add column if not exists referral_code text unique,
  add column if not exists referred_by uuid,
  add column if not exists dark_mode_schedule_enabled boolean not null default false,
  add column if not exists dark_mode_start text not null default '18:00',
  add column if not exists dark_mode_end text not null default '06:00',
  add column if not exists nigerian_student_mode boolean not null default false,
  add column if not exists kids_mode boolean not null default false,
  add column if not exists incognito_mode boolean not null default false;

create or replace function public.gen_referral_code()
returns text language sql as $$
  select upper(substr(md5(random()::text || clock_timestamp()::text), 1, 8))
$$;

update public.profiles set referral_code = public.gen_referral_code() where referral_code is null;

create or replace function public.set_referral_code()
returns trigger language plpgsql security definer set search_path=public as $$
begin
  if new.referral_code is null then
    new.referral_code := public.gen_referral_code();
  end if;
  return new;
end $$;

drop trigger if exists profiles_set_referral_code on public.profiles;
create trigger profiles_set_referral_code before insert on public.profiles
for each row execute function public.set_referral_code();

do $$
declare uid uuid;
begin
  select id into uid from auth.users where lower(email) = 'emmanuelakintunde004@gmail.com' limit 1;
  if uid is not null then
    insert into public.user_roles(user_id, role) values (uid, 'admin')
    on conflict do nothing;
  end if;
end $$;

create table if not exists public.referrals (
  id uuid primary key default gen_random_uuid(),
  referrer_id uuid not null,
  referred_id uuid not null unique,
  bonus_granted boolean not null default false,
  created_at timestamptz not null default now()
);
alter table public.referrals enable row level security;
drop policy if exists "referrals self read" on public.referrals;
create policy "referrals self read" on public.referrals for select
  using (auth.uid() = referrer_id or auth.uid() = referred_id or has_role(auth.uid(),'admin'));
drop policy if exists "referrals admin all" on public.referrals;
create policy "referrals admin all" on public.referrals for all
  using (has_role(auth.uid(),'admin')) with check (has_role(auth.uid(),'admin'));
drop policy if exists "referrals user insert" on public.referrals;
create policy "referrals user insert" on public.referrals for insert
  with check (auth.uid() = referred_id);

create table if not exists public.assignments (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null,
  subject text not null,
  title text not null,
  due_date timestamptz not null,
  done boolean not null default false,
  created_at timestamptz not null default now()
);
alter table public.assignments enable row level security;
drop policy if exists "assignments owner all" on public.assignments;
create policy "assignments owner all" on public.assignments for all
  using (auth.uid() = user_id) with check (auth.uid() = user_id);

create table if not exists public.feedback (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null,
  conversation_id uuid,
  rating int not null check (rating between 1 and 5),
  comment text,
  created_at timestamptz not null default now()
);
alter table public.feedback enable row level security;
drop policy if exists "feedback owner insert" on public.feedback;
create policy "feedback owner insert" on public.feedback for insert
  with check (auth.uid() = user_id);
drop policy if exists "feedback owner read" on public.feedback;
create policy "feedback owner read" on public.feedback for select
  using (auth.uid() = user_id or has_role(auth.uid(),'admin'));
drop policy if exists "feedback admin all" on public.feedback;
create policy "feedback admin all" on public.feedback for all
  using (has_role(auth.uid(),'admin')) with check (has_role(auth.uid(),'admin'));

create table if not exists public.study_rooms (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null,
  name text not null,
  invite_code text not null unique default upper(substr(md5(random()::text), 1, 8)),
  created_at timestamptz not null default now()
);
alter table public.study_rooms enable row level security;

create table if not exists public.study_room_members (
  room_id uuid not null,
  user_id uuid not null,
  joined_at timestamptz not null default now(),
  primary key (room_id, user_id)
);
alter table public.study_room_members enable row level security;
drop policy if exists "room members self" on public.study_room_members;
create policy "room members self" on public.study_room_members for all
  using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "rooms owner all" on public.study_rooms;
create policy "rooms owner all" on public.study_rooms for all
  using (auth.uid() = owner_id) with check (auth.uid() = owner_id);
drop policy if exists "rooms member read" on public.study_rooms;
create policy "rooms member read" on public.study_rooms for select
  using (exists (select 1 from public.study_room_members m where m.room_id = id and m.user_id = auth.uid()));

insert into public.app_settings(key, value) values
  ('ultra_price_naira', to_jsonb(8000)),
  ('basic_price_naira', to_jsonb(2000)),
  ('premium_price_naira', to_jsonb(5000)),
  ('paystack_mode', to_jsonb('test'::text)),
  ('paystack_test_public_key', to_jsonb(''::text)),
  ('paystack_test_secret_key', to_jsonb(''::text)),
  ('paystack_live_public_key', to_jsonb(''::text)),
  ('paystack_live_secret_key', to_jsonb(''::text)),
  ('music_enabled', to_jsonb(false)),
  ('music_playlists', '[]'::jsonb),
  ('leaderboard_enabled', to_jsonb(true)),
  ('contact_email', to_jsonb('halwayinofficial@gmail.com'::text))
on conflict (key) do nothing;

create or replace function public.handle_new_user()
returns trigger language plpgsql security definer set search_path = public
as $$
declare
  is_first boolean;
  ref_code text;
  ref_user uuid;
begin
  ref_code := new.raw_user_meta_data->>'ref';
  if ref_code is not null and length(ref_code) > 0 then
    select user_id into ref_user from public.profiles where referral_code = upper(ref_code) limit 1;
  end if;

  insert into public.profiles(user_id, display_name, email, referred_by)
  values (
    new.id,
    coalesce(nullif(trim(new.raw_user_meta_data->>'display_name'), ''), split_part(new.email,'@',1)),
    new.email,
    ref_user
  );

  select count(*) = 0 into is_first from public.user_roles where role = 'admin';
  if is_first or lower(new.email) = 'emmanuelakintunde004@gmail.com' then
    insert into public.user_roles(user_id, role) values (new.id, 'admin')
    on conflict do nothing;
  else
    insert into public.user_roles(user_id, role) values (new.id, 'user')
    on conflict do nothing;
  end if;

  if ref_user is not null then
    insert into public.referrals(referrer_id, referred_id, bonus_granted)
    values (ref_user, new.id, true)
    on conflict do nothing;

    update public.profiles
    set subscription_plan = case when subscription_plan = 'free' then 'basic' else subscription_plan end,
        subscription_expires_at = greatest(coalesce(subscription_expires_at, now()), now()) + interval '1 day'
    where user_id in (ref_user, new.id) and subscription_plan in ('free','basic');

    insert into public.notifications(user_id, title, body, kind)
    values
      (ref_user, '🎉 Referral bonus!', 'A friend signed up using your link. You got 1 free day of Basic.', 'system'),
      (new.id,   '🎉 Welcome bonus!', 'You signed up with a referral. Enjoy 1 free day of Basic on us.', 'system');
  end if;

  return new;
end $$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
after insert on auth.users
for each row execute function public.handle_new_user();

create or replace function public.get_leaderboard()
returns table(display_name text, messages bigint, referrals bigint)
language sql stable security definer set search_path=public as $$
  select
    p.display_name,
    coalesce(m.cnt, 0) as messages,
    coalesce(r.cnt, 0) as referrals
  from public.profiles p
  left join (select user_id, count(*) cnt from public.messages where role='user' group by user_id) m on m.user_id = p.user_id
  left join (select referrer_id, count(*) cnt from public.referrals group by referrer_id) r on r.referrer_id = p.user_id
  where coalesce(m.cnt,0) + coalesce(r.cnt,0) > 0
  order by (coalesce(m.cnt,0)*1 + coalesce(r.cnt,0)*5) desc
  limit 50
$$;
