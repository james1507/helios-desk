"use client";
import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  LayoutDashboard,
  Inbox,
  UserRound,
  Users,
  ShieldCheck,
  Settings,
  LogOut,
  Menu,
  ChevronDown,
  ArrowUpRight,
  LifeBuoy,
  Search,
  X,
  Sun,
  BarChart3,
  Clock3,
} from "lucide-react";
import { api, ApiError, clearCsrf, mutation } from "@/lib/api";
import type { Session, Page, Ticket } from "@/lib/types";
import { Avatar, ErrorBanner, Loading, Logo } from "./common";
import { Notifications } from "./notifications";
import { Reports, ServiceLevels } from "./operations";
import {
  TicketList,
  Overview,
  NewTicket,
  TicketDetail,
  Directory,
  AuditPage,
  Profile,
} from "./screens";
export function Workspace() {
  const path = usePathname(),
    router = useRouter(),
    cache = useQueryClient();
  const [mobile, setMobile] = useState(false);
  const [actionError, setActionError] = useState<unknown>();
  const sessionChannel = useRef<BroadcastChannel | null>(null);
  const session = useQuery({
    queryKey: ["session"],
    queryFn: () => api<Session>("/auth/me"),
    retry: false,
  });
  useEffect(() => {
    if (session.error instanceof ApiError && session.error.status === 401)
      router.replace("/login");
  }, [session.error, router]);
  useEffect(() => {
    const channel = new BroadcastChannel("helios-session");
    sessionChannel.current = channel;
    channel.onmessage = () => {
      cache.clear();
      window.location.reload();
    };
    return () => {
      sessionChannel.current = null;
      channel.close();
    };
  }, [cache]);
  function broadcastSessionChange() {
    sessionChannel.current?.postMessage("changed");
  }
  const actor = session.data?.actor;
  const customer = actor?.role === "REQUESTER";
  const manager =
    actor && ["OWNER", "ADMIN", "MANAGER", "AUDITOR"].includes(actor.role);
  const base = customer ? "/portal" : "/app";
  const counts = useQuery({
    queryKey: ["tenant", actor?.tenantId, "sidebar-count"],
    queryFn: () => api<Page<Ticket>>("/tickets?limit=1"),
    enabled: !!actor,
  });
  useEffect(() => {
    if (
      actor &&
      (path === "/" ||
        (customer && path.startsWith("/app/")) ||
        (!customer && path.startsWith("/portal/")))
    )
      router.replace(`${customer ? "/portal/tickets" : "/app/overview"}`);
  }, [actor, customer, path, router]);
  if (!actor)
    return session.isPending ? (
      <Loading />
    ) : (
      <main className="page">
        <ErrorBanner error={session.error} retry={() => session.refetch()} />
      </main>
    );
  async function logout() {
    try {
      await api("/auth/logout", mutation("POST"));
      broadcastSessionChange();
      clearCsrf();
      cache.clear();
      router.replace("/login");
    } catch (e) {
      setActionError(e);
    }
  }
  async function changeTenant(slug: string) {
    try {
      const next = await api<Session>(
        "/tenants/switch",
        mutation("POST", { slug }),
      );
      cache.clear();
      cache.setQueryData(["session"], next);
      broadcastSessionChange();
      window.location.assign(
        next.actor.role === "REQUESTER" ? "/portal/tickets" : "/app/overview",
      );
    } catch (e) {
      setActionError(e);
    }
  }
  const nav = [
    ...(!customer
      ? [{ href: "/app/overview", icon: LayoutDashboard, title: "Overview" }]
      : []),
    {
      href: `${base}/tickets`,
      icon: Inbox,
      title: customer ? "My tickets" : "All tickets",
    },
    ...(!customer
      ? [
          { href: "/app/my-tickets", icon: UserRound, title: "My tickets" },
          { href: "/app/team", icon: Users, title: "Team queue" },
          { href: "/app/reports", icon: BarChart3, title: "Reports" },
        ]
      : []),
  ];
  let screen: React.ReactNode;
  const detail = path.match(/^\/(?:app|portal)\/tickets\/([0-9a-f-]{36})$/);
  if (path.endsWith("/tickets/new"))
    screen =
      actor.role === "AUDITOR" ? (
        <div className="empty">
          <h1>Read-only access</h1>
          <p>Your auditor role cannot create tickets.</p>
        </div>
      ) : (
        <NewTicket session={session.data!} />
      );
  else if (detail)
    screen = (
      <TicketDetail
        key={`${actor.tenantId}:${detail[1]}`}
        id={detail[1]}
        session={session.data!}
      />
    );
  else if (path === "/app/overview")
    screen = <Overview session={session.data!} />;
  else if (
    path.endsWith("/tickets") ||
    path === "/app/my-tickets" ||
    path === "/app/team"
  )
    screen = (
      <TicketList
        key={path}
        session={session.data!}
        queue={
          path === "/app/my-tickets"
            ? "mine"
            : path === "/app/team"
              ? "team"
              : undefined
        }
      />
    );
  else if (path === "/app/reports")
    screen = <Reports session={session.data!} />;
  else if (path === "/app/service-levels")
    screen = <ServiceLevels session={session.data!} />;
  else if (path === "/app/directory")
    screen = <Directory session={session.data!} />;
  else if (path === "/app/audit")
    screen = <AuditPage session={session.data!} />;
  else if (path === `${base}/profile`)
    screen = <Profile session={session.data!} />;
  else
    screen = (
      <div className="empty">
        <h1>Page not found</h1>
        <Link href={`${base}/tickets`}>Return to tickets</Link>
      </div>
    );
  const active =
    nav.find((n) => path === n.href)?.title ??
    (path.endsWith("/new")
      ? "New ticket"
      : detail
        ? "Ticket details"
        : path.endsWith("/audit")
          ? "Audit logs"
          : path.endsWith("/directory")
            ? "Team directory"
            : "Profile");
  return (
    <div className="workspace">
      <a className="skip-link" href="#main-content">
        Skip to content
      </a>
      {mobile && (
        <button
          className="mobile-overlay"
          aria-label="Close navigation"
          onClick={() => setMobile(false)}
        />
      )}
      <aside className={`sidebar ${mobile ? "sidebar-open" : ""}`}>
        <div className="sidebar-brand">
          <Logo />
          <button
            className="mobile-close"
            aria-label="Close navigation"
            onClick={() => setMobile(false)}
          >
            <X size={20} />
          </button>
        </div>
        <div className="tenant-block">
          <span className="tenant-avatar">{actor.tenantName[0]}</span>
          <div>
            <strong>{actor.tenantName}</strong>
            <small>Support workspace</small>
          </div>
          {session.data!.memberships.length > 1 ? (
            <select
              aria-label="Switch workspace"
              value={actor.tenantSlug}
              onChange={(e) => changeTenant(e.target.value)}
            >
              {session.data!.memberships.map((m) => (
                <option key={m.id} value={m.slug}>
                  {m.name}
                </option>
              ))}
            </select>
          ) : (
            <ChevronDown size={15} />
          )}
        </div>
        <div className="nav-label">WORKSPACE</div>
        <nav aria-label="Main navigation">
          {nav.map((n) => (
            <Link
              key={n.href}
              href={n.href}
              onClick={() => setMobile(false)}
              className={`nav-item ${path === n.href ? "active" : ""}`}
            >
              <n.icon size={18} />
              <span>{n.title}</span>
              {n.title === (customer ? "My tickets" : "All tickets") && (
                <span className="nav-count">
                  {counts.data?.totalCount ?? "–"}
                </span>
              )}
            </Link>
          ))}
        </nav>
        {!customer && (
          <>
            <div className="nav-label administration">MANAGEMENT</div>
            <nav aria-label="Management">
              {manager && (
                <Link
                  className={`nav-item ${path === "/app/service-levels" ? "active" : ""}`}
                  href="/app/service-levels"
                >
                  <Clock3 size={18} />
                  Service levels
                </Link>
              )}
              <Link
                className={`nav-item ${path === "/app/directory" ? "active" : ""}`}
                href="/app/directory"
              >
                <Users size={18} />
                Team directory
              </Link>
              {manager && (
                <Link
                  className={`nav-item ${path === "/app/audit" ? "active" : ""}`}
                  href="/app/audit"
                >
                  <ShieldCheck size={18} />
                  Audit logs
                </Link>
              )}
              <Link
                className={`nav-item ${path.endsWith("/profile") ? "active" : ""}`}
                href={`${base}/profile`}
              >
                <Settings size={18} />
                Workspace profile
              </Link>
            </nav>
          </>
        )}
        <div className="sidebar-bottom">
          <div className="support-tip">
            <span className="tip-icon">
              <Sun size={19} />
            </span>
            <strong>A brighter support day</strong>
            <p>
              Clarity for your team.
              <br />
              Confidence for your customers.
            </p>
            <Link href={`${base}/tickets/new`}>
              Start a conversation
              <ArrowUpRight size={14} />
            </Link>
          </div>
          <div className="user-block">
            <Avatar name={actor.name} />
            <Link href={`${base}/profile`}>
              <strong>{actor.name}</strong>
              <small>{actor.role.toLowerCase()}</small>
            </Link>
            <button aria-label="Sign out" title="Sign out" onClick={logout}>
              <LogOut size={17} />
            </button>
          </div>
        </div>
      </aside>
      <div className="workspace-main">
        <header className="topbar">
          <div className="breadcrumbs">
            <button
              className="mobile-menu"
              aria-label="Open navigation"
              onClick={() => setMobile(true)}
            >
              <Menu size={20} />
            </button>
            <span>Workspace</span>
            <span className="slash">/</span>
            <strong>{active}</strong>
          </div>
          <div className="topbar-actions">
            <Notifications session={session.data!} />
            <Link className="topbar-search" href={`${base}/tickets`}>
              <Search size={16} />
              Search tickets
            </Link>
            <span className="topbar-divider" />
            <Avatar name={actor.name} size="small" />
          </div>
        </header>
        <main id="main-content" className="main-content">
          <ErrorBanner error={actionError} />
          {screen}
        </main>
        <footer className="workspace-footer">
          <span>
            <span className="system-dot" />
            Connected to your workspace
          </span>
          <span>
            <LifeBuoy size={13} />
            Helios Desk
          </span>
        </footer>
      </div>
    </div>
  );
}
