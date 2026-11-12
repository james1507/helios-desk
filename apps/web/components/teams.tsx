"use client";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, mutation } from "@/lib/api";
import type { Member, Page, Session } from "@/lib/types";
import { Button } from "./ui/button";
import { Modal } from "./ui/dialog";
import { Empty, ErrorBanner, Loading } from "./common";

type Team = {
  id: string;
  name: string;
  isActive: boolean;
  version: number;
  memberIds: string[];
};
export function Teams({ session }: { session: Session }) {
  const cache = useQueryClient(),
    a = session.actor;
  const writable = ["OWNER", "ADMIN", "MANAGER"].includes(a.role);
  const [editing, setEditing] = useState<Team>();
  const root = ["tenant", a.tenantId, a.membershipId, "teams"];
  const teams = useQuery({
    queryKey: [...root, "directory"],
    queryFn: () =>
      api<Page<Team>>(
        `/teams?includeInactive=${writable || a.role === "AUDITOR"}`,
      ),
  });
  const members = useQuery({
    queryKey: ["tenant", a.tenantId, a.membershipId, "members"],
    queryFn: () => api<Page<Member>>("/tenant/members"),
  });
  const save = useMutation({
    mutationFn: (t: Team) =>
      api<Team>(
        t.id ? `/teams/${t.id}` : "/teams",
        mutation(t.id ? "PATCH" : "POST", {
          name: t.name,
          isActive: t.isActive,
          expectedVersion: t.version,
          memberIds: t.memberIds,
        }),
      ),
    onSuccess: async () => {
      await cache.invalidateQueries({
        predicate: (q) =>
          q.queryKey[0] === "tenant" &&
          q.queryKey[1] === a.tenantId &&
          q.queryKey.includes("teams"),
      });
      setEditing(undefined);
    },
  });
  return (
    <section className="panel">
      <div className="panel-heading">
        <div>
          <h2>Support teams</h2>
          <p>
            Members control shared queue access. Deactivation preserves ticket
            history.
          </p>
        </div>
        {writable && (
          <Button
            variant="outline"
            onClick={() => {
              save.reset();
              setEditing({
                id: "",
                name: "",
                isActive: true,
                version: 0,
                memberIds: [],
              });
            }}
          >
            New team
          </Button>
        )}
      </div>
      <ErrorBanner error={teams.error ?? members.error} />
      {teams.isPending ? (
        <Loading />
      ) : teams.data?.items.length ? (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Team</th>
                <th>Members</th>
                <th>Status</th>
                {writable && <th>Actions</th>}
              </tr>
            </thead>
            <tbody>
              {teams.data.items.map((t) => (
                <tr key={t.id}>
                  <td>{t.name}</td>
                  <td>{t.memberIds.length}</td>
                  <td>{t.isActive ? "Active" : "Inactive"}</td>
                  {writable && (
                    <td>
                      <Button
                        variant="ghost"
                        aria-label={`Edit ${t.name}`}
                        onClick={() => {
                          save.reset();
                          setEditing(t);
                        }}
                      >
                        Edit
                      </Button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <Empty
          title="No teams yet"
          description="Create a team to organize support ownership."
        />
      )}
      <Modal
        title={editing?.id ? "Edit team" : "Create team"}
        description="Choose active staff for shared queue access. Existing assignments remain attributed."
        open={!!editing}
        onOpenChange={(open) => {
          if (!open) setEditing(undefined);
        }}
      >
        {editing && (
          <form
            className="form-stack"
            onSubmit={(e) => {
              e.preventDefault();
              save.mutate(editing);
            }}
          >
            <label>
              Team name
              <input
                required
                maxLength={100}
                value={editing.name}
                onChange={(e) =>
                  setEditing({ ...editing, name: e.target.value })
                }
              />
            </label>
            <label className="checkbox-label">
              <input
                type="checkbox"
                checked={editing.isActive}
                onChange={(e) =>
                  setEditing({ ...editing, isActive: e.target.checked })
                }
              />
              Active team
            </label>
            <fieldset className="team-member-options">
              <legend>Team members</legend>
              {members.data?.items
                .filter((m) => !["REQUESTER", "AUDITOR"].includes(m.role))
                .map((m) => (
                  <label className="checkbox-label" key={m.id}>
                    <input
                      type="checkbox"
                      checked={editing.memberIds.includes(m.id)}
                      onChange={(e) =>
                        setEditing({
                          ...editing,
                          memberIds: e.target.checked
                            ? [...editing.memberIds, m.id]
                            : editing.memberIds.filter((id) => id !== m.id),
                        })
                      }
                    />
                    {m.name}
                  </label>
                ))}
            </fieldset>
            <ErrorBanner error={save.error} />
            <Button
              disabled={save.isPending || members.isPending || !!members.error}
            >
              Save team
            </Button>
          </form>
        )}
      </Modal>
    </section>
  );
}
