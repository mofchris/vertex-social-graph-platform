// Browser client for the Vertex gateway. Every call goes to same-origin /api/*: the Cloudflare
// Worker forwards it to the gateway in production, `next dev` rewrites it locally.

export type Tokens = { accessToken: string; refreshToken: string };
export type Account = Tokens & {
  userId: string;
  email: string;
  username: string;
  displayName: string;
  password: string;
};

/**
 * A response plus what we know about how long it took: `ms` is the round trip on the visitor's
 * clock; `gatewayMs` and `upstreamMs` come from the gateway's Server-Timing header.
 */
export type Timed<T> = {
  data: T;
  status: number;
  ms: number;
  gatewayMs: number | null;
  upstreamMs: number | null;
  remaining: number | null;
  at: number;
};

function serverTiming(header: string | null): { gateway: number | null; upstream: number | null } {
  const pick = (name: string) => {
    const m = header?.match(new RegExp(`${name}(?:;[^,]*?)?;dur=([\\d.]+)`));
    return m ? Number(m[1]) : null;
  };
  return { gateway: pick("gateway"), upstream: pick("upstream") };
}

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    public retryAfter: number | null = null,
  ) {
    super(message);
  }
}

type Init = { method?: string; body?: unknown; account?: Account | null };

let onTokens: ((a: Account) => void) | null = null;
/** Called whenever an account's tokens were refreshed, so the caller can persist them. */
export const setTokenListener = (fn: (a: Account) => void) => {
  onTokens = fn;
};

async function raw(path: string, init: Init, token?: string): Promise<Response> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (init.body !== undefined) headers["Content-Type"] = "application/json";
  if (token) headers.Authorization = `Bearer ${token}`;
  try {
    return await fetch(`/api${path}`, {
      method: init.method ?? "GET",
      headers,
      body: init.body !== undefined ? JSON.stringify(init.body) : undefined,
      cache: "no-store",
    });
  } catch {
    throw new ApiError(0, "Can't reach Vertex. Check your connection.");
  }
}

async function refresh(account: Account): Promise<boolean> {
  const res = await raw("/identity/v1/auth/refresh", { method: "POST", body: { refreshToken: account.refreshToken } });
  if (!res.ok) return false;
  const body = (await res.json()) as Tokens;
  account.accessToken = body.accessToken;
  account.refreshToken = body.refreshToken;
  onTokens?.(account);
  return true;
}

/** Call the gateway, timing the round trip and reading the rate-limit budget it reports. */
export async function api<T>(path: string, init: Init = {}): Promise<Timed<T>> {
  const at = Date.now();
  const start = performance.now();
  let res = await raw(path, init, init.account?.accessToken);
  if (res.status === 401 && init.account && (await refresh(init.account))) {
    res = await raw(path, init, init.account.accessToken);
  }
  const ms = Math.round(performance.now() - start);
  const remainingHeader = res.headers.get("X-RateLimit-Remaining");
  const remaining = remainingHeader === null ? null : Number(remainingHeader);

  if (!res.ok) {
    const retry = res.headers.get("Retry-After");
    let message = `Request failed (${res.status})`;
    try {
      const body = await res.json();
      message = body.message || body.error || message;
    } catch {
      /* no JSON body */
    }
    if (res.status === 429) message = `Slow down: the gateway's rate limit kicked in. Try again in ${retry ?? "a few"} s.`;
    if (res.status === 502 || res.status === 503) message = "That service is not answering right now.";
    throw new ApiError(res.status, message, retry ? Number(retry) : null);
  }
  const text = await res.text();
  const data = (text ? JSON.parse(text) : null) as T;
  const timing = serverTiming(res.headers.get("Server-Timing"));
  return { data, status: res.status, ms, gatewayMs: timing.gateway, upstreamMs: timing.upstream, remaining, at };
}

// ---------- types the services return ----------

export type ServiceStatus = { name: string; status: string; latencyMs: number };
export type PlatformStatus = { status: "UP" | "DEGRADED" | "DOWN"; checkedAt: string; services: ServiceStatus[] };
export type Profile = { userId: string; displayName: string | null; bio: string | null; visibility: string };
export type Post = { id: string; authorId: string; content: string; createdAt: string };
export type Notification = {
  id: string;
  type: string;
  targetId: string | null;
  actorCount: number;
  latestActorId: string;
  read: boolean;
  updatedAt: string;
};
export type Suggestion = { userId: string; mutualFriends: number; reason: "MUTUAL_FRIENDS" | "POPULAR" };
export type Relationship = { userId: string; following: boolean; followedBy: boolean; friendStatus: string };
export type OutboxRow = {
  outboxId: string;
  eventId: string | null;
  type: string | null;
  kind: "EVENT" | "REPLAY" | "POISON";
  actorId: string;
  recipientId: string | null;
  topic: string;
  createdAt: string;
  publishedAt: string | null;
};
export type Delivery = {
  eventId: string;
  type: string;
  processedAt: string;
  partition: number;
  offset: number;
  duplicatesIgnored: number;
  lastDuplicateAt: string | null;
};
export type DeadLetter = {
  id: string;
  error: string;
  payloadPreview: string;
  originalTopic: string;
  originalPartition: number;
  originalOffset: number;
  receivedAt: string;
};
export type PipelineStats = { processedEvents: number; duplicatesIgnored: number; deadLetters: number };

