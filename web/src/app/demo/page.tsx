import type { Metadata } from "next";
import { Console } from "@/components/demo/Console";

export const metadata: Metadata = {
  title: "Live demo",
  description:
    "Operate the real Vertex backend: follow, post, replay an event and send a poison record, and watch each one cross the gateway, Graph's outbox, Kafka and Notify with real timings.",
};

export default function DemoPage() {
  return <Console />;
}
