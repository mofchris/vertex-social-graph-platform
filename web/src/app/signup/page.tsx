import type { Metadata } from "next";
import { AuthPage } from "@/components/auth-page";

export const metadata: Metadata = {
  title: "Create account · Vertex",
  description: "Join Vertex and start building.",
};

export default function Signup() {
  return <AuthPage mode="signup" />;
}
