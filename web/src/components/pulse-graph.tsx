"use client";
import { motion } from "motion/react";

type N = { x: number; y: number };

const nodes: N[] = [
	{ x: 80, y: 90 },
	{ x: 300, y: 70 },
	{ x: 200, y: 175 },
	{ x: 60, y: 260 },
	{ x: 330, y: 250 },
	{ x: 180, y: 330 },
	{ x: 95, y: 420 },
	{ x: 310, y: 415 },
	{ x: 245, y: 470 },
	{ x: 150, y: 475 },
];

const edges: [number, number][] = [
	[0, 2], [1, 2], [2, 4], [2, 3], [3, 5],
	[4, 5], [5, 6], [5, 7], [6, 9], [7, 8], [4, 7],
];

/**
 * Signup-side animation: a living social graph. Nodes pulse and edges breathe,
 * deliberately different from the flowing FloatingPaths used on /login.
 */
export function PulseGraph() {
	return (
		<div className="pointer-events-none absolute inset-0">
			<svg
				className="h-full w-full text-primary"
				viewBox="0 0 400 540"
				fill="none"
				preserveAspectRatio="xMidYMid slice"
			>
				<title>Animated social graph</title>

				{edges.map(([a, b], i) => {
					const n1 = nodes[a];
					const n2 = nodes[b];
					return (
						<motion.line
							key={`e-${i}`}
							x1={n1.x}
							y1={n1.y}
							x2={n2.x}
							y2={n2.y}
							stroke="currentColor"
							strokeWidth={1.2}
							initial={{ opacity: 0.1, pathLength: 0 }}
							animate={{ opacity: [0.1, 0.35, 0.1], pathLength: 1 }}
							transition={{
								duration: 5 + (i % 4),
								repeat: Number.POSITIVE_INFINITY,
								ease: "easeInOut",
								delay: i * 0.3,
							}}
						/>
					);
				})}

				{nodes.map((n, i) => (
					<g key={`n-${i}`}>
						<motion.circle
							cx={n.x}
							cy={n.y}
							r={14}
							fill="currentColor"
							initial={{ opacity: 0.15, scale: 0.6 }}
							animate={{ opacity: [0.12, 0.25, 0.12], scale: [0.6, 1.4, 0.6] }}
							transition={{
								duration: 4 + (i % 3),
								repeat: Number.POSITIVE_INFINITY,
								ease: "easeInOut",
								delay: i * 0.4,
							}}
							style={{ transformOrigin: `${n.x}px ${n.y}px` }}
						/>
						<circle cx={n.x} cy={n.y} r={3.5} fill="currentColor" />
					</g>
				))}
			</svg>
		</div>
	);
}
