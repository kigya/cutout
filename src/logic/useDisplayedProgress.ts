import { useEffect, useRef, useState } from 'react';
import { clampPercent, estimatePercent } from './progress';

/** Keep the estimate local to the UI; native milestones still own processing stages. */
export function useDisplayedProgress(percent: number, active: boolean, taskId?: string) {
  const reported = clampPercent(percent);
  const bounded = Math.min(98, reported);
  const [displayed, setDisplayed] = useState(bounded);
  const value = useRef(bounded);
  const previousTask = useRef(taskId);
  const wasActive = useRef(false);

  useEffect(() => {
    const continuing = wasActive.current && previousTask.current === taskId;
    const base = continuing ? Math.max(value.current, bounded) : bounded;
    previousTask.current = taskId;
    wasActive.current = active;
    const publish = (next: number) => { value.current = next; setDisplayed(next); };
    publish(base);
    if (!active || base >= 98) return;
    const started = Date.now();
    const timer = setInterval(() => publish(estimatePercent(base, reported, Date.now() - started)), 250);
    return () => clearInterval(timer);
  }, [active, bounded, reported, taskId]);

  if (!active) return reported;
  return previousTask.current === taskId ? Math.max(bounded, displayed) : bounded;
}