type AuthBody = Tokens & { user: { id: string; email: string; username: string; displayName: string } };

// ---------- endpoints ----------

export const status = () => api<PlatformStatus>("/status");

export async function signUp(email: string, username: string, password: string, displayName: string): Promise<Account> {
  const { data } = await api<AuthBody>("/identity/v1/auth/signup", {
    method: "POST",
    body: { email, username, password, displayName },
  });
  return toAccount(data, password);
}

export async function signIn(email: string, password: string): Promise<Account> {
  const { data } = await api<AuthBody>("/identity/v1/auth/login", { method: "POST", body: { email, password } });
  return toAccount(data, password);
}

function toAccount(b: AuthBody, password: string): Account {
  return {
    accessToken: b.accessToken,
    refreshToken: b.refreshToken,
    userId: b.user.id,
    email: b.user.email,
    username: b.user.username,
    displayName: b.user.displayName,
    password,
  };
}

export const putProfile = (account: Account, displayName: string) =>
  api<Profile>("/profile/v1/me/profile", { method: "PUT", account, body: { displayName, visibility: "PUBLIC" } });

export const profiles = (account: Account, ids: string[]) =>
  api<{ items: Profile[] }>(`/profile/v1/profiles?ids=${ids.join(",")}`, { account });

export const follow = (account: Account, userId: string) =>
  api<null>(`/graph/v1/follow/${userId}`, { method: "POST", account });
export const unfollow = (account: Account, userId: string) =>
  api<null>(`/graph/v1/follow/${userId}`, { method: "DELETE", account });
export const counts = (account: Account, userId: string) =>
  api<{ userId: string; followers: number; following: number; friends: number }>(`/graph/v1/counts/${userId}`, { account });
export const relationship = (account: Account, userId: string) =>
  api<Relationship>(`/graph/v1/relationship/${userId}`, { account });
export const popular = (account: Account, limit = 12) =>
  api<{ items: { userId: string; followers: number }[] }>(`/graph/v1/popular?limit=${limit}`, { account });
export const myEvents = (account: Account, limit = 10) =>
  api<{ items: OutboxRow[] }>(`/graph/v1/events/mine?limit=${limit}`, { account });
export const replay = (account: Account, eventId: string) =>
  api<OutboxRow>(`/graph/v1/events/${eventId}/replay`, { method: "POST", account });
export const poison = (account: Account) => api<OutboxRow>("/graph/v1/events/poison", { method: "POST", account });

export const createPost = (account: Account, content: string) =>
  api<Post>("/feed/v1/posts", { method: "POST", account, body: { content } });
export const feed = (account: Account, limit = 20) => api<{ items: Post[] }>(`/feed/v1/feed?limit=${limit}`, { account });

export const notifications = (account: Account, limit = 20) =>
  api<{ items: Notification[] }>(`/notify/v1/notifications?limit=${limit}`, { account });
export const delivery = (account: Account, eventId: string) =>
  api<Delivery>(`/notify/v1/pipeline/events/${eventId}`, { account });
export const deadLetters = (account: Account, limit = 5) =>
  api<{ items: DeadLetter[] }>(`/notify/v1/pipeline/dead-letters/mine?limit=${limit}`, { account });
export const pipelineStats = (account: Account) => api<PipelineStats>("/notify/v1/pipeline/stats", { account });

export const suggestions = (account: Account, limit = 6) =>
  api<{ items: Suggestion[] }>(`/recommend/v1/recommendations?limit=${limit}`, { account });

/**
 * Open the caller's live notification stream (Server-Sent Events). EventSource can't send an
 * Authorization header, so this reads the stream with fetch. Reconnects with backoff until the
 * returned function is called.
 */
export function streamNotifications(
  account: Account,
  handlers: { onOpen?: () => void; onNotification: (n: Notification, at: number) => void; onDrop?: () => void },
): () => void {
  let stopped = false;
  let controller: AbortController | null = null;
  let attempt = 0;

  const connect = async () => {
    while (!stopped) {
      controller = new AbortController();
      try {
        const res = await fetch("/api/notify/v1/notifications/stream", {
          headers: { Accept: "text/event-stream", Authorization: `Bearer ${account.accessToken}` },
          signal: controller.signal,
          cache: "no-store",
        });
        if (res.status === 401 && (await refresh(account))) continue;
        if (!res.ok || !res.body) throw new Error(String(res.status));
        attempt = 0;
        handlers.onOpen?.();
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          let cut: number;
          while ((cut = buffer.indexOf("\n\n")) >= 0) {
            const block = buffer.slice(0, cut);
            buffer = buffer.slice(cut + 2);
            let event = "message";
            const data: string[] = [];
            for (const line of block.split("\n")) {
              if (line.startsWith("event:")) event = line.slice(6).trim();
              else if (line.startsWith("data:")) data.push(line.slice(5));
            }
            if (event === "notification" && data.length) {
              try {
                handlers.onNotification(JSON.parse(data.join("\n")) as Notification, Date.now());
              } catch {
                /* ignore a malformed frame */
              }
            }
          }
        }
      } catch {
        if (stopped) return;
      }
      if (stopped) return;
      handlers.onDrop?.();
      attempt++;
      await new Promise((r) => setTimeout(r, Math.min(15000, 1000 * 2 ** Math.min(attempt, 4))));
    }
  };
  connect();
  return () => {
    stopped = true;
    controller?.abort();
  };
}
