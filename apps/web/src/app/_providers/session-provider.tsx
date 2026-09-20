'use client';

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';

import type { Session } from '@/lib/auth/claims';
import { refreshSession } from '@/app/actions/auth';
import { isAuthor as hasAuthorGroup } from '@/lib/auth/claims';

interface SessionContextValue {
  session: Session | null;
  isAuthor: boolean;
  renew: () => Promise<Session | null>;
}

const SessionContext = createContext<SessionContextValue>({
  session: null,
  isAuthor: false,
  renew: async () => null,
});

export function SessionProvider({
  initialSession,
  children,
}: {
  initialSession: Session | null;
  children: React.ReactNode;
}) {
  const [session, setSession] = useState<Session | null>(initialSession);

  const renew = useCallback(async () => {
    const renewed = await refreshSession();
    setSession(renewed);
    return renewed;
  }, []);

  useEffect(() => {
    if (!session) return;
    const delay = Math.max(session.expiresAt - Date.now() - 60_000, 0);
    const timer = setTimeout(() => void renew(), delay);
    return () => clearTimeout(timer);
  }, [session, renew]);

  const value = useMemo(
    () => ({ session, isAuthor: hasAuthorGroup(session), renew }),
    [session, renew],
  );

  return (
    <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
  );
}

export function useSession(): SessionContextValue {
  return useContext(SessionContext);
}
