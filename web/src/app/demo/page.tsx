import type { Metadata } from "next";
import { Console } from "@/components/demo/Console";

export const metadata: Metadata = {
  title: "Live demo",
  description:
    "Operate the real Vertex backend: follow, post, replay an event, send a poison record, and trace each one through the outbox, Kafka and Notify.",
};

export default function DemoPage() {
  return (
    <>
      <h1 className="sr-only">Vertex live demo</h1>
      <Console />
    </>
  );
}
