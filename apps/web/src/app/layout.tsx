import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";

import { SiteHeader } from "@/app/_components/site-header";
import { Providers } from "@/app/_providers";
import { Toaster } from "@/components/ui/sonner";
import { publicSession } from "@/lib/auth/claims";
import { readSession } from "@/lib/auth/cookies";

import "./globals.css";

const geistSans = Geist({ variable: "--font-geist-sans", subsets: ["latin"] });
const geistMono = Geist_Mono({ variable: "--font-geist-mono", subsets: ["latin"] });

export const metadata: Metadata = {
    title: "axonposts · cliente de teste",
    description:
        "Cliente GraphQL para exercitar os fluxos do blog: saga coreografada, federação e identidade.",
};

/**
 * A sessão é lida AQUI, no servidor, e desce por prop.
 *
 * O cookie do ID token é `httpOnly` — o JavaScript da página não o alcança. Este layout o lê da
 * requisição e entrega ao `SessionProvider`, que o põe em memória para o Apollo. O efeito colateral é
 * que toda página passa a ser dinâmica, e é o certo: uma página que mostra quem está logado não pode
 * ser servida de um cache estático.
 */
export default async function RootLayout({ children }: { children: React.ReactNode }) {
    // `publicSession` tira o token: o que desce para o navegador é só quem está logado.
    const session = publicSession(await readSession());

    return (
        <html lang="pt-BR" suppressHydrationWarning>
            <body className={`${geistSans.variable} ${geistMono.variable} antialiased`}>
                <Providers session={session}>
                    <SiteHeader />
                    <main className="mx-auto max-w-5xl px-4 py-8">{children}</main>
                    <Toaster position="bottom-right" />
                </Providers>
            </body>
        </html>
    );
}
