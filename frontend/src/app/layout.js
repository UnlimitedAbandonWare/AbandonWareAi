import "./globals.css";

export const metadata = {
  title: "Demo-1 RAG Console",
  description: "Desktop RAG chat console"
};

export default function RootLayout({ children }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
