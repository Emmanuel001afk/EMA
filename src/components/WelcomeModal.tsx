import { useEffect, useState } from "react";
import { Sparkles, X } from "lucide-react";
const KEY = "hi-there-welcome-seen-v1";
export function WelcomeModal({ name }: { name: string }) {
  const [open, setOpen] = useState(false);
  useEffect(() => {
    try { if (!localStorage.getItem(KEY)) setOpen(true); } catch {}
  }, []);
  const close = () => { try { localStorage.setItem(KEY, "1"); } catch {}; setOpen(false); };
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-[80] grid place-items-center bg-black/60 p-4">
      <div className="relative w-full max-w-md rounded-2xl border border-border bg-card p-6 shadow-2xl">
        <button onClick={close} className="absolute right-3 top-3 rounded-md p-1 hover:bg-accent"><X className="h-4 w-4" /></button>
        <div className="grid h-12 w-12 place-items-center rounded-2xl bg-gradient-to-br from-primary to-accent text-primary-foreground">
          <Sparkles className="h-6 w-6" />
        </div>
        <h2 className="mt-4 text-xl font-bold">Welcome{name ? `, ${name}` : ""}! 🎉</h2>
        <p className="mt-2 text-sm text-muted-foreground">
          I'm <span className="font-semibold text-foreground">HI THERE</span> — your correct AI assistant. I'm here to help you sharp-sharp with anything: assignments, ideas, code, translation, deadlines, you name it.
        </p>
        <ul className="mt-3 space-y-1 text-xs text-muted-foreground">
          <li>• <span className="font-semibold text-foreground">Free</span> — 20 messages/day</li>
          <li>• <span className="font-semibold text-foreground">Basic ₦2,000/mo</span> — unlimited messages</li>
          <li>• <span className="font-semibold text-foreground">Premium ₦5,000/mo</span> — + uploads & priority</li>
          <li>• <span className="font-semibold text-foreground">Ultra ₦8,000/mo</span> — everything unlocked</li>
        </ul>
        <button onClick={close} className="mt-5 w-full rounded-md bg-primary py-2 text-sm font-semibold text-primary-foreground">Let's go</button>
      </div>
    </div>
  );
}
