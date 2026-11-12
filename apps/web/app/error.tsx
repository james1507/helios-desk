"use client";
import { Button } from "@/components/ui/button";
export default function Error({ reset }: { reset: () => void }) {
  return (
    <main className="empty">
      <h1>Something went wrong</h1>
      <p>Your saved work is safe. Retry loading this page.</p>
      <Button onClick={reset}>Try again</Button>
    </main>
  );
}
