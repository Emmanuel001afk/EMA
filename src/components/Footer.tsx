export function Footer() {
  return (
    <footer className="border-t border-border bg-background/60 px-4 py-4 text-center text-xs text-muted-foreground">
      <p>
        Need help? Email{" "}
        <a className="text-primary hover:underline" href="mailto:halwayinofficial@gmail.com">
          halwayinofficial@gmail.com
        </a>{" "}
        · WhatsApp{" "}
        <a
          className="text-primary hover:underline"
          href="https://wa.me/2348125620535"
          target="_blank"
          rel="noreferrer"
        >
          08125620535
        </a>
      </p>
      <p className="mt-1 opacity-70">© {new Date().getFullYear()} HI THERE</p>
    </footer>
  );
}