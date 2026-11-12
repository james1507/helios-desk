"use client";
import { useEffect, useState } from "react";
import Link from "next/link";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Bell, Check, RefreshCw } from "lucide-react";
import { api, mutation } from "@/lib/api";
import type { Page, Session } from "@/lib/types";
import type { components } from "@/lib/schema";
import { date, label } from "@/lib/utils";
import { Button } from "./ui/button";
import { Modal } from "./ui/dialog";
import { Empty, ErrorBanner, Loading } from "./common";
export type Notice = components["schemas"]["Notice"];
export function Notifications({ session }: { session: Session }) {
  const cache = useQueryClient(),
    a = session.actor,
    [open, setOpen] = useState(false),
    [live, setLive] = useState(false),
    [error, setError] = useState<unknown>();
  const root = ["tenant", a.tenantId, a.membershipId, "notifications"];
  const count = useQuery({
    queryKey: [...root, "count"],
    queryFn: () => api<{ count: number }>("/notifications/unread-count"),
    refetchInterval: live ? false : 15000,
  });
  const inbox = useQuery({
    queryKey: root,
    queryFn: () => api<Page<Notice>>("/notifications?limit=50"),
    enabled: open,
    refetchInterval: open ? 15000 : false,
  });
  useEffect(() => {
    const source = new EventSource("/api/v1/events/stream");
    let refresh: ReturnType<typeof setTimeout> | undefined;
    source.onopen = () => setLive(true);
    source.onerror = () => setLive(false);
    source.addEventListener("ticket.updated", (event: MessageEvent<string>) => {
      const notice = JSON.parse(event.data) as Notice;
      if (!refresh)
        refresh = setTimeout(() => {
          refresh = undefined;
          void cache.invalidateQueries({
            queryKey: ["tenant", a.tenantId, a.membershipId, "notifications"],
          });
          void cache.invalidateQueries({
            predicate: (q) =>
              q.queryKey[0] === "tenant" &&
              q.queryKey[1] === a.tenantId &&
              [
                "tickets",
                "overview-counts",
                "unassigned-count",
                "sidebar-count",
                "reports",
              ].some((part) => q.queryKey.includes(part)),
          });
        }, 150);
      window.dispatchEvent(
        new CustomEvent("helios-ticket-updated", { detail: notice }),
      );
    });
    return () => {
      source.close();
      clearTimeout(refresh);
    };
  }, [a.tenantId, a.membershipId, cache]);
  async function read(n: Notice) {
    try {
      await api(`/notifications/${n.id}/read`, mutation("PATCH"));
      await cache.invalidateQueries({ queryKey: root });
    } catch (e) {
      setError(e);
    }
  }
  return (
    <>
      <button
        className="notification-bell"
        aria-label={`Notifications, ${count.data?.count ?? 0} unread`}
        onClick={() => setOpen(true)}
      >
        <Bell size={18} />
        {!!count.data?.count && <span>{Math.min(99, count.data.count)}</span>}
      </button>
      <Modal
        title="Your notifications"
        description={`${live ? "Live updates connected." : "Updates refresh every 15 seconds."} Showing latest 50 workspace events.`}
        open={open}
        onOpenChange={setOpen}
      >
        <ErrorBanner error={error ?? inbox.error} />
        <div className="notification-list">
          {inbox.isPending ? (
            <Loading />
          ) : inbox.data?.items.length ? (
            [...inbox.data.items].reverse().map((n) => (
              <article
                key={n.id}
                className={`notification-item ${n.readAt ? "" : "unread"}`}
              >
                <Link
                  href={`${a.role === "REQUESTER" ? "/portal" : "/app"}/tickets/${n.ticketId}`}
                  onClick={() => {
                    read(n);
                    setOpen(false);
                  }}
                >
                  <strong>{label(n.type.replaceAll(".", "_"))}</strong>
                  <small>{date(n.createdAt, a.timezone, true)}</small>
                </Link>
                {!n.readAt && (
                  <Button
                    variant="ghost"
                    size="icon"
                    aria-label="Mark notification read"
                    onClick={() => read(n)}
                  >
                    <Check size={16} />
                  </Button>
                )}
              </article>
            ))
          ) : (
            <Empty
              title="You're up to date"
              description="New activity will appear here."
            />
          )}
        </div>
        <Button variant="outline" onClick={() => inbox.refetch()}>
          <RefreshCw size={14} />
          Refresh
        </Button>
      </Modal>
    </>
  );
}
