import { Link } from "@tanstack/react-router";

export function Footer() {
  return (
    <footer className="border-t border-border bg-background/60 px-4 py-4 text-center text-xs text-muted-foreground">
      <nav className="flex flex-wrap justify-center gap-x-4 gap-y-1">
        <Link to="/terms" className="hover:text-foreground">Terms</Link>
        <Link to="/privacy" className="hover:text-foreground">Privacy</Link>
        <Link to="/faq" className="hover:text-foreground">FAQ</Link>
        <Link to="/contact" className="hover:text-foreground">Contact</Link>
      </nav>
      <p className="mt-2 opacity-70">Support: halwayinofficial@gmail.com</p>
      <p className="opacity-60">© {new Date().getFullYear()} HI THERE</p>
    </footer>
  );
}