import { Archivo, Martian_Mono } from "next/font/google";

// One family for display and text (Archivo's width axis gives the wide panel lettering and the
// normal-width reading face), plus a mono for every timing, offset and id.
export const archivo = Archivo({
  subsets: ["latin"],
  axes: ["wdth"],
  variable: "--font-archivo",
  display: "swap",
});

export const mono = Martian_Mono({
  subsets: ["latin"],
  weight: ["400", "500"],
  variable: "--font-mono",
  display: "swap",
});
