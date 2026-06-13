import { cn } from "@/lib/utils";
import { DecorIcon } from "@/components/decor-icon";

type Tech = { name: string; role: string };

const stack: Tech[] = [
	{ name: "Java", role: "Spring Boot" },
	{ name: "Python", role: "FastAPI" },
	{ name: "PostgreSQL", role: "source of truth" },
	{ name: "Redis", role: "cache + sessions" },
	{ name: "Apache Kafka", role: "event streaming" },
	{ name: "Kubernetes", role: "orchestration" },
	{ name: "Docker", role: "containers" },
	{ name: "Prometheus", role: "metrics + tracing" },
];

export function LogoCloud() {
	return (
		<div className="grid grid-cols-2 border md:grid-cols-4">
			{stack.map((tech, i) => (
				<TechCard key={tech.name} tech={tech} index={i} />
			))}
		</div>
	);
}

function TechCard({ tech, index }: { tech: Tech; index: number }) {
	// Subtle checkerboard shading, mirroring the original layout's rhythm.
	const shaded = (Math.floor(index / 4) + index) % 2 === 0;
	return (
		<div
			className={cn(
				"relative flex flex-col items-center justify-center gap-1 border-r border-b px-4 py-7 text-center md:p-8",
				shaded && "bg-secondary dark:bg-secondary/30"
			)}
		>
			<span className="font-semibold text-foreground text-sm md:text-base">
				{tech.name}
			</span>
			<span className="font-mono text-[0.65rem] text-muted-foreground tracking-wide md:text-xs">
				{tech.role}
			</span>
			{index % 3 === 0 && <DecorIcon className="z-10" position="bottom-right" />}
		</div>
	);
}
