
create or replace function public.handle_new_user() returns trigger language plpgsql security definer set search_path = public as $$
declare
  is_first boolean;
begin
  insert into public.profiles(user_id, display_name) values (new.id, coalesce(new.raw_user_meta_data->>'display_name', split_part(new.email,'@',1)));
  select count(*) = 0 into is_first from public.user_roles where role = 'admin';
  if is_first then
    insert into public.user_roles(user_id, role) values (new.id, 'admin');
  else
    insert into public.user_roles(user_id, role) values (new.id, 'user');
  end if;
  return new;
end $$;
revoke execute on function public.handle_new_user() from public, anon, authenticated;
