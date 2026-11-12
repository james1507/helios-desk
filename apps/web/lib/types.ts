import type { components } from "./schema";
export type Actor = components["schemas"]["Actor"];
export type Session = {
  actor: Actor;
  memberships: { id: string; slug: string; name: string; role: string }[];
};
export type Ticket = components["schemas"]["View"];
export type Message = components["schemas"]["Message"];
export type TicketEvent = components["schemas"]["Event"];
export type Page<T> = {
  items: T[];
  nextCursor: string | null;
  totalCount: number | null;
};
export type Member = { id: string; name: string; email: string; role: string };
export type Team = { id: string; name: string };
export type Audit = {
  id: string;
  action: string;
  resourceId: string;
  actorName: string;
  createdAt: string;
  correlationId: string;
};
