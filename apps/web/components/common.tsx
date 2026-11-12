"use client";
import {
  AlertCircle,
  CheckCircle2,
  Circle,
  Clock3,
  Loader2,
  PauseCircle,
  Inbox,
  ArrowUp,
  ArrowDown,
  Minus,
  ChevronsUp,
} from "lucide-react";
import { label, initials } from "@/lib/utils";
import { Button } from "./ui/button";
import { ApiError } from "@/lib/api";
export function Avatar({
  name,
  size = "normal",
}: {
  name: string;
  size?: "normal" | "small";
}) {
  return (
    <span
      className={`avatar ${size === "small" ? "avatar-small" : ""}`}
      aria-hidden="true"
    >
      {initials(name)}
    </span>
  );
}
export function Status({ value }: { value: string }) {
  const Icon =
    value === "RESOLVED" || value === "CLOSED"
      ? CheckCircle2
      : value === "PENDING_CUSTOMER"
        ? Clock3
        : value === "ON_HOLD"
          ? PauseCircle
          : Circle;
  return (
    <span className={`badge status-${value.toLowerCase()}`}>
      <Icon size={12} />
      {label(value)}
    </span>
  );
}
export function Priority({ value }: { value: string }) {
  const Icon =
    value === "URGENT"
      ? ChevronsUp
      : value === "HIGH"
        ? ArrowUp
        : value === "LOW"
          ? ArrowDown
          : Minus;
  return (
    <span className={`priority priority-${value.toLowerCase()}`}>
      <Icon size={14} />
      {label(value)}
    </span>
  );
}
export function ErrorBanner({
  error,
  retry,
}: {
  error: unknown;
  retry?: () => void;
}) {
  if (!error) return null;
  return (
    <div className="error-banner" role="alert">
      <AlertCircle size={18} />
      <div>
        {error instanceof Error ? error.message : "Something went wrong."}
        {error instanceof ApiError && error.correlationId && (
          <small>Reference: {error.correlationId}</small>
        )}
      </div>
      {retry && (
        <Button variant="outline" size="sm" onClick={retry}>
          Retry
        </Button>
      )}
    </div>
  );
}
export function Loading() {
  return (
    <div className="loading" role="status">
      <Loader2 className="spin" size={22} />
      Loading workspace…
    </div>
  );
}
export function Empty({
  title = "All caught up",
  description = "No tickets match these filters.",
}: {
  title?: string;
  description?: string;
}) {
  return (
    <div className="empty">
      <div className="empty-icon">
        <Inbox size={25} />
      </div>
      <h3>{title}</h3>
      <p>{description}</p>
    </div>
  );
}
export function Logo({ compact = false }: { compact?: boolean }) {
  return (
    <div className="logo">
      <span className="logo-mark">
        <span />
        <span />
        <span />
      </span>
      {!compact && (
        <>
          helios<span className="logo-desk">desk</span>
        </>
      )}
    </div>
  );
}
