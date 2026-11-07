import type { Metadata, Viewport } from "next";
import { Header } from "@/components/Header";
import { Engine } from "@/engine/next";
import { site } from "@/data/site";
import { archivo, mono } from "./fonts";
import "./globals.css";

export const metadata: Metadata = {
  metadataBase: new URL(site.url),
  title: { default: "Vertex | A social network you can trace", template: "%s | Vertex" },
  description: site.description,
  alternates: { canonical: "./" },
  openGraph: { type: "website", siteName: site.name, images: ["/og.png"] },
  twitter: { card: "summary_large_image" },
};

export const viewport: Viewport = { themeColor: "#e8ecef" };

// Runs before first paint: hidden-until-revealed styles only apply when JS and motion are on.
const flags = `document.documentElement.classList.add('js');if(!matchMedia('(prefers-reduced-motion: reduce)').matches)document.documentElement.classList.add('motion')`;

const jsonLd = {
  "@context": "https://schema.org",
  "@type": "SoftwareSourceCode",
  name: "Vertex",
  description: site.description,
  codeRepository: site.github,
  programmingLanguage: ["Java", "TypeScript"],
  author: { "@type": "Person", name: site.author },
  url: site.url,
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" className={`${archivo.variable} ${mono.variable}`} suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: flags }} />
      </head>
      <body>
        <a className="skip-link" href="#main">
          Skip to content
        </a>
        <div id="curtain" aria-hidden="true">
          <div className="curtain__panel" />
        </div>
        <Header />
        <main id="main" tabIndex={-1}>
          {children}
        </main>
        <script
          type="application/ld+json"
          dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd).replace(/</g, "\\u003c") }}
        />
        <Engine />
      </body>
    </html>
  );
}
