import type { Metadata } from 'next';
import { Geist, Geist_Mono } from 'next/font/google';

import { SiteHeader } from '@/app/_components/site-header';
import { Providers } from '@/app/_providers';
import { Toaster } from '@/components/ui/sonner';
import { publicSession } from '@/lib/auth/claims';
import { readSession } from '@/lib/auth/cookies';

import './globals.css';

const geistSans = Geist({ variable: '--font-geist-sans', subsets: ['latin'] });
const geistMono = Geist_Mono({
  variable: '--font-geist-mono',
  subsets: ['latin'],
});

export const metadata: Metadata = {
  title: 'axonposts · cliente de teste',
  description:
    'Cliente GraphQL para exercitar os fluxos do blog: saga coreografada, federação e identidade.',
};

export default async function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const session = publicSession(await readSession());

  return (
    <html lang="pt-BR" suppressHydrationWarning>
      <body
        className={`${geistSans.variable} ${geistMono.variable} antialiased`}
      >
        <Providers session={session}>
          <SiteHeader />
          <main className="mx-auto max-w-5xl px-4 py-8">{children}</main>
          <Toaster position="bottom-right" />
        </Providers>
      </body>
    </html>
  );
}
