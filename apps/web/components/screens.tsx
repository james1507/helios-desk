"use client";
import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import {
  createColumnHelper,
  flexRender,
  getCoreRowModel,
  useReactTable,
} from "@tanstack/react-table";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import {
  ArrowLeft,
  ArrowRight,
  ArrowUpRight,
  Check,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  Clock3,
  Inbox,
  LockKeyhole,
  Mail,
  MessageSquare,
  Plus,
  RefreshCw,
  Search,
  Send,
  ShieldCheck,
  SlidersHorizontal,
  Ticket as TicketIcon,
  UserRound,
} from "lucide-react";
import { api, ApiError, mutation } from "@/lib/api";
import type {
  Session,
  Page,
  Ticket,
  Message,
  TicketEvent,
  Member,
  Team,
  Audit,
} from "@/lib/types";
import { date, label } from "@/lib/utils";
import {
  Avatar,
  Empty,
  ErrorBanner,
  Loading,
  Priority,
  Status,
} from "./common";
import { Button } from "./ui/button";
import { Modal } from "./ui/dialog";
import { Teams } from "./teams";
const statuses = [
  "NEW",
  "OPEN",
  "PENDING_CUSTOMER",
  "ON_HOLD",
  "RESOLVED",
  "CLOSED",
];
const priorities = ["LOW", "NORMAL", "HIGH", "URGENT"];
const isCustomer = (s: Session) => s.actor.role === "REQUESTER";
const base = (s: Session) => (isCustomer(s) ? "/portal" : "/app");
const key = (s: Session, ...parts: unknown[]) => [
  "tenant",
  s.actor.tenantId,
  s.actor.membershipId,
  ...parts,
];
function Heading({
  eyebrow,
  title,
  description,
  children,
}: {
  eyebrow?: string;
  title: string;
  description: string;
  children?: React.ReactNode;
}) {
  return (
    <div className="page-heading">
      <div>
        {eyebrow && <div className="eyebrow">{eyebrow}</div>}
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      <div className="heading-actions">{children}</div>
    </div>
  );
}
function TicketTable({
  items,
  session,
}: {
  items: Ticket[];
  session: Session;
}) {
  const col = createColumnHelper<Ticket>();
  const columns = [
    col.accessor("subject", {
      header: "Ticket",
      cell: (info) => (
        <div className="ticket-cell">
          <Link href={`${base(session)}/tickets/${info.row.original.id}`}>
            <span className="ticket-key">{info.row.original.ticketKey}</span>
            <strong>{info.getValue()}</strong>
          </Link>
          <span>
            {info.row.original.requesterName}{" "}
            <span className="cell-dot">·</span>{" "}
            {label(info.row.original.source)}
          </span>
        </div>
      ),
    }),
    col.accessor("status", {
      header: "Status",
      cell: (info) => <Status value={info.getValue()} />,
    }),
    ...(!isCustomer(session)
      ? [
          col.accessor("priority", {
            header: "Priority",
            cell: (info) => <Priority value={info.getValue()} />,
          }),
          col.accessor("assigneeName", {
            header: "Assignee",
            cell: (info) =>
              info.getValue() ? (
                <span className="assignee-cell">
                  <Avatar name={info.getValue()!} size="small" />
                  {info.getValue()}
                </span>
              ) : (
                <span className="unassigned">
                  <UserRound size={14} />
                  Unassigned
                </span>
              ),
          }),
        ]
      : []),
    col.accessor("updatedAt", {
      header: "Updated",
      cell: (info) => (
        <span className="cell-time">
          {date(info.getValue(), session.actor.timezone)}
        </span>
      ),
    }),
  ];
  // TanStack Table owns mutable row models; React Compiler intentionally skips this hook.
  // eslint-disable-next-line react-hooks/incompatible-library
  const table = useReactTable({
    data: items,
    columns,
    getCoreRowModel: getCoreRowModel(),
  });
  return (
    <div className="table-scroll">
      <table>
        <thead>
          {table.getHeaderGroups().map((g) => (
            <tr key={g.id}>
              {g.headers.map((h) => (
                <th key={h.id} scope="col">
                  {flexRender(h.column.columnDef.header, h.getContext())}
                </th>
              ))}
            </tr>
          ))}
        </thead>
        <tbody>
          {table.getRowModel().rows.map((r) => (
            <tr key={r.id}>
              {r.getVisibleCells().map((c) => (
                <td key={c.id}>
                  {flexRender(c.column.columnDef.cell, c.getContext())}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
export function TicketList({
  session,
  queue,
}: {
  session: Session;
  queue?: string;
}) {
  const params = useSearchParams(),
    router = useRouter(),
    path = usePathname();
  const q = params.get("q") ?? "",
    status = params.get("status") ?? "",
    priority = params.get("priority") ?? "",
    cursor = params.get("cursor") ?? "";
  const [previous, setPrevious] = useState<string[]>([]);
  const queryString = new URLSearchParams({
    limit: "12",
    ...(q ? { q } : {}),
    ...(status ? { status } : {}),
    ...(priority ? { priority } : {}),
    ...(queue ? { queue } : {}),
    ...(cursor ? { cursor } : {}),
  }).toString();
  const tickets = useQuery({
    queryKey: key(session, "tickets", queryString),
    queryFn: () => api<Page<Ticket>>(`/tickets?${queryString}`),
  });
  function filter(name: string, value: string) {
    const next = new URLSearchParams(params.toString());
    if (value) next.set(name, value);
    else next.delete(name);
    next.delete("cursor");
    setPrevious([]);
    router.push(`${path}?${next}`);
  }
  function page(next: string, forward: boolean) {
    const p = new URLSearchParams(params.toString());
    if (next) p.set("cursor", next);
    else p.delete("cursor");
    if (forward) setPrevious([...previous, cursor]);
    else setPrevious(previous.slice(0, -1));
    router.push(`${path}?${p}`);
  }
  return (
    <>
      <Heading
        eyebrow="YOUR SUPPORT HUB"
        title={
          queue === "mine"
            ? "My tickets"
            : queue === "team"
              ? "Team queue"
              : isCustomer(session)
                ? "My tickets"
                : "All tickets"
        }
        description={
          isCustomer(session)
            ? "Your conversations with our support team, all in one place."
            : "Every conversation. Clear priorities. Nothing falls through."
        }
      >
        {session.actor.role !== "AUDITOR" && (
          <Link
            className="button button-primary"
            href={`${base(session)}/tickets/new`}
          >
            <Plus size={17} />
            New ticket
          </Link>
        )}
      </Heading>
      <section className="panel inbox-panel">
        <div className="tabs" aria-label="Filter by status">
          {[
            ["", "All tickets"],
            ["NEW", "New"],
            ["OPEN", "Open"],
            ["PENDING_CUSTOMER", "Waiting"],
            ["RESOLVED", "Resolved"],
          ].map(([value, text]) => (
            <button
              key={value}
              className={status === value ? "selected" : ""}
              onClick={() => filter("status", value)}
            >
              {text}
              {value === "" && <span>{tickets.data?.totalCount ?? "–"}</span>}
            </button>
          ))}
        </div>
        <div className="table-toolbar">
          <form
            className="search-field"
            onSubmit={(e) => {
              e.preventDefault();
              filter("q", new FormData(e.currentTarget).get("q") as string);
            }}
          >
            <Search size={17} />
            <input
              key={q}
              aria-label="Search tickets"
              name="q"
              defaultValue={q}
              placeholder="Search tickets, customers, or keywords…"
            />
            <button type="submit" aria-label="Submit search">
              <ArrowRight size={15} />
            </button>
          </form>
          <div className="table-filter">
            <SlidersHorizontal size={15} />
            <select
              aria-label="Filter priority"
              value={priority}
              onChange={(e) => filter("priority", e.target.value)}
            >
              <option value="">All priorities</option>
              {priorities.map((p) => (
                <option key={p} value={p}>
                  {label(p)}
                </option>
              ))}
            </select>
          </div>
          <Button
            size="icon"
            variant="ghost"
            aria-label="Refresh tickets"
            onClick={() => tickets.refetch()}
          >
            <RefreshCw size={16} />
          </Button>
        </div>
        <ErrorBanner error={tickets.error} retry={() => tickets.refetch()} />
        {tickets.isPending ? (
          <Loading />
        ) : tickets.data?.items.length ? (
          <TicketTable items={tickets.data.items} session={session} />
        ) : (
          <Empty />
        )}
        <div className="table-footer">
          <span>
            {tickets.data?.totalCount ?? 0} tickets
            <span className="footer-subtle"> · Sorted by latest activity</span>
          </span>
          <div>
            <Button
              variant="outline"
              size="sm"
              disabled={!cursor}
              onClick={() => page(previous.at(-1) ?? "", false)}
            >
              <ChevronLeft size={15} />
              Previous
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={!tickets.data?.nextCursor}
              onClick={() => page(tickets.data!.nextCursor!, true)}
            >
              Next
              <ChevronRight size={15} />
            </Button>
          </div>
        </div>
      </section>
      <div className="quiet-note">
        <ShieldCheck size={14} />
        {isCustomer(session)
          ? "Only you and your support team can see these conversations."
          : "Tickets shown according to your workspace permissions."}
      </div>
    </>
  );
}
export function Overview({ session }: { session: Session }) {
  const recent = useQuery({
    queryKey: key(session, "tickets", "recent"),
    queryFn: () => api<Page<Ticket>>("/tickets?limit=6"),
  });
  const counts = useQuery({
    queryKey: key(session, "overview-counts"),
    queryFn: async () => {
      const results = await Promise.all(
        statuses.map((status) =>
          api<Page<Ticket>>(`/tickets?status=${status}&limit=1`),
        ),
      );
      return Object.fromEntries(
        statuses.map((s, i) => [s, results[i].totalCount ?? 0]),
      ) as Record<string, number>;
    },
  });
  const unassigned = useQuery({
    queryKey: key(session, "unassigned-count"),
    queryFn: () => api<Page<Ticket>>("/tickets?queue=unassigned&limit=1"),
  });
  const c = counts.data ?? {};
  const open = (c.NEW ?? 0) + (c.OPEN ?? 0) + (c.ON_HOLD ?? 0);
  const total = Object.values(c).reduce((a, b) => a + b, 0);
  const done = (c.RESOLVED ?? 0) + (c.CLOSED ?? 0);
  return (
    <>
      <Heading
        eyebrow="WORKSPACE OVERVIEW"
        title={`A clearer day, ${session.actor.name.split(" ")[0]}.`}
        description="Here’s what’s happening across your support workspace."
      >
        <span className="date-chip">
          {new Intl.DateTimeFormat("en", {
            month: "short",
            day: "numeric",
            year: "numeric",
            timeZone: session.actor.timezone,
          }).format(new Date())}
        </span>
        <Link className="button button-primary" href="/app/tickets/new">
          <Plus size={17} />
          New ticket
        </Link>
      </Heading>
      <ErrorBanner error={counts.error ?? recent.error} />
      <div className="stats-grid">
        {[
          {
            title: "Active tickets",
            value: open,
            icon: Inbox,
            kind: "indigo",
            caption: "Ready for your team",
            href: "/app/tickets?status=OPEN",
          },
          {
            title: "Unassigned",
            value: unassigned.data?.totalCount ?? 0,
            icon: UserRound,
            kind: "amber",
            caption: "Looking for an owner",
            href: "/app/tickets",
          },
          {
            title: "Waiting on customer",
            value: c.PENDING_CUSTOMER ?? 0,
            icon: Clock3,
            kind: "blue",
            caption: "Conversation in progress",
            href: "/app/tickets?status=PENDING_CUSTOMER",
          },
          {
            title: "Resolved & closed",
            value: done,
            icon: CheckCircle2,
            kind: "green",
            caption: "Taken care of",
            href: "/app/tickets?status=RESOLVED",
          },
        ].map((stat) => (
          <Link href={stat.href} className="stat-card" key={stat.title}>
            <div>
              <span>{stat.title}</span>
              <span className={`stat-icon ${stat.kind}`}>
                <stat.icon size={19} />
              </span>
            </div>
            <strong>{counts.isPending ? "–" : stat.value}</strong>
            <small>
              {stat.caption}
              <ArrowUpRight size={14} />
            </small>
          </Link>
        ))}
      </div>
      <div className="overview-grid">
        <section className="panel activity-panel">
          <div className="panel-heading">
            <div>
              <h2>Support pulse</h2>
              <p>A snapshot of your current workload</p>
            </div>
            <span className="subtle-badge">{total} total tickets</span>
          </div>
          <div className="backlog-chart">
            {statuses.map((s) => (
              <div className="backlog-row" key={s}>
                <span>{label(s)}</span>
                <div>
                  <i
                    className={`bar-${s.toLowerCase()}`}
                    style={{
                      width: `${Math.max(2, ((c[s] ?? 0) / Math.max(1, total)) * 100)}%`,
                    }}
                  />
                </div>
                <strong>{c[s] ?? 0}</strong>
              </div>
            ))}
          </div>
          <div className="chart-caption">
            Counts include all tickets visible to your membership.
          </div>
        </section>
        <section className="focus-card">
          <div className="eyebrow light">ROOM TO MAKE A DIFFERENCE</div>
          <span className="focus-orb">
            <MessageSquare size={29} />
          </span>
          <h2>
            Behind every ticket,
            <br />
            there’s a person.
          </h2>
          <p>Start with the conversations that need a little more attention.</p>
          <Link className="button focus-button" href="/app/tickets?status=NEW">
            Explore new tickets
            <ArrowRight size={16} />
          </Link>
        </section>
      </div>
      <section className="panel">
        <div className="panel-heading">
          <div>
            <h2>Recent conversations</h2>
            <p>The latest activity from your workspace</p>
          </div>
          <Link className="text-link" href="/app/tickets">
            View all tickets
            <ArrowRight size={15} />
          </Link>
        </div>
        {recent.isPending ? (
          <Loading />
        ) : recent.data?.items.length ? (
          <TicketTable items={recent.data.items} session={session} />
        ) : (
          <Empty />
        )}
      </section>
    </>
  );
}
const ticketSchema = z.object({
  subject: z.string().trim().min(5, "Use at least 5 characters.").max(200),
  description: z.string().trim().min(1, "Tell us what happened.").max(20000),
  priority: z.enum(["LOW", "NORMAL", "HIGH", "URGENT"]),
  categoryId: z.string(),
  requesterMembershipId: z.string(),
});
type TicketForm = z.infer<typeof ticketSchema>;
export function NewTicket({ session }: { session: Session }) {
  const router = useRouter(),
    cache = useQueryClient(),
    idempotency = useRef<string | undefined>(undefined);
  const customer = isCustomer(session);
  const categories = useQuery({
    queryKey: key(session, "categories"),
    queryFn: () => api<Page<Team>>("/categories"),
  });
  const members = useQuery({
    queryKey: key(session, "members"),
    queryFn: () => api<Page<Member>>("/tenant/members"),
    enabled: !customer,
  });
  const {
    register,
    handleSubmit,
    formState: { errors, isDirty },
  } = useForm<TicketForm>({
    resolver: zodResolver(ticketSchema),
    defaultValues: {
      subject: "",
      description: "",
      priority: "NORMAL",
      categoryId: "",
      requesterMembershipId: "",
    },
  });
  const create = useMutation({
    mutationFn: (values: TicketForm) => {
      idempotency.current ??= crypto.randomUUID();
      return api<Ticket>(
        "/tickets",
        mutation(
          "POST",
          {
            subject: values.subject,
            description: values.description,
            priority: values.priority,
            categoryId: values.categoryId || null,
            source: customer ? "PORTAL" : "AGENT",
            requesterMembershipId: customer
              ? null
              : values.requesterMembershipId || null,
          },
          undefined,
          idempotency.current,
        ),
      );
    },
    onSuccess: async (t) => {
      await cache.invalidateQueries({
        queryKey: ["tenant", session.actor.tenantId],
      });
      router.push(`${base(session)}/tickets/${t.id}`);
    },
  });
  return (
    <>
      <Link className="back-link" href={`${base(session)}/tickets`}>
        <ArrowLeft size={15} />
        Back to tickets
      </Link>
      <Heading
        eyebrow="START A CONVERSATION"
        title="How can we help?"
        description="Give your support team the context they need to get you moving."
      />
      <div className="new-ticket-grid">
        <form
          className="panel ticket-form stack"
          onSubmit={handleSubmit((values) => create.mutate(values))}
          onChange={() => {
            if (
              create.error instanceof ApiError &&
              create.error.code === "IDEMPOTENCY_CONFLICT"
            )
              idempotency.current = undefined;
          }}
        >
          <div className="form-section-title">
            <TicketIcon size={20} />
            <h2>Ticket details</h2>
          </div>
          <label>
            Subject
            <input
              {...register("subject")}
              maxLength={200}
              placeholder="A short summary of what you need"
              aria-invalid={!!errors.subject}
            />
            {errors.subject && (
              <small className="field-error">{errors.subject.message}</small>
            )}
          </label>
          <label>
            Description
            <textarea
              aria-label="Description"
              aria-describedby="description-hint"
              {...register("description")}
              maxLength={20000}
              rows={8}
              placeholder="What happened? What did you expect? Include any details that might help."
              aria-invalid={!!errors.description}
            />
            {errors.description && (
              <small className="field-error">
                {errors.description.message}
              </small>
            )}
            <small id="description-hint" className="muted">
              Please keep passwords and other sensitive credentials out of your
              message.
            </small>
          </label>
          <div className="form-row">
            <label>
              Category
              <select {...register("categoryId")}>
                <option value="">General support</option>
                {categories.data?.items.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Priority
              <select {...register("priority")}>
                {(customer ? ["LOW", "NORMAL"] : priorities).map((p) => (
                  <option key={p} value={p}>
                    {label(p)}
                  </option>
                ))}
              </select>
            </label>
          </div>
          {!customer && (
            <label>
              Requester
              <select {...register("requesterMembershipId")}>
                <option value="">Myself</option>
                {members.data?.items
                  .filter((m) => m.role === "REQUESTER")
                  .map((m) => (
                    <option key={m.id} value={m.id}>
                      {m.name} · {m.email}
                    </option>
                  ))}
              </select>
            </label>
          )}
          <ErrorBanner
            error={create.error ?? categories.error ?? members.error}
          />
          <div className="form-actions">
            <Link
              className="button button-outline"
              href={`${base(session)}/tickets`}
              onClick={(e) => {
                if (isDirty && !window.confirm("Discard this unsent ticket?"))
                  e.preventDefault();
              }}
            >
              Cancel
            </Link>
            <Button type="submit" disabled={create.isPending}>
              {create.isPending ? "Creating ticket…" : "Create ticket"}
              <ArrowRight size={16} />
            </Button>
          </div>
        </form>
        <aside className="new-ticket-help">
          <span className="help-orb">
            <MessageSquare size={24} />
          </span>
          <h3>A little context goes a long way.</h3>
          <p>
            A clear subject and a few helpful details make it easier for your
            team to find the right answer.
          </p>
          <div>
            <Check size={15} />
            Describe what you’re trying to do
          </div>
          <div>
            <Check size={15} />
            Share steps to reproduce the issue
          </div>
          <div>
            <Check size={15} />
            Mention when it first happened
          </div>
          <small>
            <LockKeyhole size={13} />
            Your conversation stays private.
          </small>
        </aside>
      </div>
    </>
  );
}
export function TicketDetail({
  id,
  session,
}: {
  id: string;
  session: Session;
}) {
  const cache = useQueryClient(),
    customer = isCustomer(session),
    [body, setBody] = useState(""),
    [visibility, setVisibility] = useState("PUBLIC"),
    [tab, setTab] = useState("conversation"),
    [success, setSuccess] = useState(""),
    [resolve, setResolve] = useState(false),
    [reason, setReason] = useState(""),
    [assign, setAssign] = useState(false),
    [team, setTeam] = useState(""),
    [assignee, setAssignee] = useState("");
  const retry = useRef<
    | { body: string; visibility: string; key: string; clientMessageId: string }
    | undefined
  >(undefined);
  const [newActivity, setNewActivity] = useState(false);
  useEffect(() => {
    function changed(event: Event) {
      const n = (event as CustomEvent<{ ticketId: string; version: number }>)
        .detail;
      const current = cache.getQueryData<Ticket>(key(session, "ticket", id));
      if (n.ticketId === id && current && n.version > current.version)
        setNewActivity(true);
    }
    window.addEventListener("helios-ticket-updated", changed);
    return () => window.removeEventListener("helios-ticket-updated", changed);
  }, [cache, id, session]);
  const detail = useQuery({
    queryKey: key(session, "ticket", id),
    queryFn: () => api<Ticket>(`/tickets/${id}`),
    refetchOnReconnect: false,
    refetchOnWindowFocus: false,
  });
  const messages = useInfiniteQuery({
    queryKey: key(session, "messages", id),
    refetchOnReconnect: false,
    refetchOnWindowFocus: false,
    queryFn: ({ pageParam }) =>
      api<Page<Message>>(
        `/tickets/${id}/messages${pageParam ? `?cursor=${encodeURIComponent(pageParam)}` : ""}`,
      ),
    initialPageParam: "",
    getNextPageParam: (p) => p.nextCursor ?? undefined,
  });
  const timeline = useInfiniteQuery({
    queryKey: key(session, "timeline", id),
    queryFn: ({ pageParam }) =>
      api<Page<TicketEvent>>(
        `/tickets/${id}/timeline${pageParam ? `?cursor=${encodeURIComponent(pageParam)}` : ""}`,
      ),
    initialPageParam: "",
    getNextPageParam: (p) => p.nextCursor ?? undefined,
    enabled: tab === "activity",
  });
  const teams = useQuery({
    queryKey: key(session, "teams"),
    queryFn: () => api<Page<Team>>("/teams"),
    enabled: assign,
  });
  const members = useQuery({
    queryKey: key(session, "members"),
    queryFn: () => api<Page<Member>>("/tenant/members"),
    enabled: assign,
  });
  const action = useMutation({
    mutationFn: async (args: {
      path: string;
      data?: unknown;
      method?: string;
      key?: string;
    }) =>
      api<Ticket>(
        `/tickets/${id}${args.path}`,
        mutation(
          args.method ?? "POST",
          args.data,
          detail.data!.version,
          args.key,
        ),
      ),
    onSuccess: async (t, args) => {
      cache.setQueryData(key(session, "ticket", id), t);
      setNewActivity(false);
      await cache.invalidateQueries({
        queryKey: [
          "tenant",
          session.actor.tenantId,
          session.actor.membershipId,
          "messages",
          id,
        ],
      });
      await cache.invalidateQueries({ queryKey: key(session, "timeline", id) });
      await cache.invalidateQueries({ queryKey: key(session, "tickets") });
      if (args.path === "/messages") {
        setBody("");
        retry.current = undefined;
        setSuccess(
          visibility === "PRIVATE" ? "Internal note added." : "Reply sent.",
        );
      } else setSuccess("Ticket updated.");
      setResolve(false);
      setAssign(false);
      setReason("");
    },
  });
  if (detail.isPending) return <Loading />;
  if (detail.error || !detail.data)
    return <ErrorBanner error={detail.error} retry={() => detail.refetch()} />;
  const t = detail.data;
  const conflict =
    action.error instanceof ApiError && action.error.status === 409;
  function send(e: React.FormEvent) {
    e.preventDefault();
    if (!body.trim()) return;
    if (
      !retry.current ||
      retry.current.body !== body ||
      retry.current.visibility !== visibility
    )
      retry.current = {
        body,
        visibility,
        key: crypto.randomUUID(),
        clientMessageId: crypto.randomUUID(),
      };
    action.mutate({
      path: "/messages",
      data: {
        body,
        visibility,
        clientMessageId: retry.current.clientMessageId,
      },
      key: retry.current.key,
    });
  }
  const next: Record<string, string[]> = {
    NEW: ["OPEN", "RESOLVED"],
    OPEN: ["PENDING_CUSTOMER", "ON_HOLD", "RESOLVED"],
    PENDING_CUSTOMER: ["OPEN", "RESOLVED"],
    ON_HOLD: ["OPEN", "RESOLVED"],
    RESOLVED: ["OPEN", ...(t.canManage ? ["CLOSED"] : [])],
    CLOSED: [],
  };
  return (
    <>
      <div className="ticket-topline">
        <Link className="back-link" href={`${base(session)}/tickets`}>
          <ArrowLeft size={15} />
          All tickets
        </Link>
        <span className="ticket-key">{t.ticketKey}</span>
        <span className="version-label">Version {t.version}</span>
      </div>
      <div className="ticket-detail-heading">
        <div>
          <h1>{t.subject}</h1>
          <p>
            Opened by {t.requesterName} <span>·</span>{" "}
            {date(t.createdAt, session.actor.timezone, true)}
          </p>
        </div>
        <Status value={t.status} />
      </div>
      {newActivity && !conflict && (
        <div className="new-activity" role="status">
          <RefreshCw size={15} />
          New activity on this ticket. Your draft is preserved.
          <Button
            size="sm"
            variant="outline"
            onClick={async () => {
              await detail.refetch();
              await messages.refetch();
              setNewActivity(false);
              retry.current = undefined;
            }}
          >
            Load latest
          </Button>
        </div>
      )}
      <ErrorBanner error={action.error} />
      {conflict && (
        <div className="conflict-banner" role="alert">
          <strong>This ticket changed while you were editing.</strong>
          <span>Reload the latest version; your draft is preserved.</span>
          <Button
            variant="outline"
            onClick={async () => {
              await detail.refetch();
              await messages.refetch();
              action.reset();
              retry.current = undefined;
            }}
          >
            Reload latest version
          </Button>
        </div>
      )}
      {success && (
        <div className="success-banner" role="status">
          <CheckCircle2 size={16} />
          {success}
        </div>
      )}
      <div className="ticket-detail-grid">
        <section className="conversation-panel panel">
          <div className="tabs">
            <button
              className={tab === "conversation" ? "selected" : ""}
              onClick={() => setTab("conversation")}
            >
              <MessageSquare size={15} />
              Conversation
            </button>
            <button
              className={tab === "activity" ? "selected" : ""}
              onClick={() => setTab("activity")}
            >
              <Clock3 size={15} />
              Activity
            </button>
            <span className="conversation-private">
              <LockKeyhole size={12} />
              Secure conversation
            </span>
          </div>
          {tab === "conversation" ? (
            <div className="conversation">
              <ErrorBanner
                error={messages.error}
                retry={() => messages.refetch()}
              />
              {messages.isPending ? (
                <Loading />
              ) : (
                messages.data?.pages
                  .flatMap((p) => p.items)
                  .map((m) => (
                    <article
                      key={m.id}
                      className={`message ${m.visibility === "PRIVATE" ? "message-private" : ""}`}
                    >
                      <div className="message-head">
                        <Avatar name={m.authorName} />
                        <div>
                          <strong>{m.authorName}</strong>
                          <span>
                            {m.visibility === "PRIVATE" ? (
                              <>
                                <LockKeyhole size={11} />
                                Internal note
                              </>
                            ) : m.authorRole === "REQUESTER" ? (
                              "Customer"
                            ) : (
                              "Support team"
                            )}
                          </span>
                        </div>
                        <time>
                          {date(m.createdAt, session.actor.timezone, true)}
                        </time>
                      </div>
                      <div
                        className="message-body"
                        dangerouslySetInnerHTML={{ __html: m.renderedBody }}
                      />
                    </article>
                  ))
              )}
              {messages.hasNextPage && (
                <Button
                  variant="outline"
                  onClick={() => messages.fetchNextPage()}
                  disabled={messages.isFetchingNextPage}
                >
                  Load more messages
                </Button>
              )}
            </div>
          ) : (
            <div className="timeline">
              <ErrorBanner error={timeline.error} />
              {timeline.isPending ? (
                <Loading />
              ) : (
                timeline.data?.pages
                  .flatMap((p) => p.items)
                  .map((e) => (
                    <div className="timeline-item" key={e.id}>
                      <span />
                      <div>
                        <strong>
                          {label(e.eventType.replaceAll(".", "_"))}
                        </strong>
                        <p>
                          {e.actorName} ·{" "}
                          {date(e.createdAt, session.actor.timezone, true)}
                        </p>
                      </div>
                    </div>
                  ))
              )}
              {timeline.hasNextPage && (
                <Button
                  variant="outline"
                  onClick={() => timeline.fetchNextPage()}
                >
                  Load more activity
                </Button>
              )}
            </div>
          )}
          {t.canWrite && tab === "conversation" && (
            <form
              onSubmit={send}
              className={`composer ${visibility === "PRIVATE" ? "composer-private" : ""}`}
            >
              <div className="composer-head">
                {!customer ? (
                  <select
                    aria-label="Message visibility"
                    value={visibility}
                    onChange={(e) => setVisibility(e.target.value)}
                  >
                    <option value="PUBLIC">Public reply</option>
                    <option value="PRIVATE">Internal note</option>
                  </select>
                ) : (
                  <strong>
                    <Mail size={15} />
                    Reply to support
                  </strong>
                )}
                <span>
                  {visibility === "PRIVATE" ? (
                    <>
                      <LockKeyhole size={12} />
                      Only your team can see this
                    </>
                  ) : customer ? (
                    "Visible to you and support"
                  ) : (
                    "Customer will be notified"
                  )}
                </span>
              </div>
              <label className="sr-only" htmlFor="reply-body">
                {visibility === "PRIVATE" ? "Internal note" : "Your reply"}
              </label>
              <textarea
                id="reply-body"
                value={body}
                onChange={(e) => setBody(e.target.value)}
                placeholder={
                  visibility === "PRIVATE"
                    ? "Share context with your team…"
                    : "Write a thoughtful reply…"
                }
                rows={5}
                maxLength={20000}
                required
              />
              <div className="composer-footer">
                <span>Plain text · {body.length.toLocaleString()}/20,000</span>
                <Button
                  type="submit"
                  disabled={action.isPending || conflict || !body.trim()}
                >
                  {action.isPending
                    ? "Sending…"
                    : visibility === "PRIVATE"
                      ? "Add internal note"
                      : "Send reply"}
                  <Send size={14} />
                </Button>
              </div>
            </form>
          )}
          {t.status === "CLOSED" && (
            <div className="closed-notice">
              <LockKeyhole size={17} />
              <div>
                <strong>This conversation is closed.</strong>
                <p>
                  Need more help?{" "}
                  <Link href={`${base(session)}/tickets/new`}>
                    Create a new ticket
                  </Link>
                  .
                </p>
              </div>
            </div>
          )}
        </section>
        <aside className="ticket-sidebar">
          <section className="panel details-card">
            <h2>Ticket details</h2>
            <div className="detail-field">
              <span>Status</span>
              <Status value={t.status} />
            </div>
            {!customer && (
              <>
                <div className="detail-field">
                  <label htmlFor="ticket-priority">Priority</label>
                  {t.canWrite ? (
                    <select
                      id="ticket-priority"
                      value={t.priority}
                      disabled={action.isPending}
                      onChange={(e) =>
                        action.mutate({
                          path: "",
                          method: "PATCH",
                          data: { priority: e.target.value },
                        })
                      }
                    >
                      {priorities.map((p) => (
                        <option key={p} value={p}>
                          {label(p)}
                        </option>
                      ))}
                    </select>
                  ) : (
                    <Priority value={t.priority} />
                  )}
                </div>
                <div className="detail-field">
                  <span>Assignee</span>
                  <div className="detail-value">
                    {t.assigneeName ? (
                      <>
                        <Avatar size="small" name={t.assigneeName} />
                        {t.assigneeName}
                      </>
                    ) : (
                      <span className="muted">Unassigned</span>
                    )}
                  </div>
                </div>
                {!t.assigneeId && t.canWrite && (
                  <Button
                    variant="outline"
                    disabled={action.isPending}
                    onClick={() =>
                      action.mutate({
                        path: "/claim",
                        key: crypto.randomUUID(),
                      })
                    }
                  >
                    <UserRound size={15} />
                    Assign to me
                  </Button>
                )}
                <div className="detail-field">
                  <span>Team</span>
                  <strong>{t.teamName ?? "General queue"}</strong>
                </div>
                {t.canManage && t.canWrite && (
                  <Button
                    variant="ghost"
                    onClick={() => {
                      setTeam(t.teamId ?? "");
                      setAssignee(t.assigneeId ?? "");
                      setAssign(true);
                    }}
                  >
                    Manage assignment
                    <ArrowUpRight size={14} />
                  </Button>
                )}
              </>
            )}
            <div className="detail-field">
              <span>Channel</span>
              <strong>
                <Mail size={14} />
                {label(t.source)}
              </strong>
            </div>
          </section>
          <section className="panel requester-card">
            <h2>Requester</h2>
            <div className="requester-person">
              <Avatar name={t.requesterName} />
              <div>
                <strong>{t.requesterName}</strong>
                <small>{t.requesterEmail}</small>
              </div>
            </div>
            <div className="requester-footer">
              <ShieldCheck size={13} />
              Workspace member
            </div>
          </section>
          {!customer && t.resolutionDueAt && (
            <section className="panel sla-card">
              <h2>
                <Clock3 size={16} />
                Service level
              </h2>
              <div>
                <span>First response due</span>
                <strong>
                  {t.firstResponseDueAt
                    ? date(t.firstResponseDueAt, session.actor.timezone, true)
                    : "Original cycle preserved"}
                </strong>
              </div>
              <div>
                <span>Resolution due</span>
                <strong>
                  {date(t.resolutionDueAt, session.actor.timezone, true)}
                </strong>
              </div>
              <small>{session.actor.timezone} · Business hours</small>
            </section>
          )}
          {!customer && t.canWrite && (
            <section className="panel workflow-card">
              <h2>Move conversation forward</h2>
              {next[t.status]?.map((status) => (
                <Button
                  variant={status === "RESOLVED" ? "default" : "outline"}
                  disabled={action.isPending}
                  key={status}
                  onClick={() => {
                    if (["RESOLVED", "ON_HOLD"].includes(status)) {
                      setResolve(true);
                      setReason("");
                    } else
                      action.mutate({
                        path: "/transition",
                        data: { toStatus: status },
                      });
                  }}
                >
                  {status === "RESOLVED" ? (
                    <CheckCircle2 size={16} />
                  ) : (
                    <ArrowRight size={15} />
                  )}{" "}
                  {status === "RESOLVED"
                    ? "Resolve ticket"
                    : `Move to ${label(status).toLowerCase()}`}
                </Button>
              ))}
            </section>
          )}
        </aside>
      </div>
      <Modal
        title="Resolve or hold this ticket"
        description="Resolution text is shared with the customer. Hold reasons stay within your team."
        open={resolve}
        onOpenChange={setResolve}
      >
        <form
          className="stack"
          onSubmit={(e) => {
            e.preventDefault();
            const toStatus = new FormData(e.currentTarget).get("toStatus");
            action.mutate({ path: "/transition", data: { toStatus, reason } });
          }}
        >
          <label>
            Action
            <select name="toStatus">
              <option value="RESOLVED">Resolve ticket</option>
              {t.status === "OPEN" && (
                <option value="ON_HOLD">Put on hold</option>
              )}
            </select>
          </label>
          <label>
            Reason / resolution message
            <textarea
              rows={4}
              required
              maxLength={2000}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          </label>
          <ErrorBanner error={action.error} />
          <Button disabled={action.isPending}>Confirm change</Button>
        </form>
      </Modal>
      <Modal
        title="Manage assignment"
        description="Assign a workspace member and an eligible team."
        open={assign}
        onOpenChange={setAssign}
      >
        <form
          className="stack"
          onSubmit={(e) => {
            e.preventDefault();
            action.mutate({
              path: "/assign",
              data: { teamId: team || null, assigneeId: assignee || null },
            });
          }}
        >
          <label>
            Team
            <select value={team} onChange={(e) => setTeam(e.target.value)}>
              <option value="">General queue</option>
              {teams.data?.items.map((t) => (
                <option value={t.id} key={t.id}>
                  {t.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            Assignee
            <select
              value={assignee}
              onChange={(e) => setAssignee(e.target.value)}
            >
              <option value="">Unassigned</option>
              {members.data?.items
                .filter((m) => !["REQUESTER", "AUDITOR"].includes(m.role))
                .map((m) => (
                  <option key={m.id} value={m.id}>
                    {m.name}
                  </option>
                ))}
            </select>
          </label>
          <ErrorBanner error={action.error ?? teams.error ?? members.error} />
          <Button disabled={action.isPending}>Save assignment</Button>
        </form>
      </Modal>
    </>
  );
}
export function Directory({ session }: { session: Session }) {
  const members = useQuery({
    queryKey: key(session, "members"),
    queryFn: () => api<Page<Member>>("/tenant/members"),
  });
  return (
    <>
      <Heading
        eyebrow="PEOPLE & OWNERSHIP"
        title="Team directory"
        description="The people behind every thoughtful conversation."
      />
      <Teams session={session} />
      <ErrorBanner error={members.error} />
      <div className="directory-grid">
        {members.isPending ? (
          <Loading />
        ) : (
          members.data?.items
            .filter((m) => m.role !== "REQUESTER")
            .map((m) => (
              <article className="panel person-card" key={m.id}>
                <Avatar name={m.name} />
                <h2>{m.name}</h2>
                <span className="subtle-badge">{label(m.role)}</span>
                <p>{m.email}</p>
              </article>
            ))
        )}
      </div>
    </>
  );
}
export function AuditPage({ session }: { session: Session }) {
  const audit = useQuery({
    queryKey: key(session, "audit"),
    queryFn: () => api<Page<Audit>>("/audit"),
  });
  return (
    <>
      <Heading
        eyebrow="WORKSPACE ACCOUNTABILITY"
        title="Audit logs"
        description="An append-only record of changes across your workspace."
      />
      <section className="panel">
        <ErrorBanner error={audit.error} />
        {audit.isPending ? (
          <Loading />
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Event</th>
                  <th>Actor</th>
                  <th>Resource</th>
                  <th>Time</th>
                </tr>
              </thead>
              <tbody>
                {audit.data?.items.map((a) => (
                  <tr key={a.id}>
                    <td>
                      <strong>{label(a.action.replaceAll(".", "_"))}</strong>
                      <small className="audit-reference">
                        {a.correlationId}
                      </small>
                    </td>
                    <td>{a.actorName}</td>
                    <td>
                      <Link
                        className="text-link"
                        href={`/app/tickets/${a.resourceId}`}
                      >
                        View ticket
                        <ArrowUpRight size={12} />
                      </Link>
                    </td>
                    <td className="cell-time">
                      {date(a.createdAt, session.actor.timezone, true)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="table-footer">
          Latest 100 events · Message content and credentials are excluded.
        </div>
      </section>
    </>
  );
}
export function Profile({ session }: { session: Session }) {
  const a = session.actor;
  return (
    <>
      <Heading
        eyebrow="YOUR WORKSPACE"
        title="Profile & access"
        description="Your identity and current membership."
      />
      <section className="panel profile-card">
        <Avatar name={a.name} />
        <h2>{a.name}</h2>
        <p className="muted">{a.email}</p>
        <dl>
          {[
            ["Workspace", a.tenantName],
            ["Role", label(a.role)],
            ["Timezone", a.timezone],
            ["Authentication", "Secure cookie-backed session"],
          ].map(([k, v]) => (
            <div key={k}>
              <dt>{k}</dt>
              <dd>{v}</dd>
            </div>
          ))}
        </dl>
        <div className="quiet-note">
          <ShieldCheck size={15} />
          Permissions are checked by the server on every request.
        </div>
      </section>
    </>
  );
}
