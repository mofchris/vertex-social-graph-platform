"use client";

import Link from "next/link";
import { Logo } from "@/components/logo";
import { Button } from "@/components/ui/button";
import {
	InputGroup,
	InputGroupAddon,
	InputGroupInput,
} from "@/components/ui/input-group";
import { FloatingPaths } from "@/components/floating-paths";
import { PulseGraph } from "@/components/pulse-graph";
import { ChevronLeftIcon, AtSignIcon } from "lucide-react";

type Mode = "login" | "signup";

const content = {
	login: {
		heading: "Welcome back",
		sub: "Sign in to your Vertex account.",
		action: "Sign in",
		quote:
			"Identity, the social graph, and real-time feeds — engineered to stay fast and correct at the scale of millions of users.",
		crossText: "New to Vertex?",
		crossLabel: "Create an account",
		crossHref: "/signup",
	},
	signup: {
		heading: "Create your account",
		sub: "Join Vertex and start building.",
		action: "Create account",
		quote:
			"One immutable identity, a graph of relationships, and events that flow in real time — the backend for social, done right.",
		crossText: "Already have an account?",
		crossLabel: "Sign in",
		crossHref: "/login",
	},
} satisfies Record<Mode, unknown>;

export function AuthPage({ mode }: { mode: Mode }) {
	const c = content[mode];

	return (
		<main className="relative md:h-screen md:overflow-hidden lg:grid lg:grid-cols-2">
			<div className="relative hidden h-full flex-col border-r bg-secondary p-10 lg:flex dark:bg-secondary/20">
				<div className="absolute inset-0 bg-linear-to-b from-transparent via-transparent to-background" />
				<Logo className="mr-auto h-4.5" />

				<div className="z-10 mt-auto">
					<blockquote className="space-y-2">
						<p className="text-xl">&ldquo;{c.quote}&rdquo;</p>
						<footer className="font-mono font-semibold text-sm">
							~ The Vertex platform
						</footer>
					</blockquote>
				</div>

				<div className="absolute inset-0">
					{mode === "login" ? (
						<>
							<FloatingPaths position={1} />
							<FloatingPaths position={-1} />
						</>
					) : (
						<PulseGraph />
					)}
				</div>
			</div>

			<div className="relative flex min-h-screen flex-col justify-center px-8">
				{/* Top Shades */}
				<div
					aria-hidden
					className="absolute inset-0 isolate -z-10 opacity-60 contain-strict"
				>
					<div className="absolute top-0 right-0 h-320 w-140 -translate-y-87.5 rounded-full bg-[radial-gradient(68.54%_68.72%_at_55.02%_31.46%,--theme(--color-primary/.08)_0,hsla(0,0%,55%,.02)_50%,--theme(--color-foreground/.01)_80%)]" />
					<div className="absolute top-0 right-0 h-320 w-60 rounded-full bg-[radial-gradient(50%_50%_at_50%_50%,--theme(--color-foreground/.04)_0,--theme(--color-foreground/.01)_80%,transparent_100%)] [translate:5%_-50%]" />
				</div>

				<Button className="absolute top-7 left-5" variant="ghost" render={<Link href="/" />} nativeButton={false}>
					<ChevronLeftIcon data-icon="inline-start" />
					Home
				</Button>

				<div className="mx-auto space-y-5 sm:w-sm">
					<Logo className="h-4.5 lg:hidden" />
					<div className="flex flex-col space-y-1">
						<h1 className="font-bold text-2xl tracking-wide">{c.heading}</h1>
						<p className="text-base text-muted-foreground">{c.sub}</p>
					</div>

					<form className="space-y-2">
						<p className="text-start text-muted-foreground text-xs">
							{mode === "login"
								? "Enter your email to sign in to your account"
								: "Enter your email to create your account"}
						</p>
						<InputGroup>
							<InputGroupInput
								placeholder="your.email@example.com"
								type="email"
							/>
							<InputGroupAddon align="inline-start">
								<AtSignIcon />
							</InputGroupAddon>
						</InputGroup>

						<Button className="w-full" type="button">
							{c.action}
						</Button>
					</form>

					<p className="text-muted-foreground text-sm">
						{c.crossText}{" "}
						<Link
							className="font-medium text-foreground underline underline-offset-4 hover:text-primary"
							href={c.crossHref}
						>
							{c.crossLabel}
						</Link>
					</p>

					<p className="mt-8 text-muted-foreground text-sm">
						By continuing, you agree to our{" "}
						<a
							className="underline underline-offset-4 hover:text-primary"
							href="#"
						>
							Terms of Service
						</a>{" "}
						and{" "}
						<a
							className="underline underline-offset-4 hover:text-primary"
							href="#"
						>
							Privacy Policy
						</a>
						.
					</p>
				</div>
			</div>
		</main>
	);
}
