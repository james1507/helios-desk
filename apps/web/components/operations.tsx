"use client";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  BarChart,
  Bar,
  CartesianGrid,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
} from "recharts";
import { CalendarDays, CheckCircle2, Plus, Save, Trash2 } from "lucide-react";
import { api, mutation } from "@/lib/api";
import type { Page, Session } from "@/lib/types";
import type { components } from "@/lib/schema";
import { label } from "@/lib/utils";
import { ErrorBanner, Loading, Empty } from "./common";
import { Button } from "./ui/button";
type Report = components["schemas"]["Overview"];
type Calendar = components["schemas"]["BusinessCalendar"];
type Policy = components["schemas"]["PolicyView"];
export function Reports({ session }: { session: Session }) {
  const [days, setDays] = useState("30");
  const report = useQuery({
    queryKey: [
      "tenant",
      session.actor.tenantId,
      session.actor.membershipId,
      "reports",
      days,
    ],
    queryFn: () => {
      const to = new Date(),
        from = new Date(to.getTime() - Number(days) * 86400000);
      return api<Report>(
        `/reports/overview?from=${from.toISOString()}&to=${to.toISOString()}`,
      );
    },
  });
  const r = report.data;
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">SUPPORT PERFORMANCE</div>
          <h1>A clearer picture.</h1>
          <p>Persisted outcomes. Useful context for your next decision.</p>
        </div>
        <label className="report-range">
          <span className="sr-only">Report date range</span>
          <select value={days} onChange={(e) => setDays(e.target.value)}>
            <option value="7">Last 7 days</option>
            <option value="30">Last 30 days</option>
            <option value="90">Last 90 days</option>
          </select>
        </label>
      </div>
      <ErrorBanner error={report.error} retry={() => report.refetch()} />
      {report.isPending ? (
        <Loading />
      ) : (
        r && (
          <>
            <div className="stats-grid report-stats">
              {r.compliance.map((c) => (
                <article key={c.metric} className="stat-card">
                  <div>
                    {label(c.metric)} compliance
                    <span className="stat-icon green">
                      <CheckCircle2 size={18} />
                    </span>
                  </div>
                  <strong>
                    {c.eligible
                      ? `${((c.met / c.eligible) * 100).toFixed(1)}%`
                      : "—"}
                  </strong>
                  <small>
                    {c.met} met / {c.eligible} eligible cycles
                  </small>
                </article>
              ))}
              <article className="stat-card">
                <div>Created in period</div>
                <strong>{r.trend.reduce((n, t) => n + t.created, 0)}</strong>
                <small>Tickets · UTC date boundaries</small>
              </article>
              <article className="stat-card">
                <div>Resolutions in period</div>
                <strong>{r.trend.reduce((n, t) => n + t.resolved, 0)}</strong>
                <small>Resolution cycles, including reopens</small>
              </article>
            </div>
            <section className="panel">
              <div className="panel-heading">
                <div>
                  <h2>Conversation trends</h2>
                  <p>Created tickets and completed resolution cycles · UTC</p>
                </div>
              </div>
              {r.trend.length ? (
                <div
                  className="report-chart"
                  role="img"
                  aria-label={`Created ${r.trend.reduce((n, t) => n + t.created, 0)} tickets and completed ${r.trend.reduce((n, t) => n + t.resolved, 0)} resolution cycles in this period.`}
                >
                  <ResponsiveContainer width="100%" height={240}>
                    <BarChart data={r.trend}>
                      <CartesianGrid
                        strokeDasharray="3 3"
                        vertical={false}
                        stroke="#eef0f6"
                      />
                      <XAxis
                        dataKey="day"
                        tick={{ fontSize: 10, fill: "#626c82" }}
                      />
                      <YAxis
                        allowDecimals={false}
                        tick={{ fontSize: 10, fill: "#626c82" }}
                      />
                      <Tooltip />
                      <Bar
                        dataKey="created"
                        isAnimationActive={false}
                        name="Created tickets"
                        fill="#7771d1"
                        radius={[4, 4, 0, 0]}
                      />
                      <Bar
                        dataKey="resolved"
                        isAnimationActive={false}
                        name="Resolved cycles"
                        fill="#7eaa98"
                        radius={[4, 4, 0, 0]}
                      />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              ) : (
                <Empty
                  title="No outcomes in this period"
                  description="Choose a longer date range or start a conversation."
                />
              )}
              <div className="table-scroll">
                <table>
                  <thead>
                    <tr>
                      <th>Day (UTC)</th>
                      <th>Created tickets</th>
                      <th>Resolved cycles</th>
                    </tr>
                  </thead>
                  <tbody>
                    {r.trend.map((t) => (
                      <tr key={t.day}>
                        <td>{t.day}</td>
                        <td>{t.created}</td>
                        <td>{t.resolved}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
            <div className="operations-grid">
              <section className="panel">
                <div className="panel-heading">
                  <div>
                    <h2>Current workload</h2>
                    <p>Active tickets, excluding resolved and closed</p>
                  </div>
                </div>
                <table>
                  <thead>
                    <tr>
                      <th>Assignee</th>
                      <th>Active tickets</th>
                    </tr>
                  </thead>
                  <tbody>
                    {r.workload.map((w, i) => (
                      <tr key={`${w.name}:${i}`}>
                        <td>{w.name}</td>
                        <td>{w.count}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </section>
              <section className="panel">
                <div className="panel-heading">
                  <div>
                    <h2>Backlog by priority</h2>
                    <p>Current queue · Visible membership scope</p>
                  </div>
                </div>
                <table>
                  <thead>
                    <tr>
                      <th>Priority</th>
                      <th>Active tickets</th>
                    </tr>
                  </thead>
                  <tbody>
                    {r.priorities.map((p) => (
                      <tr key={p.name}>
                        <td>{label(p.name)}</td>
                        <td>{p.count}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </section>
            </div>
            <p className="report-definition">
              Compliance includes completed or overdue eligible cycles started
              in the selected period. Unanswered response cycles are eligible
              after their due time. Paused resolution cycles are excluded until
              completion, resume or a recorded breach. First response counts
              only cycle 1. Empty samples display —. Workload and backlog show
              the current queue.
            </p>
          </>
        )
      )}
    </>
  );
}
export function ServiceLevels({ session }: { session: Session }) {
  const query = useQuery({
    queryKey: ["tenant", session.actor.tenantId, "policies"],
    queryFn: () => api<Page<Policy>>("/sla/policies"),
  });
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">TIME WELL SPENT</div>
          <h1>Service levels & business hours</h1>
          <p>
            Set clear expectations. Give every conversation the time it needs.
          </p>
        </div>
      </div>
      <ErrorBanner error={query.error} retry={() => query.refetch()} />
      {query.isPending ? (
        <Loading />
      ) : (
        query.data?.items.map((p) => (
          <PolicyEditor key={p.id} initial={p} session={session} />
        ))
      )}
    </>
  );
}
function PolicyEditor({
  initial,
  session,
}: {
  initial: Policy;
  session: Session;
}) {
  const cache = useQueryClient();
  const [p, setP] = useState(initial.policy),
    [version, setVersion] = useState(initial.version),
    [holidays, setHolidays] = useState(
      initial.policy.calendar.holidays.join("\n"),
    ),
    [success, setSuccess] = useState(false);
  const writable = ["OWNER", "ADMIN", "MANAGER"].includes(session.actor.role);
  const save = useMutation({
    mutationFn: () =>
      api<Policy>(
        `/sla/policies/${initial.id}`,
        mutation("PATCH", {
          expectedVersion: version,
          ...p,
          calendar: {
            ...p.calendar,
            holidays: holidays.split(/\s+/).filter(Boolean),
          },
        }),
      ),
    onSuccess: async (result) => {
      setVersion(result.version);
      setSuccess(true);
      await cache.invalidateQueries({
        queryKey: ["tenant", session.actor.tenantId, "policies"],
      });
    },
  });
  function calendar(update: Partial<Calendar>) {
    setP({ ...p, calendar: { ...p.calendar, ...update } });
    setSuccess(false);
  }
  function interval(
    day: string,
    index: number,
    field: "start" | "end",
    value: string,
  ) {
    calendar({
      weekly: {
        ...p.calendar.weekly,
        [day]: p.calendar.weekly[day].map((item, i) =>
          i === index ? { ...item, [field]: value } : item,
        ),
      },
    });
  }
  const dayNames = [
    "Monday",
    "Tuesday",
    "Wednesday",
    "Thursday",
    "Friday",
    "Saturday",
    "Sunday",
  ];
  return (
    <form
      className="stack"
      onSubmit={(e) => {
        e.preventDefault();
        save.mutate();
      }}
    >
      <section className="panel policy-card">
        <div className="panel-heading">
          <div>
            <h2>{initial.name}</h2>
            <p>Policy version {version} · Changes apply to new cycles</p>
          </div>
          {writable && (
            <Button type="submit" disabled={save.isPending}>
              <Save size={15} />
              {save.isPending ? "Saving…" : "Save policy"}
            </Button>
          )}
        </div>
        <ErrorBanner error={save.error} />
        {success && (
          <div className="success-banner" role="status">
            <CheckCircle2 size={15} />
            Policy saved. Existing ticket snapshots are preserved.
          </div>
        )}
        <div className="policy-targets">
          <h3>Targets in business minutes</h3>
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Priority</th>
                  <th>First response</th>
                  <th>Resolution</th>
                </tr>
              </thead>
              <tbody>
                {["URGENT", "HIGH", "NORMAL", "LOW"].map((priority) => (
                  <tr key={priority}>
                    <td>{label(priority)}</td>
                    {(["responseMinutes", "resolutionMinutes"] as const).map(
                      (field) => (
                        <td key={field}>
                          <input
                            aria-label={`${label(priority)} ${field === "responseMinutes" ? "first response" : "resolution"} minutes`}
                            type="number"
                            min="1"
                            max="525600"
                            required
                            disabled={!writable}
                            value={p.targets[priority][field]}
                            onChange={(e) =>
                              setP({
                                ...p,
                                targets: {
                                  ...p.targets,
                                  [priority]: {
                                    ...p.targets[priority],
                                    [field]: e.target.valueAsNumber,
                                  },
                                },
                              })
                            }
                          />
                        </td>
                      ),
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="policy-options">
            <label className="checkbox-label">
              <input
                type="checkbox"
                checked={p.pauseOnPending}
                disabled={!writable}
                onChange={(e) =>
                  setP({ ...p, pauseOnPending: e.target.checked })
                }
              />
              Pause resolution while waiting on customer
            </label>
            <label>
              Warning threshold (% remaining)
              <input
                type="number"
                min="1"
                max="99"
                required
                disabled={!writable}
                value={p.warningRatio}
                onChange={(e) =>
                  setP({ ...p, warningRatio: e.target.valueAsNumber })
                }
              />
            </label>
          </div>
        </div>
      </section>
      <section className="panel policy-card">
        <div className="panel-heading">
          <div>
            <h2>
              <CalendarDays size={16} />
              Business calendar
            </h2>
            <p>
              Local intervals consume real time across timezone and daylight
              saving changes.
            </p>
          </div>
        </div>
        <div className="calendar-editor">
          <label>
            Timezone
            <input
              required
              disabled={!writable}
              value={p.calendar.timezone}
              onChange={(e) => calendar({ timezone: e.target.value })}
              placeholder="Asia/Ho_Chi_Minh"
            />
          </label>
          <div className="calendar-days">
            {dayNames.map((day, i) => {
              const dayKey = String(i + 1),
                intervals = p.calendar.weekly[dayKey] ?? [];
              return (
                <div className="calendar-day" key={day}>
                  <strong>{day}</strong>
                  <div>
                    {intervals.map((v, index) => (
                      <div className="calendar-interval" key={index}>
                        <input
                          aria-label={`${day} interval ${index + 1} start`}
                          type="time"
                          value={v.start.slice(0, 5)}
                          required
                          disabled={!writable}
                          onChange={(e) =>
                            interval(dayKey, index, "start", e.target.value)
                          }
                        />
                        <span>to</span>
                        <input
                          aria-label={`${day} interval ${index + 1} end`}
                          type="time"
                          value={v.end.slice(0, 5)}
                          required
                          disabled={!writable}
                          onChange={(e) =>
                            interval(dayKey, index, "end", e.target.value)
                          }
                        />
                        {writable && (
                          <Button
                            type="button"
                            variant="ghost"
                            size="icon"
                            aria-label={`Remove ${day} interval ${index + 1}`}
                            onClick={() =>
                              calendar({
                                weekly: {
                                  ...p.calendar.weekly,
                                  [dayKey]: intervals.filter(
                                    (_, n) => n !== index,
                                  ),
                                },
                              })
                            }
                          >
                            <Trash2 size={14} />
                          </Button>
                        )}
                      </div>
                    ))}
                    {!intervals.length && <span className="muted">Closed</span>}
                  </div>
                  {writable && (
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon"
                      aria-label={`Add ${day} interval`}
                      onClick={() =>
                        calendar({
                          weekly: {
                            ...p.calendar.weekly,
                            [dayKey]: [
                              ...intervals,
                              { start: "09:00", end: "12:00" },
                            ],
                          },
                        })
                      }
                    >
                      <Plus size={15} />
                    </Button>
                  )}
                </div>
              );
            })}
          </div>
          <label>
            Holidays (one date per line)
            <textarea
              rows={4}
              disabled={!writable}
              value={holidays}
              onChange={(e) => setHolidays(e.target.value)}
              placeholder="2026-12-25"
            />
            <small className="muted">
              Use YYYY-MM-DD. The calendar stays closed on these dates.
            </small>
          </label>
        </div>
      </section>
    </form>
  );
}
