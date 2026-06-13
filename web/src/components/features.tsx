import {
	DatabaseIcon,
	GaugeIcon,
	RadioIcon,
	Share2Icon,
	UsersIcon,
	ZapIcon,
} from "lucide-react";

type Feature = {
	icon: React.ReactNode;
	title: string;
	description: string;
};

const features: Feature[] = [
	{
		icon: <UsersIcon />,
		title: "Identity & Auth",
		description:
			"Signup, login, JWT access tokens with refresh-token rotation, and a Redis-backed revocation list. Uniqueness enforced at the database, not in app code.",
	},
	{
		icon: <Share2Icon />,
		title: "Social Graph",
		description:
			"Friend, follow, and block edges sharded by user ID. Idempotent writes, mutual lookups, and eventually-consistent counts reconciled against the source of truth.",
	},
	{
		icon: <ZapIcon />,
		title: "Real-Time Interactions",
		description:
			"Hybrid fan-out — on-write for normal users, on-read for celebrities — so a high-follower post never tries to write 50M feed entries at once.",
	},
	{
		icon: <DatabaseIcon />,
		title: "Caching Strategy",
		description:
			"Read-through Redis for hot profiles with stampede protection, negative caching, and jittered TTLs. Volatile cache kept separate from durable auth state.",
	},
	{
		icon: <RadioIcon />,
		title: "Event Pipeline",
		description:
			"Kafka with idempotent consumers keyed on a dedup ID, per-edge sequence numbers for ordering, and a dead-letter queue for poison-pill messages.",
	},
	{
		icon: <GaugeIcon />,
		title: "Observability",
		description:
			"Prometheus metrics, Grafana dashboards, and distributed tracing across services — with alerting that catches latency and error-rate degradation early.",
	},
];

export function Features() {
	return (
		<section id="features" className="mx-auto w-full max-w-5xl scroll-mt-20 px-4 py-16 md:py-24">
			<div className="mx-auto mb-10 max-w-2xl text-center md:mb-14">
				<p className="font-mono text-primary text-xs tracking-widest uppercase">
					What it does
				</p>
				<h2 className="mt-3 text-balance font-semibold text-2xl md:text-4xl">
					Backend services built for scale and reliability
				</h2>
				<p className="mt-4 text-balance text-muted-foreground text-sm md:text-base">
					Each capability is an independently deployable service, designed around
					the failure modes that actually bite at millions of users.
				</p>
			</div>

			<div className="grid gap-px overflow-hidden rounded-xl border bg-border sm:grid-cols-2 lg:grid-cols-3">
				{features.map((feature) => (
					<div
						key={feature.title}
						className="group flex flex-col gap-3 bg-card p-6 transition-colors hover:bg-accent/40"
					>
						<div className="flex size-10 items-center justify-center rounded-lg bg-primary/10 text-primary [&_svg]:size-5">
							{feature.icon}
						</div>
						<h3 className="font-semibold text-base">{feature.title}</h3>
						<p className="text-muted-foreground text-sm leading-relaxed">
							{feature.description}
						</p>
					</div>
				))}
			</div>
		</section>
	);
}
