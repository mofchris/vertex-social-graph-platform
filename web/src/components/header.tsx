"use client";
import Link from "next/link";
import { cn } from "@/lib/utils";
import { Logo } from "@/components/logo";
import { useScroll } from "@/hooks/use-scroll";
import { Button } from "@/components/ui/button";
import { MobileNav } from "@/components/mobile-nav";
import { ModeToggle } from "@/components/mode-toggle";

export const navLinks = [
	{
		label: "Features",
		href: "#features",
	},
	{
		label: "Architecture",
		href: "#architecture",
	},
	{
		label: "Stack",
		href: "#stack",
	},
];

export function Header() {
	const scrolled = useScroll(10);

	return (
		<header
			className={cn(
				"sticky top-0 z-50 mx-auto w-full max-w-4xl border-transparent border-b md:rounded-md md:border md:transition-all md:ease-out",
				{
					"border-border bg-background/95 backdrop-blur-sm supports-backdrop-filter:bg-background/50 md:top-2 md:max-w-3xl md:shadow":
						scrolled,
				}
			)}
		>
			<nav
				className={cn(
					"flex h-14 w-full items-center justify-between px-4 md:h-12 md:transition-all md:ease-out",
					{
						"md:px-2": scrolled,
					}
				)}
			>
				<Link
					className="rounded-md p-2 hover:bg-muted dark:hover:bg-muted/50"
					href="/"
				>
					<Logo className="h-4" />
				</Link>
				<div className="hidden items-center gap-2 md:flex">
					<div>
						{navLinks.map((link) => (
							<Button key={link.label} size="sm" variant="ghost" render={<a href={link.href} />} nativeButton={false}>{link.label}</Button>
						))}
					</div>
					<ModeToggle />
					<Button size="sm" variant="outline" render={<Link href="/login" />} nativeButton={false}>
						Sign In
					</Button>
					<Button size="sm" render={<Link href="/signup" />} nativeButton={false}>Get Started</Button>
				</div>
				<div className="flex items-center gap-1 md:hidden">
					<ModeToggle />
					<MobileNav />
				</div>
			</nav>
		</header>
	);
}
