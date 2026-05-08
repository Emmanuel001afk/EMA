
create or replace function public.gen_referral_code()
returns text language sql set search_path=public as $$
  select upper(substr(md5(random()::text || clock_timestamp()::text), 1, 8))
$$;

revoke execute on function public.get_leaderboard() from public, anon;
grant execute on function public.get_leaderboard() to authenticated;
revoke execute on function public.gen_referral_code() from public, anon;
