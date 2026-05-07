export const LANGUAGES: { code: string; label: string }[] = [
  { code: "en", label: "English" },
  { code: "pcm", label: "Nigerian Pidgin" },
  { code: "yo", label: "Yoruba" },
  { code: "ig", label: "Igbo" },
  { code: "ha", label: "Hausa" },
  { code: "fr", label: "French" },
  { code: "es", label: "Spanish" },
  { code: "ar", label: "Arabic" },
  { code: "pt", label: "Portuguese" },
];
export const langLabel = (c: string) => LANGUAGES.find((l) => l.code === c)?.label || c;