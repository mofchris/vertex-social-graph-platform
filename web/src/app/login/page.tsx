import type { Metadata } from "next";
import { AuthPage } from "@/components/auth-page";

export const metadata: Metadata = {
  title: "Sign in · Vertex",
  description: "Sign in to your Vertex account.",
};

export default function Login() {
  return <AuthPage mode="login" />;
}
