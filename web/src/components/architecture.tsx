import { cn } from "@/lib/utils";
import { ChevronDownIcon } from "lucide-react";

const services = [
	{ name: "Identity", note: "auth · tokens" },
	{ name: "Profile", note: "data · settings" },
	{ name: "Graph", note: "edges · blocks" },
	{ name: "Feed", note: "fan-out" },
	{ name: "Recommend", note: "people you know" },
	{ name: "Notify", note: "real-time" },
];

const stores = [
	{ name: "PostgreSQL", note: "sharded by user ID · read replicas" },
	{ name: "Redis", note: "cache · sessions · rate limits" },
	{ name: "Kafka", note: "event log · async fan-out" },
];

function Connector() {
	return (
		<div className="flex flex-col items-center py-3 text-muted-foreground">
			<span className="h-4 w-px bg-border" />
			<ChevronDownIcon className="size-4" />
		</div>
	);
}

function Layer({
	label,
	children,
	className,
}: {
	label: string;
	children: React.ReactNode;
	className?: string;
}) {
	return (
		<div className={cn("w-full rounded-xl border bg-card/70 p-4 backdrop-blur-sm", className)}>
			<p className="mb-3 text-center font-mono text-[0.65rem] text-muted-foreground tracking-widest uppercase">
				{label}
			</p>
			{children}
		</div>
	);
}

export function Architecture() {
	return (
		<section id="architecture" className="mx-auto w-full max-w-3xl scroll-mt-20 px-4 py-16 md:py-24">
			<div className="mx-auto mb-10 max-w-2xl text-center md:mb-14">
				<p className="font-mono text-primary text-xs tracking-widest uppercase">
					How it fits together
				</p>
				<h2 className="mt-3 text-balance font-semibold text-2xl md:text-4xl">
					A microservices architecture
				</h2>
				<p className="mt-4 text-balance text-muted-foreground text-sm md:text-base">
					Stateless services scale independently behind a gateway. They talk over
					gRPC for synchronous calls and Kafka for asynchronous events.
				</p>
			</div>

			<div className="flex flex-col items-center">
				<Layer label="Clients">
					<div className="flex flex-wrap justify-center gap-2">
						{["Web", "Mobile", "Public REST API"].map((c) => (
							<span
								key={c}
								className="rounded-md border bg-background px-3 py-1.5 text-sm"
							>
								{c}
							</span>
						))}
					</div>
				</Layer>

				<Connector />

				<Layer label="API Gateway" className="border-primary/40">
					<p className="text-center text-sm">
						<span className="font-medium text-foreground">
							Auth verification · routing · rate limiting
						</span>
					</p>
				</Layer>

				<Connector />

				<Layer label="Services (gRPC + Kafka)">
					<div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
						{services.map((s) => (
							<div
								key={s.name}
								className="rounded-lg border bg-background p-3 text-center"
							>
								<div className="font-medium text-sm">{s.name}</div>
								<div className="font-mono text-[0.6rem] text-muted-foreground tracking-wide">
									{s.note}
								</div>
							</div>
						))}
					</div>
				</Layer>

				<Connector />

				<Layer label="Data Layer">
					<div className="grid gap-2 sm:grid-cols-3">
						{stores.map((s) => (
							<div
								key={s.name}
								className="rounded-lg bg-primary/10 p-3 text-center"
							>
								<div className="font-semibold text-primary text-sm">
									{s.name}
								</div>
								<div className="mt-0.5 text-[0.65rem] text-muted-foreground leading-tight">
									{s.note}
								</div>
							</div>
						))}
					</div>
				</Layer>
			</div>
		</section>
	);
}
