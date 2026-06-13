import { Header } from "@/components/header";
import { HeroSection } from "@/components/hero";
import { Features } from "@/components/features";
import { Architecture } from "@/components/architecture";
import { LogosSection } from "@/components/logos-section";
import { CallToAction } from "@/components/cta";
import { Footer } from "@/components/footer";

export default function Home() {
  return (
    <>
      <Header />
      <main className="flex-1">
        <HeroSection />
        <Features />
        <Architecture />
        <LogosSection />
        <CallToAction />
      </main>
      <Footer />
    </>
  );
}
