import { Link } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { supabase } from "@/integrations/supabase/client";

export function Footer() {
  const [email, setEmail] = useState<string>("");
  useEffect(() => {
    supabase.from("app_settings").select("value").eq("key", "contact_email").maybeSingle()
      .then(({ data }) => { if (data?.value) setEmail(String(data.value)); });
  }, []);
  return (
    <footer className="border-t border-border bg-background/60 px-4 py-4 text-center text-xs text-muted-foreground">
      <nav className="flex flex-wrap justify-center gap-x-4 gap-y-1">
        <Link to="/terms" className="hover:text-foreground">Terms</Link>
        <Link to="/privacy" className="hover:text-foreground">Privacy</Link>
        <Link to="/faq" className="hover:text-foreground">FAQ</Link>
        <Link to="/contact" className="hover:text-foreground">Contact</Link>
        {email && (
          <a href={`mailto:${email}`} className="hover:text-foreground">{email}</a>
        )}
      </nav>
      <p className="mt-2 opacity-70">© {new Date().getFullYear()} HI THERE</p>
    </footer>
  );
}