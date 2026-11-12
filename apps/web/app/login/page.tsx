"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { ArrowRight, Check, Eye, EyeOff, ShieldCheck } from "lucide-react";
import { api, clearCsrf, mutation } from "@/lib/api";
import type { Session } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { ErrorBanner, Logo } from "@/components/common";
export default function Login() {
  const router = useRouter(),
    cache = useQueryClient();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [visible, setVisible] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      const session = await api<Session>(
        "/auth/login",
        mutation("POST", { email, password }),
      );
      clearCsrf();
      cache.clear();
      cache.setQueryData(["session"], session);
      router.replace(
        session.actor.role === "REQUESTER"
          ? "/portal/tickets"
          : "/app/overview",
      );
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }
  return (
    <main className="login-page">
      <section className="login-story">
        <Logo />
        <div className="login-story-content">
          <div className="eyebrow light">A CLEARER WAY TO SUPPORT</div>
          <h1>
            Great support.
            <br />
            Human at heart.
          </h1>
          <p>
            Bring every conversation into focus.
            <br />
            Give your team the space to do their best work.
          </p>
          <div className="story-card">
            <div className="story-card-head">
              <span className="story-dot" />
              <span>A little more clarity. Every day.</span>
            </div>
            <div className="story-line">
              <span className="check-orb">
                <Check size={17} />
              </span>
              <div>
                <strong>Every conversation, connected</strong>
                <small>One shared workspace for your whole team</small>
              </div>
            </div>
            <div className="story-line">
              <span className="check-orb">
                <Check size={17} />
              </span>
              <div>
                <strong>The right context, right on time</strong>
                <small>Clear ownership. Thoughtful collaboration.</small>
              </div>
            </div>
            <div className="story-line">
              <span className="check-orb">
                <Check size={17} />
              </span>
              <div>
                <strong>A better experience on both sides</strong>
                <small>Happy customers start with empowered teams</small>
              </div>
            </div>
          </div>
        </div>
        <div className="login-story-footer">
          Built around people. Designed for clarity.
        </div>
      </section>
      <section className="login-form-section">
        <div className="login-form-wrap">
          <span className="welcome-icon">
            <ShieldCheck size={27} />
          </span>
          <h2>Welcome back</h2>
          <p className="muted">Sign in to your support workspace.</p>
          <form onSubmit={submit} className="stack">
            <label>
              Email address
              <input
                type="email"
                autoComplete="username"
                required
                maxLength={254}
                placeholder="you@company.com"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
              />
            </label>
            <label>
              Password
              <div className="password-field">
                <input
                  type={visible ? "text" : "password"}
                  autoComplete="current-password"
                  required
                  maxLength={128}
                  placeholder="Enter your password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                />
                <button
                  type="button"
                  aria-label={visible ? "Hide password" : "Show password"}
                  onClick={() => setVisible(!visible)}
                >
                  {visible ? <EyeOff size={18} /> : <Eye size={18} />}
                </button>
              </div>
            </label>
            <ErrorBanner error={error} />
            <Button disabled={busy} type="submit">
              {busy ? "Signing in…" : "Sign in"}
              <ArrowRight size={17} />
            </Button>
          </form>
          <div className="login-demo">
            <span>LOCAL DEMO ACCOUNTS</span>
            <p>
              Use password <code>HeliosDemo!2026</code> with:
            </p>
            <div className="demo-roles">
              {["agent1", "manager", "customer1"].map((account) => (
                <button
                  key={account}
                  type="button"
                  onClick={() => {
                    setEmail(`${account}@northstar.test`);
                    setPassword("HeliosDemo!2026");
                  }}
                >
                  {account === "agent1"
                    ? "Support agent"
                    : account === "manager"
                      ? "Manager"
                      : "Customer"}
                </button>
              ))}
            </div>
            <small>
              Demo credentials apply only to the local development seed.
            </small>
          </div>
          <p className="login-footnote">
            <ShieldCheck size={13} />
            Secure session · Private workspace
          </p>
        </div>
      </section>
    </main>
  );
}
